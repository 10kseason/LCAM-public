package dev.lunacam.app.photon.raw

import android.app.Application
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lunacam.app.photon.PhotonAspectRatio
import dev.lunacam.app.photon.PhotonProcessingGate
import dev.lunacam.app.photon.PhotonShutterSound
import dev.lunacam.app.photon.loadRecentRawPhoto
import dev.lunacam.app.photon.rawauto.RawAutoPhotoStore
import dev.lunacam.app.photon.rawauto.rawAutoCompensationBias
import dev.lunacam.app.photon.rawauto.rawAutoEvIndex
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RawCameraViewModel(application: Application, val naturalAuto: Boolean) :
    AndroidViewModel(application) {
    constructor(application: Application) : this(application, false)
    private val context = application
    private val settings = application.getSharedPreferences("photon-raw", Application.MODE_PRIVATE)
    private val modeSettings = application.getSharedPreferences(
        if (naturalAuto) "photon-raw-auto-ui" else "photon-raw-ui",
        Application.MODE_PRIVATE
    )
    private var selectedAspect by mutableStateOf(
        PhotonAspectRatio.fromPreference(modeSettings.getString("aspect", null))
    )
    val aspectRatio: PhotonAspectRatio get() = selectedAspect
    var gridEnabled by mutableStateOf(modeSettings.getBoolean("grid", false))
        private set
    var levelEnabled by mutableStateOf(modeSettings.getBoolean("level", false))
        private set
    var histogramEnabled by mutableStateOf(modeSettings.getBoolean("histogram", false))
        private set
    var recentPhotoUri by mutableStateOf<Uri?>(null)
        private set
    var recentPhotoMimeType by mutableStateOf(
        if (naturalAuto) "image/jpeg" else "image/x-adobe-dng"
    )
        private set
    private var recentLoadVersion = 0
    var unsupported by mutableStateOf(false)
        private set

    fun setAspectRatio(value: PhotonAspectRatio) {
        if (busy) return
        selectedAspect = value
        focusPoint = null
        modeSettings.edit { putString("aspect", value.id) }
    }

    fun setGrid(value: Boolean) {
        gridEnabled = value
        modeSettings.edit { putBoolean("grid", value) }
    }

    fun setLevel(value: Boolean) {
        levelEnabled = value
        modeSettings.edit { putBoolean("level", value) }
    }

    fun setHistogram(value: Boolean) {
        histogramEnabled = value
        modeSettings.edit { putBoolean("histogram", value) }
    }

    private fun refreshRecentPhoto() {
        val version = ++recentLoadVersion
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { loadRecentRawPhoto(context, naturalAuto) }
            if (version == recentLoadVersion) {
                recentPhotoUri = photo?.uri
                recentPhotoMimeType =
                    photo?.mimeType ?: if (naturalAuto) "image/jpeg" else "image/x-adobe-dng"
            }
        }
    }
    private var camera: RawBurstCamera? = null
    private var opening: Job? = null
    private var generation = 0
    private var zoomDebounce: Job? = null
    private var controlUpdate: Job? = null
    private var controlRequest = 0
    private var controlLensId: String? = null
    var capabilities by mutableStateOf(RawControlCapabilities())
        private set
    var controls by mutableStateOf(RawControls())
        private set
    var observedControls by mutableStateOf<RawControlObservation?>(null)
        private set
    var controlsUpdating by mutableStateOf(false)
        private set
    var controlsFailed by mutableStateOf(false)
        private set
    var focusPoint by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    var focusStatus by mutableStateOf("")
        private set
    private val failedOpticalIds = mutableSetOf<String>()
    private var lastGoodId: String? = null

    // Keep the requested field of view across camera recreation and physical-lens changes.
    var zoomRatio by mutableStateOf(1f)
        private set
    var digitalZoomRatio by mutableStateOf(1f)
        private set
    private val waitingForCapacity = mutableListOf<RawCaptureJob>()
    var lenses by mutableStateOf<List<RawLens>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var opticalLenses by mutableStateOf<List<RawLens>>(emptyList())
        private set
    var autoLens by mutableStateOf(true)
        private set
    var switching by mutableStateOf(false)
        private set
    val opticalLabel: String?
        get() = opticalLenses.firstOrNull { it.id == selectedId }?.label
    val minimumZoomRatio: Float
        get() = (opticalLenses.firstOrNull()?.opticalRatio ?: RawZoomPolicy.MIN_RATIO)
            .coerceAtLeast(RawZoomPolicy.MIN_RATIO)
    val maximumZoomRatio: Float
        get() = RawZoomPolicy.MAX_RATIO
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var processingCount by mutableStateOf(0)
        private set
    val canCapture: Boolean
        get() = ready && !busy && !switching && !controlsUpdating && !controlsFailed &&
            processingCount < MAX_IN_FLIGHT
    var mergeEnabled by mutableStateOf(settings.getBoolean("merge", false))
        private set
    var message by mutableStateOf("RAW 카메라 준비 중…")
        private set
    var shadowStrength by mutableStateOf(
        modeSettings.getFloat("shadowStrength", 0.35f).takeIf { it in listOf(0f, 0.35f, 0.6f) }
            ?: 0.35f
    )
        private set
    var hdrEnabled by mutableStateOf(modeSettings.getBoolean("hdr", true))
        private set
    var recoverableCount by mutableStateOf(0)
        private set

    fun updateShadowStrength(value: Float) {
        if (!naturalAuto || busy || value !in listOf(0f, 0.35f, 0.6f)) return
        shadowStrength = value
        modeSettings.edit { putFloat("shadowStrength", value) }
    }

    fun setHdr(enabled: Boolean) {
        if (!naturalAuto || busy) return
        hdrEnabled = enabled
        modeSettings.edit { putBoolean("hdr", enabled) }
    }

    private fun refreshRecovery() {
        if (naturalAuto) {
            viewModelScope.launch {
                recoverableCount =
                    withContext(Dispatchers.IO) { RawAutoPhotoStore(context).pendingCount() }
            }
        }
    }

    fun recoverNaturalPhoto() {
        if (!naturalAuto || busy || processingCount != 0) return
        processingCount++
        viewModelScope.launch {
            try {
                val result = PhotonProcessingGate.mutex.withLock {
                    RawAutoPhotoStore(context).recover()
                }
                message = result?.message ?: "복구할 사진이 없습니다."
                refreshRecentPhoto()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "복구 저장 실패 · 원본은 앱에 보존되어 있습니다."
            } finally {
                processingCount--
                refreshRecovery()
                releaseWaitingShutters()
            }
        }
    }

    fun open(view: TextureView) {
        closeCamera()
        unsupported = false
        refreshRecentPhoto()
        refreshRecovery()
        switching = true
        val token = generation
        opening = viewModelScope.launch {
            var candidate: RawBurstCamera? = null
            var selected: RawLens? = null
            try {
                candidate = RawBurstCamera(context)
                camera = candidate
                lenses = candidate.lenses()
                opticalLenses = candidate.opticalLenses().filter {
                    it.id !in failedOpticalIds && it.opticalRatio?.let { ratio ->
                        ratio.isFinite() && ratio > 0f && ratio <= RawZoomPolicy.MAX_RATIO
                    } == true
                }.sortedBy { it.opticalRatio }.distinctBy { it.opticalRatio }
                if (naturalAuto) check(opticalLenses.isNotEmpty()) { "NaturalRawUnsupported" }
                if (opticalLenses.isEmpty()) autoLens = false
                selected = if (autoLens) {
                    val current = opticalLenses.indexOfFirst { it.id == selectedId }
                        .takeIf { it >= 0 } ?: opticalLenses.indices.minBy {
                        abs(requireNotNull(opticalLenses[it].opticalRatio) - 1f)
                    }
                    val zoom = RawZoomPolicy.select(
                        opticalLenses.map { requireNotNull(it.opticalRatio) },
                        current,
                        zoomRatio
                    )
                    zoomRatio = zoom.requestedRatio
                    digitalZoomRatio = zoom.digitalRatio
                    opticalLenses[zoom.index]
                } else {
                    digitalZoomRatio = 1f
                    lenses.firstOrNull { it.id == selectedId } ?: lenses.firstOrNull()
                }
                val lens = selected ?: error("NoRawCamera")
                selectedId = lens.id
                candidate.open(lens, view)
                candidate.setDigitalZoom(digitalZoomRatio, view)
                val support = candidate.controlCapabilities()
                val observed = candidate.controlObservation()
                val requested = if (controlLensId == lens.id) {
                    controls
                } else {
                    RawControls(
                        exposureNs = observed.exposureNs ?: 8_333_333,
                        iso = observed.iso ?: 100,
                        evIndex = if (naturalAuto) rawAutoEvIndex(support) else 0
                    )
                }
                val bounded = requested.bounded(support)
                val applied = candidate.setControls(bounded)
                if (generation == token) {
                    capabilities = support
                    controls = bounded
                    observedControls = applied
                    controlsFailed = false
                    controlLensId = lens.id
                    ready = true
                    switching = false
                    lastGoodId = lens.id
                    releaseWaitingShutters()
                    if (!busy) {
                        message = if (naturalAuto) {
                            "RAW AUTO 준비 완료 · 하늘·거리 톤은 저장 사진에 적용됩니다."
                        } else {
                            "RAW 준비 완료 · 움직임이 적은 장면에서 사용하세요."
                        }
                    }
                }
            } catch (error: Exception) {
                candidate?.close()
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                if (generation == token) {
                    ready = false
                    switching = false
                    val failed = selected
                    if (autoLens && failed?.physicalId != null && rawLensIsIncompatible(error)) {
                        // A published characteristic is not a supported stream combination.
                        // Reject this candidate for this ViewModel lifetime and recover safely.
                        failedOpticalIds += failed.id
                        opticalLenses = opticalLenses.filter { it.id !in failedOpticalIds }
                        selectedId = lastGoodId?.takeIf { it !in failedOpticalIds }
                        if (opticalLenses.isEmpty()) autoLens = false
                        message = "이 광학 렌즈의 RAW 연결을 지원하지 않아 사용 가능한 렌즈로 복귀합니다."
                        open(view)
                    } else {
                        // Only an exhausted/absent RAW capability triggers startup fallback.
                        // A temporary camera-in-use or permission error remains retryable.
                        unsupported = naturalAuto && error.message == "NaturalRawUnsupported"
                        message = if (naturalAuto) {
                            if (unsupported) {
                                "지원되는 RAW 카메라가 없어 AUTO로 전환합니다."
                            } else {
                                "RAW 연결 실패 · 다른 카메라 앱을 닫고 재연결해 주세요."
                            }
                        } else {
                            "RAW 카메라를 열지 못했습니다. 렌즈 변경 또는 재연결해 주세요."
                        }
                    }
                }
            }
        }
    }

    fun select(id: String, view: TextureView) {
        if (naturalAuto) return
        if (busy || switching || (!autoLens && id == selectedId)) return
        autoLens = false
        selectedId = id
        open(view)
    }

    fun selectAuto(view: TextureView) {
        if (busy || switching || autoLens || opticalLenses.isEmpty()) return
        autoLens = true
        selectedId = null
        open(view)
    }

    fun pinch(scale: Float, view: TextureView) {
        if (!scale.isFinite() || scale <= 0f) return
        setZoom(zoomRatio * scale, view)
    }

    fun setZoom(value: Float, view: TextureView) {
        if (!autoLens || busy || !ready || controlsUpdating || opticalLenses.isEmpty()) return
        if (!value.isFinite()) return
        val ratios = opticalLenses.map { requireNotNull(it.opticalRatio) }
        val current = opticalLenses.indexOfFirst { it.id == selectedId }
        if (current < 0) return
        val zoom = RawZoomPolicy.select(ratios, current, value)
        zoomRatio = zoom.requestedRatio
        focusPoint = null
        focusStatus = ""
        zoomDebounce?.cancel()
        if (zoom.index == current) {
            digitalZoomRatio = zoom.digitalRatio
            camera?.setDigitalZoom(digitalZoomRatio, view)
            switching = false
            releaseWaitingShutters()
            return
        }
        // Cropping the old sensor bridges zoom-in while the new physical session is prepared.
        // Zoom-out never invents pixels outside that old sensor; the brief switch stays gated.
        digitalZoomRatio = (zoomRatio / ratios[current]).coerceAtLeast(1f)
        camera?.setDigitalZoom(digitalZoomRatio, view)
        switching = true
        zoomDebounce = viewModelScope.launch {
            delay(200)
            if (busy || !ready) return@launch
            selectedId = opticalLenses[zoom.index].id
            open(view)
        }
    }

    fun setMerge(enabled: Boolean) {
        if (busy || naturalAuto) return
        mergeEnabled = enabled
        settings.edit { putBoolean("merge", enabled) }
    }

    fun updateControls(requested: RawControls) {
        val active = camera ?: return
        if (!ready || busy || switching) return
        controls = requested.bounded(capabilities)
        focusPoint = null
        focusStatus = ""
        val value = controls
        val request = ++controlRequest
        val token = generation
        controlUpdate?.cancel()
        controlsUpdating = true
        controlsFailed = false
        controlUpdate = viewModelScope.launch {
            try {
                delay(100)
                val actual = active.setControls(value)
                if (generation == token && request == controlRequest) {
                    observedControls = actual
                    message = "RAW 설정 반영 프레임 확인 · 촬영 준비"
                }
            } catch (error: Exception) {
                if (error is CancellationException &&
                    error !is TimeoutCancellationException
                ) {
                    throw error
                }
                if (generation == token && request == controlRequest) {
                    controlsFailed = true
                    message = "설정 반영을 확인하지 못했습니다. 설정 초기화 또는 재연결해 주세요."
                }
            } finally {
                if (generation == token && request == controlRequest) controlsUpdating = false
            }
        }
    }

    fun focus(view: TextureView, x: Float, y: Float) {
        val active = camera ?: return
        if (!ready || busy || switching || controlsUpdating || view.width <= 0 ||
            view.height <= 0
        ) {
            return
        }
        if (!capabilities.tapAf && !capabilities.tapAe) {
            message = "이 렌즈는 터치 초점·측광을 지원하지 않습니다."
            return
        }
        if (!capabilities.tapAf && controls.manualExposure) {
            message = "수동 노출에서는 ISO·셔터로 밝기를 조절합니다. 이 렌즈는 터치 AF를 지원하지 않습니다."
            return
        }
        val token = generation
        val request = ++controlRequest
        controlsUpdating = true
        controlsFailed = false
        if (capabilities.tapAf) controls = controls.copy(focusDiopters = null)
        focusPoint = (x / view.width) to (y / view.height)
        focusStatus = "초점·측광 조절 중"
        controlUpdate = viewModelScope.launch {
            try {
                val actual = active.tapFocus(
                    x,
                    y,
                    view.width,
                    view.height,
                    view.display?.rotation ?: 0
                )
                if (generation == token && request == controlRequest) {
                    observedControls = actual
                    focusStatus = when {
                        !capabilities.tapAf -> "측광 위치 설정"
                        actual.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "초점 맞춤"
                        else -> "초점 실패 · 다시 터치하세요"
                    }
                    message = focusStatus
                }
            } catch (error: Exception) {
                if (error is CancellationException &&
                    error !is TimeoutCancellationException
                ) {
                    throw error
                }
                if (generation == token && request == controlRequest) {
                    controlsFailed = true
                    focusStatus = "초점 확인 시간 초과"
                    message = "초점을 확인하지 못했습니다. 다시 터치하거나 설정을 초기화하세요."
                }
            } finally {
                if (generation == token && request == controlRequest) controlsUpdating = false
            }
        }
    }

    fun updateTransform(view: TextureView) {
        focusPoint = null
        focusStatus = ""
        camera?.updateTransform(view)
    }

    fun capture(rotation: Int) {
        val active = camera ?: return
        if (!canCapture) return
        if (!shutterSound.play()) {
            message = "셔터음을 준비하지 못했습니다. 촬영을 다시 시도해 주세요."
            return
        }
        val merge = mergeEnabled && !naturalAuto
        val hdr = naturalAuto && hdrEnabled
        val toneStrength = shadowStrength
        val framing = aspectRatio
        val compensationBias = if (naturalAuto) {
            rawAutoCompensationBias(capabilities)
        } else {
            0f
        }
        val jobId = UUID.randomUUID().toString()
        val shutterStarted = SystemClock.elapsedRealtime()
        val shutterWallTime = System.currentTimeMillis()
        busy = true
        message =
            if (naturalAuto) {
                if (hdr) "RAW AUTO 밝기 3단계 촬영 중… 잠시 고정해 주세요." else "RAW AUTO 촬영 중…"
            } else if (merge) {
                "RAW 3장 촬영 중…"
            } else {
                "RAW 1장 촬영 중…"
            }
        viewModelScope.launch {
            try {
                val burst = active.capture(if (merge || hdr) 3 else 1, rotation, hdr = hdr)
                    .copy(aspectRatio = framing, compensationBiasEv = compensationBias)
                val job = RawCaptureJob(
                    jobId,
                    shutterStarted,
                    shutterWallTime,
                    SystemClock.elapsedRealtime()
                )
                // Camera acquisition ends here; processing retains this bounded slot separately.
                processingCount++
                busy = false
                if (!burst.previewRestored) ready = false
                // A preserved burst is still saveable after preview restoration fails, but that
                // capture must not produce a successful shutter-ready latency measurement.
                if (burst.previewRestored) waitingForCapacity += job
                releaseWaitingShutters()
                message = if (burst.previewRestored) {
                    "RAW 수신 완료 · 백그라운드 처리 ${processingCount}건"
                } else {
                    "RAW 수신 완료 · 저장 중 · 미리보기 복원 실패, 다음 촬영 전 재연결해 주세요."
                }
                // Activity-scoped ViewModel survives leaving this screen or switching lenses.
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val receipt = JSONObject().put("schemaVersion", 2).put("jobId", job.id)
                                .put(
                                    "pipeline",
                                    when {
                                        hdr -> "raw_auto_hdr_v2"
                                        naturalAuto -> "raw_auto_single_v1"
                                        else -> "raw_dng"
                                    }
                                )
                                .put("status", "queued").put("captureMs", burst.elapsedMs)
                                .put("previewRestored", burst.previewRestored)
                                .put("cameraId", burst.cameraId ?: JSONObject.NULL)
                                .put("opticalRatio", burst.opticalRatio ?: JSONObject.NULL)
                                .put(
                                    "physicalIds",
                                    JSONArray(burst.frames.map { it.physicalId }.distinct())
                                )
                                .put("shutterStartedElapsedMs", job.shutterStartedElapsedMs)
                                .put("shutterStartedWallTimeMs", job.shutterStartedWallTimeMs)
                                .put("rawReceivedElapsedMs", job.rawReceivedElapsedMs)
                                .put("shutterReadyElapsedMs", job.shutterReadyElapsedMs)
                                .put(
                                    "shutterReadyMs",
                                    job.shutterReadyElapsedMs?.minus(job.shutterStartedElapsedMs)
                                        ?: JSONObject.NULL
                                )
                                .put("outputCount", 0)
                            RawJobJournal.write(context, receipt, false)
                        }
                        val savedMessage = PhotonProcessingGate.mutex.withLock {
                            if (naturalAuto) {
                                RawAutoPhotoStore(context).save(burst, toneStrength, job).message
                            } else {
                                RawPhotoStore(context).save(burst, merge, job).message
                            }
                        }
                        refreshRecentPhoto()
                        if (!busy) {
                            message = savedMessage + if (!burst.previewRestored && !ready) {
                                " · 미리보기 복원 실패, 다음 촬영 전 재연결해 주세요."
                            } else {
                                ""
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: OutOfMemoryError) {
                        if (!busy) message = "메모리가 부족해 RAW를 저장하지 못했습니다."
                    } catch (_: Exception) {
                        if (!busy) message = "RAW 처리를 완료하지 못했습니다."
                    } finally {
                        processingCount--
                        refreshRecovery()
                        releaseWaitingShutters()
                        // Jobs cancelled by Activity destruction are no longer eligible for a tap.
                        waitingForCapacity.remove(job)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                message = "RAW 촬영 시간이 초과돼 저장하지 못했습니다. 재연결해 주세요."
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                message = "RAW 촬영 메모리가 부족합니다. AUTO 촬영을 사용해 주세요."
            } catch (error: Exception) {
                message = when (error.message) {
                    "RAW focus lock failed" ->
                        "초점을 잡지 못했습니다. 밝고 무늬가 있는 곳에 맞춘 뒤 다시 촬영해 주세요."
                    "RAW metering not settled" ->
                        "노출과 색온도를 조정 중입니다. 잠시 고정한 뒤 다시 촬영해 주세요."
                    else -> "RAW 촬영이 중단됐습니다. 재연결 후 다시 촬영해 주세요."
                }
            } finally {
                busy = false
                releaseWaitingShutters()
            }
        }
    }

    private fun releaseWaitingShutters() {
        if (!canCapture) return
        val now = SystemClock.elapsedRealtime()
        val released = waitingForCapacity.toList()
        released.forEach { it.markShutterReady(now) }
        waitingForCapacity.clear()
        if (released.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                released.forEach { RawJobJournal.updateShutterReady(context, it) }
            }
        }
    }

    fun closeCamera() {
        controlRequest++
        controlUpdate?.cancel()
        controlUpdate = null
        controlsUpdating = false
        focusPoint = null
        focusStatus = ""
        zoomDebounce?.cancel()
        zoomDebounce = null
        switching = false
        generation++
        ready = false
        opening?.cancel()
        opening = null
        camera?.close()
        camera = null
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
