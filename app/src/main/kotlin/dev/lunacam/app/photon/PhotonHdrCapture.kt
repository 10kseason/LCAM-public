package dev.lunacam.app.photon

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CapturedJpegs(
    val base: File,
    val short: File?,
    /** Sensor/file callbacks plus exposure restoration; excludes later image processing. */
    val captureMs: Long,
    val hdrRequested: Boolean,
    /** A captured bracket is not yet a successfully merged HDR image. */
    val hdrReason: String,
    val stages: Map<String, Long> = emptyMap()
)

/** Callback receipt time uses elapsedRealtime; sensor timestamps can have another clock source. */
data class PhotonMeteredFrame(val result: TotalCaptureResult, val receivedElapsedMs: Long)

/**
 * One instance per ImageCapture binding; observes metadata only, never owns the session.
 * Supply the main executor so completion actions serialize with the capture helper.
 */
class PhotonStillCaptureEvents(private val executor: Executor) {
    internal data class Completed(val session: CameraCaptureSession, val frameNumber: Long)

    internal class Watch(
        val exposure: Long,
        val iso: Int,
        val after: Completed?,
        val minimumFrame: Long,
        val startedListener: (() -> Unit)?,
        val listener: () -> Unit
    ) {
        var startedRequest: CaptureRequest? = null
        var startedFrame = -1L
        var completed: Completed? = null
    }

    private val lock = Any()
    private var active: Watch? = null
    private var latestFrame = -1L

    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            val watch = synchronized(lock) {
                latestFrame = maxOf(latestFrame, frameNumber)
                val watch = active ?: return
                if (!matches(watch, session, request, frameNumber)) return
                watch.startedRequest = request
                watch.startedFrame = frameNumber
                watch
            }
            watch.startedListener?.let { listener ->
                executor.execute {
                    if (synchronized(lock) { active === watch }) listener()
                }
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val watch = synchronized(lock) {
                latestFrame = maxOf(latestFrame, result.frameNumber)
                val candidate = active ?: return
                if (candidate.completed != null ||
                    candidate.startedRequest != request ||
                    candidate.startedFrame != result.frameNumber ||
                    !matches(candidate, session, request, result.frameNumber)
                ) {
                    return
                }
                candidate.completed = Completed(session, result.frameNumber)
                candidate
            }
            executor.execute {
                if (synchronized(lock) { active === watch }) watch.listener()
            }
        }
    }

    internal fun watch(
        exposure: Long,
        iso: Int,
        minimumFrame: Long,
        after: Completed? = null,
        startedListener: (() -> Unit)? = null,
        listener: () -> Unit = {}
    ): Watch = synchronized(lock) {
        check(active == null) { "A still result observer is already active" }
        Watch(
            exposure,
            iso,
            after,
            maxOf(minimumFrame, latestFrame),
            startedListener,
            listener
        ).also {
            active = it
        }
    }

    internal fun finish(watch: Watch): Completed? = synchronized(lock) {
        if (active === watch) active = null
        watch.completed
    }

    private fun matches(
        watch: Watch,
        session: CameraCaptureSession,
        request: CaptureRequest,
        frameNumber: Long
    ): Boolean = !request.isReprocess && frameNumber > watch.minimumFrame &&
        (
            watch.after == null ||
                (session === watch.after.session && frameNumber > watch.after.frameNumber)
            ) &&
        request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) ==
        CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE &&
        request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF &&
        request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == watch.exposure &&
        request.get(CaptureRequest.SENSOR_SENSITIVITY) == watch.iso
}

/** A failed restore requires the caller to rebind before accepting another shutter request. */
class PhotonHdrCaptureException(
    message: String,
    val retainedBase: File?,
    val requiresRebind: Boolean,
    cause: Throwable
) : Exception(message, cause)

