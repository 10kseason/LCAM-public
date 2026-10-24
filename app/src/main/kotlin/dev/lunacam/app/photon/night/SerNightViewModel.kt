package dev.lunacam.app.photon.night

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lunacam.app.photon.PhotonProcessingGate
import dev.lunacam.app.photon.PhotonShutterSound
import dev.lunacam.app.photon.raw.NightReadiness
import dev.lunacam.app.photon.raw.RawBurst
import dev.lunacam.app.photon.raw.RawBurstCamera
import dev.lunacam.app.photon.raw.RawCaptureJob
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Acquisition follows the screen lifecycle; the two processing slots follow the Activity. */
class SerNightViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val settings = application.getSharedPreferences(
        "photon-night",
        Application.MODE_PRIVATE
    )
    private var camera: RawBurstCamera? = null
    private var opening: Job? = null
    private var polling: Job? = null
    private var captureJob: Job? = null
    private var generation = 0
    private var readinessRevision = 0
    private val waitingForReadiness = mutableListOf<RawCaptureJob>()

    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var processingCount by mutableStateOf(0)
        private set
    var recoverableCount by mutableStateOf(0)
        private set
    val canCapture: Boolean
        get() = ready && !busy && processingCount < MAX_IN_FLIGHT
    var gridEnabled by mutableStateOf(settings.getBoolean("grid", false))
        private set
    var levelEnabled by mutableStateOf(settings.getBoolean("level", false))
        private set
    var serEnabled by mutableStateOf(settings.getBoolean("ser", true))
        private set
    var message by mutableStateOf("SER 나이트 카메라 준비 중…")
        private set
    var lastShutterReadyMs by mutableStateOf<Long?>(null)
        private set
    var recentPhotoUri by mutableStateOf<Uri?>(null)
        private set
    var previewReadiness by mutableStateOf(NightReadiness(false, "camera_closed"))
        private set
    val focusColorHeld: Boolean
        get() = previewReadiness.heldFocusWhiteBalance

    init {
        refreshRecoverableCount()
    }

    fun open(view: TextureView) {
        closeCamera()
        val token = generation
        message = "SER 나이트 카메라 연결 중…"
        refreshRecoverableCount()
        opening = viewModelScope.launch {
            val candidate = RawBurstCamera(context)
            camera = candidate
            try {
                val lens = candidate.nightLens() ?: error("unsupported_night_source")
                candidate.open(lens, view)
                if (generation != token) return@launch
                polling = viewModelScope.launch {
                    while (generation == token) {
                        refreshReadiness(candidate, token)
                        delay(100)
                    }
                }
            } catch (error: Exception) {
                candidate.close()
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                if (generation == token) {
                    ready = false
                    message = if (error.message == "unsupported_night_source") {
                        "이 기기는 SER 나이트의 후면 1× RAW 구성을 지원하지 않습니다."
                    } else {
                        "나이트 카메라 연결에 실패했습니다. AUTO로 돌아간 뒤 다시 열어 주세요."
                    }
                }
            }
        }
    }

    private suspend fun refreshReadiness(active: RawBurstCamera, token: Int) {
        val revision = readinessRevision
        val state = active.nightReadiness()
        if (generation != token || revision != readinessRevision) return
        val changed = state.reason != previewReadiness.reason
        previewReadiness = state
        ready = state.ready && !busy
        releaseWaitingShutters()
        if (!busy && (changed || !state.ready)) {
            message = when (state.reason) {
                "ready" -> if (processingCount == 0) {
                    "촬영 준비 완료 · 정지 장면에서 사용하세요."
                } else {
                    "백그라운드 처리 ${processingCount}건"
                }
                "focus_settling" -> "초점을 맞추는 중… 무늬가 있는 피사체에 맞춰 주세요."
                "exposure_settling", "white_balance_settling" -> "노출과 색을 맞추는 중…"
                "preview_stale", "preview_restoring", "capture_busy" -> "프리뷰 복귀 확인 중…"
                "camera_closed" -> "카메라 연결이 끊겼습니다. AUTO로 돌아간 뒤 다시 열어 주세요."
                else -> "나이트 촬영에 필요한 카메라 메타데이터를 확인하지 못했습니다."
            }
        }
    }

    fun setGrid(enabled: Boolean) {
        gridEnabled = enabled
        settings.edit { putBoolean("grid", enabled) }
    }

    fun setLevel(enabled: Boolean) {
        levelEnabled = enabled
        settings.edit { putBoolean("level", enabled) }
    }

    fun setSer(enabled: Boolean) {
        if (busy) return
        serEnabled = enabled
        settings.edit { putBoolean("ser", enabled) }
    }

    fun updateTransform(view: TextureView) = camera?.updateTransform(view)

    fun refocus() {
        if (busy) return
        val active = camera ?: return
        readinessRevision++
        ready = false
        previewReadiness = previewReadiness.copy(
            ready = false,
            reason = "focus_settling",
            heldFocusWhiteBalance = false
        )
        message = "초점과 색을 다시 맞추는 중…"
        active.refocusNight()
    }

    private fun refreshRecoverableCount() {
        viewModelScope.launch {
            recoverableCount = withContext(Dispatchers.IO) {
                runCatching { SerNightPhotoStore(context).pendingCount() }.getOrDefault(0)
            }
        }
    }

    fun recoverPending() {
        if (busy || processingCount != 0 || recoverableCount == 0) return
        processingCount++
        message = "보존한 기준 사진을 복구 중…"
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    PhotonProcessingGate.mutex.withLock {
                        SerNightPhotoStore(context).recoverPending()
                    }
                }
                if (saved?.receipt?.optString("mimeType") == "image/jpeg") {
                    recentPhotoUri = saved.uri
                }
                if (!busy) message = saved?.message ?: "복구할 기준 사진이 없습니다."
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!busy) message = "기준 사진 복구에 실패했습니다. 보존된 파일은 유지됩니다."
            } finally {
                processingCount--
                releaseWaitingShutters()
                refreshRecoverableCount()
            }
        }
    }

    fun capture(displayRotation: Int) {
        val active = camera ?: return
        if (!canCapture) return
        if (!shutterSound.play()) {
            message = "셔터음을 준비하지 못했습니다. 촬영을 다시 시도해 주세요."
            return
        }
        val token = generation
        val ser = serEnabled
        val id = UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        val wallTime = System.currentTimeMillis()
        busy = true
        ready = false
        message = if (ser) "나이트 RAW 수신 중…" else "기준 RAW 수신 중…"
        captureJob = viewModelScope.launch {
            try {
                val burst = active.captureNight(if (ser) 3 else 1, displayRotation)
                val timing = requireNotNull(burst.nightTiming)
                val job = RawCaptureJob(
                    id,
                    started,
                    wallTime,
                    requireNotNull(timing.rawReceivedElapsedMs)
                )
                // Reserve the slot before allowing another tap, even if the shared worker is busy.
                processingCount++
                waitingForReadiness += job
                launchProcessing(burst, ser, job)
                if (generation == token) {
                    busy = false
                    message = "RAW 수신 완료 · 백그라운드 처리 ${processingCount}건"
                    refreshReadiness(active, token)
                }
            } catch (error: Exception) {
                val receipt = JSONObject().put("schemaVersion", 1).put("jobId", id)
                    .put("status", "acquisition_failed").put("outputCount", 0)
                    .put("shutterStartedElapsedMs", started)
                    .put("shutterStartedWallTimeMs", wallTime)
                    .put("failureElapsedMs", SystemClock.elapsedRealtime())
                    .put("failure", error.javaClass.simpleName)
                    .put("nightTiming", active.lastNightTiming?.toJson() ?: JSONObject.NULL)
                // A lifecycle cancellation is also an acquisition outcome, not a saved image.
                viewModelScope.launch(Dispatchers.IO) {
                    SerNightJournal.write(context, receipt, false)
                }
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                if (generation == token) {
                    ready = false
                    message = if (error.message?.startsWith("Night not ready:") == true) {
                        "초점·노출 준비 상태가 바뀌어 촬영하지 않았습니다. 준비 완료 후 다시 눌러 주세요."
                    } else {
                        "나이트 촬영을 마치지 못했습니다. AUTO로 돌아간 뒤 다시 열어 주세요."
                    }
                }
            } finally {
                if (generation == token) {
                    busy = false
                    // Readiness remains false until a fresh auto-preview result is observed.
                    releaseWaitingShutters()
                }
            }
        }
    }

    private fun launchProcessing(burst: RawBurst, ser: Boolean, job: RawCaptureJob) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val receipt = JSONObject().put("schemaVersion", 1).put("jobId", job.id)
                        .put("status", "queued").put("outputCount", 0)
                        .put("shutterStartedElapsedMs", job.shutterStartedElapsedMs)
                        .put("shutterStartedWallTimeMs", job.shutterStartedWallTimeMs)
                        .put("rawReceivedElapsedMs", job.rawReceivedElapsedMs)
                        .put("captureMs", job.rawReceivedElapsedMs - job.shutterStartedElapsedMs)
                        .put("shutterReadyElapsedMs", job.shutterReadyElapsedMs ?: JSONObject.NULL)
                        .put("nightTiming", burst.nightTiming?.toJson() ?: JSONObject.NULL)
                    SerNightJournal.write(context, receipt, false)
                }
                val saved = withContext(Dispatchers.Default) {
                    PhotonProcessingGate.mutex.withLock {
                        SerNightPhotoStore(context).save(burst, ser, job)
                    }
                }
                if (saved.receipt.optString("mimeType") == "image/jpeg") {
                    recentPhotoUri = saved.uri
                }
                if (!busy) message = saved.message
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                if (!busy) message = "메모리가 부족해 나이트 처리를 마치지 못했습니다."
            } catch (_: Exception) {
                if (!busy) message = "나이트 저장을 마치지 못했습니다. 보존된 기준 파일을 확인해 주세요."
            } finally {
                processingCount--
                releaseWaitingShutters()
                refreshRecoverableCount()
                // Keep completed jobs awaiting camera readiness; a paused screen is not ready.
            }
        }
    }

    private fun releaseWaitingShutters() {
        if (!canCapture || waitingForReadiness.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val released = waitingForReadiness.toList()
        released.forEach { it.markShutterReady(now) }
        lastShutterReadyMs = released.last().let { now - it.shutterStartedElapsedMs }
        waitingForReadiness.clear()
        viewModelScope.launch(Dispatchers.IO) {
            released.forEach { SerNightJournal.updateShutterReady(context, it) }
        }
    }

    fun closeCamera() {
        val interrupted = waitingForReadiness.toList()
        val closedAt = SystemClock.elapsedRealtime()
        if (interrupted.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                interrupted.forEach { SerNightJournal.markCameraClosed(context, it, closedAt) }
            }
        }
        generation++
        ready = false
        busy = false
        opening?.cancel()
        opening = null
        polling?.cancel()
        polling = null
        captureJob?.cancel()
        captureJob = null
        camera?.close()
        camera = null
        previewReadiness = NightReadiness(false, "camera_closed")
    }

    private val shutterSound = PhotonShutterSound()

    override fun onCleared() {
        shutterSound.close()
        closeCamera()
    }

    private companion object {
        const val MAX_IN_FLIGHT = 2
    }
}