/**
 * Captures an ordinary JPEG or metered / quarter-exposure bracket in a private directory.
 * Manual sensor, AF and AWB overrides are used only when the user enables HDR and recent,
 * settled metering proves the lens can support them. Unsupported cases save one ordinary JPEG.
 * The 1.1 s budget controls admission of the second request, not CameraX's completion latency.
 * Once submitted, requests are awaited even after cancellation so a late camera callback cannot
 * overlap a new capture. Completed source files survive cancellation and restoration failures.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class PhotonHdrCapture(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()

    suspend fun capture(
        photo: ImageCapture,
        camera: Camera,
        outputDirectory: File,
        hdr: Boolean,
        rotation: Int,
        meteredFrame: PhotonMeteredFrame? = null,
        stillEvents: PhotonStillCaptureEvents? = null
    ): CapturedJpegs = mutex.withLock {
        require(
            rotation in setOf(
                Surface.ROTATION_0,
                Surface.ROTATION_90,
                Surface.ROTATION_180,
                Surface.ROTATION_270
            )
        )
        val directory = privateDirectory(outputDirectory)
        val base = File(directory, "base.jpg")
        val short = File(directory, "short.jpg")
        require(!base.exists() && !short.exists()) { "Capture destination already exists" }
        val caller = currentCoroutineContext()[Job]
        caller?.ensureActive()
        // CameraX has no per-request file-capture cancellation. Do not return to the controller
        // until the outstanding callback and any exposure restoration have actually completed.
        withContext(Dispatchers.Main.immediate + NonCancellable) {
            captureOnMain(
                photo, camera, base, short, hdr, rotation, caller, meteredFrame, stillEvents
            )
        }
    }

    private data class ManualPlan(
        val control: Camera2CameraControl,
        val previous: CaptureRequestOptions,
        val preLock: CaptureRequestOptions,
        val baseline: CaptureRequestOptions,
        val short: CaptureRequestOptions,
        val baselineExposure: Long,
        val shortExposure: Long,
        val iso: Int,
        val restoreOnStarted: Boolean
    )

    private suspend fun captureOnMain(
        photo: ImageCapture,
        camera: Camera,
        base: File,
        short: File,
        hdr: Boolean,
        rotation: Int,
        caller: Job?,
        meteredFrame: PhotonMeteredFrame?,
        stillEvents: PhotonStillCaptureEvents?
    ): CapturedJpegs = supervisorScope {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + CAPTURE_BUDGET_MS
        val stages = linkedMapOf(
            "baselineConfig" to 0L,
            "base" to 0L,
            "shortConfig" to 0L,
            "short" to 0L,
            "restore" to 0L,
            "total" to 0L
        )
        var baseComplete = false
        var shortComplete = false
        var shortConfigComplete = false
        var restoreRequired = false
        var pendingUpdate: ListenableFuture<Void?>? = null
        var pendingPhase = "baselineConfig"
        var earlyRestore: Deferred<Unit>? = null
        var activeWatch: PhotonStillCaptureEvents.Watch? = null
        var baseSensor: PhotonStillCaptureEvents.Completed? = null
        suspend fun awaitPendingUpdate() {
            val update = pendingUpdate ?: return
            pendingUpdate = null
            val phase = SystemClock.elapsedRealtime()
            try {
                update.awaitResult(context.mainExecutor)
            } finally {
                stages[pendingPhase] = requireNotNull(stages[pendingPhase]) +
                    SystemClock.elapsedRealtime() - phase
            }
        }
        var reason = if (hdr) "manual_metering_unavailable" else "disabled"
        var plan = if (hdr) {
            try {
                manualPlan(camera, meteredFrame)
            } catch (failure: Exception) {
                reason = failure.message?.takeIf { it.startsWith("manual_") }
                    ?: "manual_sensor_unavailable"
                null
            }
        } else {
            null
        }
        val savedPlan = plan
        suspend fun restore() {
            if (!restoreRequired || savedPlan == null) return
            val phase = SystemClock.elapsedRealtime()
            stages["restoreStartOffset"] = phase - started
            try {
                savedPlan.control.setCaptureRequestOptions(savedPlan.previous)
                    .awaitResult(context.mainExecutor)
                restoreRequired = false
            } catch (failure: Exception) {
                throw PhotonHdrCaptureException(
                    "Manual override restoration failed; rebind camera before capture",
                    base.takeIf { baseComplete },
                    true,
                    failure
                )
            } finally {
                stages["restore"] = SystemClock.elapsedRealtime() - phase
            }
        }
        fun startEarlyRestore() {
            if (earlyRestore == null) {
                earlyRestore = async {
                    awaitPendingUpdate()
                    shortConfigComplete = true
                    restore()
                }
            }
        }
        try {
            caller?.ensureActive()
            photo.targetRotation = rotation
            // Flash pre-capture can invoke slow 3A sequences and invalidate a two-exposure pair.
            photo.flashMode = ImageCapture.FLASH_MODE_OFF
            plan?.let { manual ->
                val phase = SystemClock.elapsedRealtime()
                restoreRequired = true
                try {
                    // Lock AWB while AE is still automatic. AWB with AE_OFF is device-dependent.
                    manual.control.addCaptureRequestOptions(manual.preLock)
                        .awaitResult(context.mainExecutor)
                    caller?.ensureActive()
                    // CameraX 1.6.2 enqueues this config before takePicture's still request on
                    // the same sequential request control. Its future additionally waits for
                    // a tagged repeating result; overlap that wait with JPEG production.
                    pendingUpdate = manual.control.addCaptureRequestOptions(manual.baseline)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    restore()
                    plan = null
                    reason = "manual_setup_failed"
                } finally {
                    stages["baselineConfig"] = SystemClock.elapsedRealtime() - phase
                }
            }
            caller?.ensureActive()
            val baseStart = SystemClock.elapsedRealtime()
            plan?.let { manual ->
                activeWatch = stillEvents?.watch(
                    manual.baselineExposure,
                    manual.iso,
                    meteredFrame?.result?.frameNumber ?: -1L
                )
            }
            try {
                captureFile(photo, base)
                baseComplete = true
            } finally {
                stages["base"] = SystemClock.elapsedRealtime() - baseStart
                awaitPendingUpdate()
                activeWatch?.let { baseSensor = stillEvents?.finish(it) }
                activeWatch = null
            }
            caller?.ensureActive()
            plan?.let { manual ->
                if (deadline - SystemClock.elapsedRealtime() < MIN_SECOND_REQUEST_MS) {
                    reason = "baseline_exceeded_hdr_budget"
                } else {
                    try {
                        val phase = SystemClock.elapsedRealtime()
                        pendingPhase = "shortConfig"
                        pendingUpdate = manual.control.addCaptureRequestOptions(manual.short)
                        stages["shortConfig"] = SystemClock.elapsedRealtime() - phase
                        try {
                            caller?.ensureActive()
                            if (deadline - SystemClock.elapsedRealtime() < MIN_SECOND_REQUEST_MS) {
                                reason = "manual_config_exceeded_hdr_budget"
                            } else {
                                val shortStart = SystemClock.elapsedRealtime()
                                baseSensor?.let { sensor ->
                                    activeWatch = stillEvents?.watch(
                                        manual.shortExposure,
                                        manual.iso,
                                        sensor.frameNumber,
                                        sensor,
                                        startedListener = if (manual.restoreOnStarted) {
                                            {
                                                stages["shortSensorStartOffset"] =
                                                    SystemClock.elapsedRealtime() - started
                                                // PER_FRAME_CONTROL applies each request's
                                                // controls atomically. This exact still request
                                                // has begun; restore affects subsequent frames.
                                                // Started is not proof that its image succeeded.
                                                startEarlyRestore()
                                            }
                                        } else {
                                            null
                                        }
                                    ) {
                                        // Total metadata proves this exact still frame finished.
                                        // Drain its config update before replacing options, while
                                        // CameraX continues encoding/writing the immutable JPEG.
                                        stages["shortSensorCompleteOffset"] =
                                            SystemClock.elapsedRealtime() - started
                                        startEarlyRestore()
                                    }
                                }
                                try {
                                    captureFile(photo, short)
                                    shortComplete = true
                                    stages["shortFileReadyOffset"] =
                                        SystemClock.elapsedRealtime() - started
                                } finally {
                                    stages["short"] = SystemClock.elapsedRealtime() - shortStart
                                    activeWatch?.let { stillEvents?.finish(it) }
                                    activeWatch = null
                                }
                            }
                        } finally {
                            earlyRestore?.await() ?: awaitPendingUpdate()
                        }
                        shortConfigComplete = true
                        if (shortComplete) {
                            reason = "manual_bracket_captured"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        reason = "short_capture_failed"
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PhotonHdrCaptureException) {
            throw failure
        } catch (failure: Exception) {
            throw PhotonHdrCaptureException(
                "JPEG capture failed",
                base.takeIf { baseComplete },
                false,
                failure
            )
        } finally {
            activeWatch?.let { stillEvents?.finish(it) }
            activeWatch = null
            if (!baseComplete) base.delete()
            if (!shortComplete) short.delete()
            // A cancellation before takePicture can leave an option update outstanding.
            // Drain it before restoration; no pending request may escape to the next capture.
            try {
                earlyRestore?.await() ?: awaitPendingUpdate()
            } finally {
                restore()
            }
        }
        caller?.ensureActive()
        stages["total"] = SystemClock.elapsedRealtime() - started
        CapturedJpegs(
            base,
            short.takeIf { shortComplete && shortConfigComplete },
            requireNotNull(stages["total"]),
            hdr,
            reason,
            stages.toMap()
        )
    }

    private fun manualPlan(camera: Camera, frame: PhotonMeteredFrame?): ManualPlan {
        requireNotNull(frame) { "manual_metering_unavailable" }
        val age = SystemClock.elapsedRealtime() - frame.receivedElapsedMs
        require(age in 0..500) { "manual_metering_stale" }
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val capabilities = info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: intArrayOf()
        require(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        ) {
            "manual_sensor_unavailable"
        }
        val result = frame.result
        require(
            result.get(CaptureResult.CONTROL_AE_STATE) in listOf(
                CaptureResult.CONTROL_AE_STATE_CONVERGED,
                CaptureResult.CONTROL_AE_STATE_LOCKED
            )
        ) { "manual_exposure_unsettled" }
        require(
            result.get(CaptureResult.CONTROL_AWB_STATE) in listOf(
                CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                CaptureResult.CONTROL_AWB_STATE_LOCKED
            )
        ) { "manual_white_balance_unsettled" }
        require(
            info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        ) {
            "manual_white_balance_lock_unavailable"
        }
        val exposureRange = requireNotNull(
            info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
        ) { "manual_exposure_range_unavailable" }
        val isoRange = requireNotNull(
            info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
        ) { "manual_iso_range_unavailable" }
        val exposure = requireNotNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        val iso = requireNotNull(result.get(CaptureResult.SENSOR_SENSITIVITY))
        require(exposureRange.contains(exposure) && isoRange.contains(iso)) {
            "manual_metering_out_of_range"
        }
        val shortTime = (exposure / 4).coerceIn(exposureRange.lower, exposureRange.upper)
        require(exposure.toDouble() / shortTime >= 1.5) { "manual_short_range_unavailable" }
        val duration = requireNotNull(result.get(CaptureResult.SENSOR_FRAME_DURATION))
        val maxDuration = requireNotNull(
            info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION
            )
        )
        require(duration >= exposure && duration <= maxDuration) { "manual_duration_unavailable" }
        val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        require(CaptureRequest.CONTROL_AF_MODE_OFF in modes) { "manual_focus_unavailable" }
        val minimumFocus = requireNotNull(
            info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            )
        ) { "manual_focus_unavailable" }
        val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (minimumFocus > 0f) {
            require(focus != null && focus.isFinite() && focus in 0f..minimumFocus) {
                "manual_focus_distance_unavailable"
            }
            require(result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY) {
                "manual_focus_moving"
            }
            require(
                result.get(CaptureResult.CONTROL_AF_STATE) in listOf(
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                )
            ) { "manual_focus_unsettled" }
        }
        fun common(): CaptureRequestOptions.Builder = CaptureRequestOptions.Builder().apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            if (focus != null) setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
        fun manual(time: Long): CaptureRequestOptions = common().apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, time)
            setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, duration)
            result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.let { boost ->
                val range = requireNotNull(
                    info.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE
                    )
                ) { "manual_post_raw_boost_unavailable" }
                require(range.contains(boost)) { "manual_post_raw_boost_out_of_range" }
                setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
            }
        }.build()
        val control = Camera2CameraControl.from(camera.cameraControl)
        return ManualPlan(
            control,
            control.getCaptureRequestOptions(),
            common().build(),
            manual(exposure),
            manual(shortTime),
            exposure,
            shortTime,
            iso,
            info.getCameraCharacteristic(CameraCharacteristics.SYNC_MAX_LATENCY) ==
                CameraCharacteristics.SYNC_MAX_LATENCY_PER_FRAME_CONTROL
        )
    }

    private suspend fun captureFile(photo: ImageCapture, destination: File) {
        suspendCoroutine { continuation ->
            photo.takePicture(
                ImageCapture.OutputFileOptions.Builder(destination).build(),
                context.mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (destination.isFile && destination.length() > 0) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("Camera reported an empty JPEG")
                            )
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }
    }

    private fun privateDirectory(directory: File): File {
        val canonical = directory.canonicalFile
        val allowed = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
            .map { it.canonicalFile }
            .any { canonical.path.startsWith(it.path + File.separator) }
        require(allowed) { "Capture directory must be inside app private storage" }
        require(canonical.isDirectory || canonical.mkdirs()) { "Cannot create capture directory" }
        return canonical
    }

    private companion object {
        const val CAPTURE_BUDGET_MS = 1_100L
        const val MIN_SECOND_REQUEST_MS = 350L
    }
}
