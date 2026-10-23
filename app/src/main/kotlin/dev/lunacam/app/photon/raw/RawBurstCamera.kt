package dev.lunacam.app.photon.raw

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One camera/session lifetime. Call close on stop/dispose and create a new instance to reopen.
 * Camera state and RAW image/result pairing are confined to a dedicated handler thread.
 * Opening starts only preview: capture is the only operation that requests sensor images.
 */
class RawBurstCamera(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("photon-raw-camera").apply { start() }
    private val handler = Handler(thread.looper)

    // Late device/session callbacks must still close their resource after our thread exits.
    private val executor = Executor { runnable -> if (!handler.post(runnable)) runnable.run() }
    private val closed = AtomicBoolean(false)
    private var opening: CancellableContinuation<Unit>? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null
    private var latest: TotalCaptureResult? = null
    private var pending: PendingBurst? = null
    private var preparing: CancellableContinuation<TotalCaptureResult>? = null
    private var prepareTimeout: Runnable? = null
    private var prepareTag: Any? = null
    private var previewSize: Size? = null
    private var physicalId: String? = null
    private var opticalRatio: Float? = null

    @Volatile private var digitalZoom = 1f
    private var physicalRequestKeys: List<CaptureRequest.Key<*>> = emptyList()
    private var latestReceivedMs = 0L
    private var latestPreviewRequest: CaptureRequest? = null

    // Null leaves the independently validated Night path entirely on its legacy policy.
    private var userControls: RawControls? = null
    private var controlsEpoch: Any? = null
    private var tapRegion: MeteringRectangle? = null
    private var tapAfActive = false
    private var nightPreviewEpoch: Any? = null
    private var nightHold: NightPreviewHold? = null
    private var nightHoldExpiry: Runnable? = null
    private var nightRestore: NightRestore? = null

    @Volatile
    var lastNightTiming: NightCaptureTiming? = null
        private set

    fun lenses(): List<RawLens> = manager.cameraIdList.mapNotNull { id ->
        val info = manager.getCameraCharacteristics(id)
        val capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return@mapNotNull null
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in capabilities) {
            return@mapNotNull null
        }
        val raw = rawSize(info) ?: return@mapNotNull null
        val facing = when (info.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "후면"
            CameraCharacteristics.LENS_FACING_FRONT -> "전면"
            else -> "외부"
        }
        RawLens(id, "$facing · $id", raw)
    }

    /** Candidates require a successful physical preview session before becoming capture-ready. */
    fun opticalLenses(): List<RawLens> {
        for (id in manager.cameraIdList) {
            val logical = manager.getCameraCharacteristics(id)
            if (logical.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }
            val baseFocal = logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: continue
            val baseWidth = logical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.width ?: continue
            val candidates = logical.physicalCameraIds.mapNotNull { physical ->
                runCatching {
                    val info = manager.getCameraCharacteristics(physical)
                    val capabilities = info.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                    )
                        ?: return@runCatching null
                    if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in capabilities) {
                        return@runCatching null
                    }
                    val size = rawSize(info) ?: return@runCatching null
                    val focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull() ?: return@runCatching null
                    val width = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        ?.width ?: return@runCatching null
                    val ratio = RawOpticalZoom.ratio(focal, width, baseFocal, baseWidth)
                        ?: return@runCatching null
                    RawLens(
                        "$id@$physical",
                        String.format(Locale.ROOT, "약 %.1f×", ratio),
                        size,
                        id,
                        physical,
                        ratio
                    )
                }.getOrNull()
            }.sortedBy { it.opticalRatio }
            if (candidates.size >= 2) return candidates
        }
        return emptyList()
    }

    /** Initial Night scope is the calibrated 1x physical source, not a digital crop. */
    fun nightLens(): RawLens? = opticalLenses().firstOrNull {
        it.rawSize == Size(4080, 3060) && abs((it.opticalRatio ?: 0f) - 1f) < 0.01f
    }

    suspend fun nightReadiness(): NightReadiness = suspendCancellableCoroutine { continuation ->
        if (!handler.post {
                if (continuation.isActive) continuation.resume(readNightReadiness())
            }
        ) {
            continuation.resume(NightReadiness(false, "camera_closed"))
        }
    }

    private fun readNightReadiness(): NightReadiness {
        val reference = latest
        val info = characteristics
        val now = SystemClock.elapsedRealtime()
        val age = (now - latestReceivedMs).takeIf { reference != null }
        val hold = nightHold
        val held = hold != null && reference != null && age != null &&
            NightPreviewHoldPolicy.usable(
                hold.startedMs,
                now,
                age,
                hold.requestTag != null && latestPreviewRequest?.tag === hold.requestTag,
                (reference.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0) > hold.referenceTimestamp
            ) && reference.get(CaptureResult.CONTROL_AF_MODE) ==
            CaptureResult.CONTROL_AF_MODE_OFF &&
            reference.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY &&
            reference.get(CaptureResult.CONTROL_AWB_STATE) ==
            CaptureResult.CONTROL_AWB_STATE_LOCKED &&
            nightCalibration(reference)?.let {
                NightPreviewHoldPolicy.matches(hold.calibration, it)
            } == true
        val exposure = reference?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = reference?.get(CaptureResult.SENSOR_SENSITIVITY)
        val manual = info?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val focus = reference?.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val focusReady = held || reference?.get(CaptureResult.CONTROL_AF_STATE) in listOf(
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        ) && reference?.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY
        val focusFreeze = info?.availableCaptureRequestKeys
            ?.contains(CaptureRequest.LENS_FOCUS_DISTANCE) == true &&
            info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true
        val reason = when {
            closed.get() || session == null -> "camera_closed"
            pending != null || preparing != null || nightRestore != null -> "capture_busy"
            physicalId == null || opticalRatio?.let { abs(it - 1f) < 0.01f } != true ||
                reader?.width != 4080 || reader?.height != 3060 -> "unsupported_night_source"
            reference == null || age == null ||
                age !in 0..NightAcquisitionPolicy.MAX_PREVIEW_AGE_MS ->
                "preview_stale"
            nightPreviewEpoch != null && latestPreviewRequest?.tag !== nightPreviewEpoch ->
                "preview_restoring"
            !manual || exposure == null || exposure <= 0 || iso == null || iso <= 0 ->
                "manual_metadata_missing"
            reference.get(CaptureResult.CONTROL_AE_MODE) != CaptureResult.CONTROL_AE_MODE_ON ->
                "preview_restoring"
            reference.get(CaptureResult.CONTROL_AE_STATE) !in listOf(
                CaptureResult.CONTROL_AE_STATE_CONVERGED,
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            ) -> "exposure_settling"
            !held && reference.get(CaptureResult.CONTROL_AWB_STATE) !=
                CaptureResult.CONTROL_AWB_STATE_CONVERGED ->
                "white_balance_settling"
            !focusFreeze || focus == null || !focus.isFinite() || focus < 0f ->
                "focus_metadata_missing"
            !focusReady -> "focus_settling"
            else -> "ready"
        }
        return NightReadiness(
            reason == "ready", reason, age, exposure, iso,
            reference?.get(CaptureResult.COLOR_CORRECTION_GAINS) != null,
            reference?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) != null,
            physicalId, reader?.width ?: 0, reader?.height ?: 0, held
        )
    }

    /** Tap explicitly releases the short continuity window and restarts continuous metering. */
    fun refocusNight() {
        handler.post {
            if (closed.get() || pending != null || nightRestore != null) return@post
            clearNightHold()
            latest = null
            val tag = Any()
            nightPreviewEpoch = tag
            restorePreview(tag, resetFocus = true)
        }
    }

    private fun nightCalibration(result: TotalCaptureResult): NightPreviewHoldPolicy.Calibration? {
        val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return null
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
        return NightPreviewHoldPolicy.Calibration(
            focus,
            floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
            FloatArray(9) { transform.getElement(it % 3, it / 3).toFloat() }
        )
    }

    private fun startNightHold(reference: TotalCaptureResult) {
        if (nightHold != null ||
            characteristics?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) != true
        ) {
            return
        }
        val calibration = nightCalibration(reference) ?: return
        if (!NightPreviewHoldPolicy.matches(calibration, calibration)) return
        val timestamp = reference.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val hold = NightPreviewHold(SystemClock.elapsedRealtime(), timestamp, calibration)
        nightHold = hold
        nightHoldExpiry = Runnable {
            if (nightHold !== hold) return@Runnable
            clearNightHold()
            if (pending == null) {
                latest = null
                val tag = nightRestore?.tag ?: Any()
                nightPreviewEpoch = tag
                restorePreview(tag)
            }
        }.also { handler.postDelayed(it, NightPreviewHoldPolicy.DURATION_MS) }
    }

    private fun clearNightHold() {
        nightHold = null
        nightHoldExpiry?.let(handler::removeCallbacks)
        nightHoldExpiry = null
    }

    /** Night only: no shutter-time AF scan, then wait for an actual restored preview frame. */
    suspend fun captureNight(frameCount: Int, displayRotation: Int): RawBurst {
        require(frameCount == 1 || frameCount == 3)
        val started = SystemClock.elapsedRealtime()
        val completedFrames = AtomicReference<RawBurst?>()
        lastNightTiming = NightCaptureTiming(started, frameCount)
        try {
            return withTimeout(5_000) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { close() }
                    if (!handler.post {
                            if (!continuation.isActive) return@post
                            try {
                                val state = readNightReadiness()
                                check(state.ready) { "Night not ready: ${state.reason}" }
                                val reference = requireNotNull(latest)
                                val info = requireNotNull(characteristics)
                                val camera = requireNotNull(device)
                                val activeSession = requireNotNull(session)
                                val output = requireNotNull(reader)
                                val exposureRange = requireNotNull(
                                    info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                                )
                                val isoRange = requireNotNull(
                                    info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                                )
                                val plan = NightAcquisitionPolicy.exposure(
                                    requireNotNull(state.exposureNs),
                                    requireNotNull(state.iso),
                                    exposureRange.lower,
                                    exposureRange.upper,
                                    isoRange.lower,
                                    isoRange.upper
                                )
                                val map = requireNotNull(
                                    info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                )
                                val frameNs = maxOf(
                                    plan.exposureNs,
                                    map.getOutputMinFrameDuration(
                                        ImageFormat.RAW_SENSOR,
                                        Size(output.width, output.height)
                                    ),
                                    map.getOutputMinFrameDuration(
                                        SurfaceTexture::class.java,
                                        requireNotNull(previewSize)
                                    )
                                )
                                val maxFrameNs = requireNotNull(
                                    info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                                )
                                check(frameNs <= maxFrameNs) { "Night frame duration unsupported" }
                                val count = NightAcquisitionPolicy.frameCount(
                                    frameCount,
                                    requireNotNull(state.previewAgeMs),
                                    frameNs,
                                    SystemClock.elapsedRealtime() - started
                                )
                                startNightHold(reference)
                                val timing = NightCaptureTiming(
                                    started, frameCount,
                                    previewAgeMs = state.previewAgeMs,
                                    referenceExposureNs = state.exposureNs,
                                    requestedExposureNs = plan.exposureNs,
                                    referenceIso = state.iso,
                                    requestedIso = plan.iso,
                                    requestedFrameDurationNs = frameNs,
                                    exposureLimited = plan.limited,
                                    focusWhiteBalanceHoldStartedMs = nightHold?.startedMs,
                                    fallbackReason = if (count != frameCount) {
                                        "submission_budget"
                                    } else {
                                        null
                                    }
                                )
                                lastNightTiming = timing
                                val burst = PendingBurst(
                                    count, info, output.width, output.height,
                                    orientation(info, displayRotation), started, continuation,
                                    requireNotNull(reference.get(CaptureResult.SENSOR_TIMESTAMP)),
                                    nightTiming = timing,
                                    completedNightFrames = completedFrames
                                )
                                val requests = (0 until count).map { index ->
                                    newRequest(camera, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(output.surface)
                                        addTarget(requireNotNull(previewSurface))
                                        setTag(BurstTag(burst, index))
                                        configureLocked(this, reference, true)
                                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
                                        set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
                                        set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                                        applyPhysicalControls(this)
                                    }.build()
                                }
                                pending = burst
                                latest = null
                                activeSession.stopRepeating()
                                val submittedAt = SystemClock.elapsedRealtime()
                                burst.sequenceId = activeSession.captureBurst(
                                    requests,
                                    burstCallback,
                                    handler
                                )
                                burst.nightTiming = timing.copy(
                                    submittedFrames = count,
                                    submitElapsedMs = submittedAt
                                )
                                lastNightTiming = burst.nightTiming
                            } catch (error: Exception) {
                                if (pending != null) {
                                    failBurst(error)
                                } else {
                                    lastNightTiming = lastNightTiming?.copy(failure = error.message)
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(error)
                                    }
                                }
                            }
                        }
                    ) {
                        continuation.resumeWithException(IllegalStateException("RAW camera closed"))
                    }
                }
            }
        } catch (error: Exception) {
            lastNightTiming = lastNightTiming?.copy(
                failure = error.message ?: error.javaClass.simpleName
            )
            if (lastNightTiming?.submittedFrames != 0) close()
            completedFrames.get()?.let { acquired ->
                // An internal restore timeout must not discard a fully received photograph.
                // External lifecycle cancellation still cancels this operation normally.
                coroutineContext.ensureActive()
                close()
                return acquired.copy(
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    nightTiming = requireNotNull(acquired.nightTiming).copy(
                        failure = error.message ?: error.javaClass.simpleName
                    )
                )
            }
            throw error
        }
    }

    suspend fun open(cameraId: String, textureView: TextureView) {
        open(lenses().first { it.id == cameraId }, textureView)
    }

    suspend fun open(lens: RawLens, textureView: TextureView) {
        check(!closed.get()) { "RAW controller is closed; create a new instance" }
        check(device == null && opening == null) { "RAW controller already opened" }
        try {
            withTimeout(12_000) {
                val logical = manager.getCameraCharacteristics(lens.cameraId)
                val info = manager.getCameraCharacteristics(lens.physicalId ?: lens.cameraId)
                val raw = requireNotNull(rawSize(info)) { "No supported RAW size" }
                val sizes = requireNotNull(
                    info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ).getOutputSizes(SurfaceTexture::class.java)
                val preview = sizes.filter { it.width <= 1920 && it.height <= 1080 }
                    .minByOrNull {
                        abs(it.width.toFloat() / it.height - raw.width.toFloat() / raw.height)
                    } ?: error("No bounded preview size")
                val texture = withContext(Dispatchers.Main.immediate) {
                    awaitSurface(textureView).also { surfaceTexture ->
                        surfaceTexture.setDefaultBufferSize(preview.width, preview.height)
                        applyTransform(textureView, preview, info)
                    }
                }
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { close() }
                    handler.post {
                        if (closed.get() || !continuation.isActive) return@post
                        opening = continuation
                        characteristics = info
                        physicalId = lens.physicalId
                        opticalRatio = lens.opticalRatio
                        physicalRequestKeys = logical.availablePhysicalCameraRequestKeys.orEmpty()
                        previewSize = preview
                        try {
                            previewSurface = Surface(texture)
                            reader = ImageReader.newInstance(
                                raw.width,
                                raw.height,
                                ImageFormat.RAW_SENSOR,
                                2
                            ).also { it.setOnImageAvailableListener(::onImage, handler) }
                            openDevice(lens.cameraId)
                        } catch (error: Exception) {
                            failOpen(error)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** Apply again after display rotation or TextureView bounds change. Main thread only. */
    fun updateTransform(view: TextureView) {
        val info = characteristics ?: return
        val size = previewSize ?: return
        applyTransform(view, size, info, digitalZoom)
    }

    /** Crop only the view; RAW pixels and all physical calibration stay in full sensor space. */
    fun setDigitalZoom(value: Float, view: TextureView) {
        require(value.isFinite() && value in 1f..16f)
        digitalZoom = value
        updateTransform(view)
    }

    suspend fun controlCapabilities(): RawControlCapabilities = onCameraThread {
        rawControlSupport(requireNotNull(characteristics), physicalId != null, physicalRequestKeys)
    }

    suspend fun controlObservation(): RawControlObservation = onCameraThread { observation(latest) }

    suspend fun setControls(requested: RawControls): RawControlObservation {
        val epoch = onCameraThread {
            check(pending == null && preparing == null) { "RAW capture busy" }
            val capabilities =
                rawControlSupport(
                    requireNotNull(characteristics),
                    physicalId != null,
                    physicalRequestKeys
                )
            userControls = requested.bounded(capabilities)
            tapAfActive = false
            tapRegion = null
            val next = Any()
            controlsEpoch = next
            restorePreview(resetFocus = true)
            next
        }
        return awaitControls(epoch, waitForFocus = false)
    }

    suspend fun tapFocus(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        rotation: Int
    ): RawControlObservation {
        val epoch = onCameraThread {
            check(pending == null && preparing == null) { "RAW capture busy" }
            val info = requireNotNull(characteristics)
            val capability = rawControlSupport(info, physicalId != null, physicalRequestKeys)
            check(capability.tapAf || capability.tapAe) { "RAW tap metering unsupported" }
            val size = requireNotNull(previewSize)
            val point = requireNotNull(
                rawMeteringPoint(
                    x, y, width, height, size.width, size.height,
                    info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, rotation * 90,
                    info.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                )
            )
            val active = requireNotNull(
                info.get(
                    if (latest?.get(CaptureResult.DISTORTION_CORRECTION_MODE) ==
                        CaptureResult.DISTORTION_CORRECTION_MODE_OFF
                    ) {
                        CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
                    } else {
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
                    }
                )
            )
            val crop = Rect(latest?.get(CaptureResult.SCALER_CROP_REGION) ?: active)
            check(crop.intersect(active))
            // A preview stream can have a narrower aspect than the sensor's current crop.
            val ratio = size.width.toFloat() / size.height
            val visibleWidth = minOf(crop.width(), (crop.height() * ratio).toInt())
            val visibleHeight = minOf(crop.height(), (crop.width() / ratio).toInt())
            val left = crop.left + (crop.width() - visibleWidth) / 2
            val top = crop.top + (crop.height() - visibleHeight) / 2
            val side = (minOf(visibleWidth, visibleHeight) / 10).coerceAtLeast(1)
            val cx = left + (((point.first - 0.5f) / digitalZoom + 0.5f) * visibleWidth).toInt()
            val cy = top + (((point.second - 0.5f) / digitalZoom + 0.5f) * visibleHeight).toInt()
            tapRegion = MeteringRectangle(
                (cx - side / 2).coerceIn(crop.left, crop.right - side),
                (cy - side / 2).coerceIn(crop.top, crop.bottom - side),
                side,
                side,
                MeteringRectangle.METERING_WEIGHT_MAX
            )
            val currentControls = userControls ?: RawControls()
            userControls = currentControls.copy(
                focusDiopters = if (capability.tapAf) null else currentControls.focusDiopters
            ).bounded(capability)
            tapAfActive = capability.tapAf
            val next = Any()
            controlsEpoch = next
            restorePreview(resetFocus = true, triggerTap = capability.tapAf)
            next
        }
        return awaitControls(epoch, waitForFocus = true)
    }

    private suspend fun awaitControls(epoch: Any, waitForFocus: Boolean): RawControlObservation {
        return withTimeout(4_000) {
            while (true) {
                val result = onCameraThread {
                    check(controlsEpoch === epoch) { "RAW controls superseded" }
                    val request = latestPreviewRequest
                    if (request?.tag === epoch &&
                        request.get(CaptureRequest.CONTROL_AF_TRIGGER) !=
                        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                    ) {
                        val observed = observation(latest)
                        val support = rawControlSupport(
                            requireNotNull(characteristics),
                            physicalId != null,
                            physicalRequestKeys
                        )
                        observed.takeIf { it.matches(requireNotNull(userControls), support) }
                    } else {
                        null
                    }
                }
                if (result != null &&
                    (
                        !waitForFocus || result.afMode != CaptureResult.CONTROL_AF_MODE_AUTO ||
                            result.afState in
                            listOf(
                                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                            )
                        )
                ) {
                    return@withTimeout result
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            error("RAW controls unavailable")
        }
    }

    private fun observation(result: TotalCaptureResult?) = RawControlObservation(
        result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        result?.get(CaptureResult.SENSOR_SENSITIVITY),
        result?.get(CaptureResult.LENS_FOCUS_DISTANCE),
        result?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
        result?.get(CaptureResult.CONTROL_AE_MODE),
        result?.get(CaptureResult.CONTROL_AWB_MODE),
        result?.get(CaptureResult.CONTROL_AF_MODE),
        result?.get(CaptureResult.CONTROL_AF_STATE)
    )

    private suspend fun <T> onCameraThread(action: () -> T): T = withTimeout(3_000) {
        suspendCancellableCoroutine { continuation ->
            if (!handler.post {
                    if (!continuation.isActive) return@post
                    try {
                        check(!closed.get()) { "RAW camera closed" }
                        continuation.resume(action())
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            ) {
                continuation.resumeWithException(IllegalStateException("RAW camera closed"))
            }
        }
    }

    suspend fun capture(frameCount: Int, displayRotation: Int, hdr: Boolean = false): RawBurst {
        require(frameCount == 1 || frameCount == 3)
        require(!hdr || frameCount == 3)
        check(!closed.get()) { "RAW camera is closed" }
        val started = SystemClock.elapsedRealtime()
        // Outlives acquisition's cancellable continuation: a missing later bracket frame must
        // not discard an already timestamp-matched metered baseline.
        val completedBaseline = AtomicReference<RawBurst?>()
        val captured = try {
            withTimeout(15_000) {
                try {
                    val reference = prepareCapture()
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation {
                            handler.post { abortBurst("RAW capture cancelled") }
                        }
                        handler.post {
                            if (!continuation.isActive) return@post
                            try {
                                check(!closed.get() && pending == null)
                                val info = requireNotNull(characteristics)
                                val camera = requireNotNull(device)
                                val activeSession = requireNotNull(session)
                                val output = requireNotNull(reader)
                                val requiredKeys = listOf(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                                    CaptureRequest.SENSOR_SENSITIVITY,
                                    CaptureRequest.SENSOR_FRAME_DURATION
                                )
                                val manualSupported = rawControlSupport(
                                    info,
                                    physicalId != null,
                                    physicalRequestKeys
                                ).manualExposure &&
                                    (
                                        physicalId == null ||
                                            requiredKeys.all { it in physicalRequestKeys }
                                        )
                                val range = info.get(
                                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
                                )
                                val bracket = if (hdr && manualSupported && range != null) {
                                    rawHdrBracket(
                                        reference.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                                        range.lower,
                                        minOf(
                                            range.upper,
                                            maximumFrameDuration(info)
                                        )
                                    )
                                } else {
                                    null
                                }
                                val count = if (hdr && bracket == null) 1 else frameCount
                                val burst = PendingBurst(
                                    count,
                                    info,
                                    output.width,
                                    output.height,
                                    orientation(info, displayRotation),
                                    started,
                                    continuation,
                                    reference.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                                    digitalZoom = digitalZoom,
                                    hdrRequested = hdr,
                                    completedHdrBaseline = if (hdr) completedBaseline else null,
                                    hdrFallbackReason = if (hdr && bracket == null) {
                                        "bracket_unavailable"
                                    } else {
                                        null
                                    }
                                )
                                pending = burst
                                val requests = (0 until count).map { index ->
                                    newRequest(camera, CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        .apply {
                                            addTarget(output.surface)
                                            addTarget(requireNotNull(previewSurface))
                                            setTag(BurstTag(burst, index))
                                            configureLocked(this, reference, true)
                                            bracket?.let {
                                                configureHdrExposure(
                                                    this,
                                                    reference,
                                                    it[index],
                                                    info
                                                )
                                            }
                                            applyPhysicalControls(this)
                                        }.build()
                                }
                                activeSession.stopRepeating()
                                burst.sequenceId = activeSession.captureBurst(
                                    requests,
                                    burstCallback,
                                    handler
                                )
                            } catch (error: Exception) {
                                if (pending != null) {
                                    failBurst(error)
                                } else if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }
                    }
                } finally {
                    handler.post { restorePreview() }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            // An enclosing lifecycle/parent cancellation remains cancellation, not a save success.
            coroutineContext.ensureActive()
            completedBaseline.get()?.copy(
                elapsedMs = SystemClock.elapsedRealtime() - started,
                hdrFallbackReason = "bracket_acquisition_timeout"
            ) ?: throw error
        }
        // A complete RAW must survive restoration failure. Keep shutter disabled until reconnect,
        // but pass acquired frames to storage instead of discarding them with a timeout exception.
        val restored = try {
            val epoch = requireNotNull(onCameraThread { controlsEpoch })
            awaitControls(epoch, waitForFocus = false)
            true
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            false
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return captured.copy(previewRestored = restored)
    }

    private fun configureHdrExposure(
        builder: CaptureRequest.Builder,
        reference: TotalCaptureResult,
        exposureNs: Long,
        info: CameraCharacteristics
    ) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        builder.set(
            CaptureRequest.SENSOR_SENSITIVITY,
            requireNotNull(reference.get(CaptureResult.SENSOR_SENSITIVITY))
        )
        val maximum = requireNotNull(info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION))
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            max(33_333_333L, exposureNs).coerceAtMost(maximum)
        )
    }

    private fun maximumFrameDuration(info: CameraCharacteristics): Long =
        info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0

    @SuppressLint("MissingPermission")
    private fun openDevice(id: String) {
        manager.openCamera(
            id,
            executor,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed.get() || opening?.isActive != true) {
                        camera.close()
                        return
                    }
                    device = camera
                    try {
                        camera.createCaptureSession(
                            SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(
                                    outputConfiguration(requireNotNull(previewSurface)),
                                    outputConfiguration(requireNotNull(reader).surface)
                                ),
                                executor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        if (closed.get() || opening?.isActive != true) {
                                            configured.close()
                                            return
                                        }
                                        session = configured
                                        try {
                                            restorePreview()
                                            // Resolve open on the first real preview result.
                                        } catch (error: Exception) {
                                            failOpen(error)
                                        }
                                    }

                                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                                        failed.close()
                                        failOpen(IllegalStateException("RAW session failed"))
                                    }
                                }
                            )
                        )
                    } catch (error: Exception) {
                        failOpen(error)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    failOpen(IllegalStateException("RAW camera disconnected"))
                    close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    failOpen(IllegalStateException("RAW camera error $error"))
                    close()
                }
            }
        )
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val source = sourceResult(result) ?: run {
                failOpen(IllegalStateException("Physical preview metadata missing"))
                return
            }
            latest = source
            latestPreviewRequest = request
            latestReceivedMs = SystemClock.elapsedRealtime()
            val restoring = nightRestore
            if (restoring != null && request.tag === restoring.tag &&
                (source.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L) > restoring.afterTimestamp &&
                source.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_ON
            ) {
                nightRestore = null
                val timing = restoring.timing.copy(previewRestoredElapsedMs = latestReceivedMs)
                lastNightTiming = timing
                if (restoring.continuation.isActive) {
                    val error = restoring.error
                    if (error != null) {
                        restoring.continuation.resumeWithException(error)
                    } else {
                        restoring.continuation.resume(
                            requireNotNull(restoring.burst).copy(
                                elapsedMs = latestReceivedMs - timing.startedElapsedMs,
                                nightTiming = timing
                            )
                        )
                    }
                }
            }
            opening?.let {
                opening = null
                if (it.isActive) it.resume(Unit)
            }
            val continuation = preparing ?: return
            if (request.tag !== prepareTag) return
            val autofocus = request.get(CaptureRequest.CONTROL_AF_MODE)
            val focusState = source.get(CaptureResult.CONTROL_AF_STATE)
            if (autofocus == CaptureRequest.CONTROL_AF_MODE_AUTO &&
                focusState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                // A stopped lens is not necessarily in focus. Do not freeze failed AF for a burst.
                preparing = null
                prepareTimeout?.let(handler::removeCallbacks)
                prepareTimeout = null
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("RAW focus lock failed"))
                }
                return
            }
            val locked = focusState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            val lensMoving = source.get(CaptureResult.LENS_STATE) ==
                CaptureResult.LENS_STATE_MOVING
            if ((autofocus == CaptureRequest.CONTROL_AF_MODE_OFF || locked) && !lensMoving) {
                preparing = null
                prepareTimeout?.let(handler::removeCallbacks)
                prepareTimeout = null
                if (continuation.isActive) continuation.resume(source)
            }
        }
    }

    internal suspend fun prepareCapture(): TotalCaptureResult {
        val reference = awaitMeteredPreview()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                handler.post {
                    if (preparing === continuation) {
                        preparing = null
                        prepareTimeout?.let(handler::removeCallbacks)
                        prepareTimeout = null
                        restorePreview()
                    }
                }
            }
            handler.post {
                if (!continuation.isActive) return@post
                try {
                    check(!closed.get() && preparing == null && pending == null)
                    val camera = requireNotNull(device)
                    val activeSession = requireNotNull(session)
                    val tag = Any()
                    prepareTag = tag
                    val builder = newRequest(camera, CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(requireNotNull(previewSurface))
                        setTag(tag)
                        configureLocked(this, reference, false)
                    }
                    preparing = continuation
                    val focusMode = builder.get(CaptureRequest.CONTROL_AF_MODE)
                    if (focusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                        builder.set(
                            CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_START
                        )
                        applyPhysicalControls(builder)
                        activeSession.capture(builder.build(), previewCallback, handler)
                        builder.set(
                            CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                        )
                    }
                    applyPhysicalControls(builder)
                    activeSession.setRepeatingRequest(builder.build(), previewCallback, handler)
                    prepareTimeout = Runnable {
                        if (preparing === continuation) {
                            preparing = null
                            prepareTimeout = null
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("RAW focus lock timed out")
                                )
                            }
                            restorePreview()
                        }
                    }.also { handler.postDelayed(it, 2_000) }
                } catch (error: Exception) {
                    preparing = null
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    private suspend fun awaitMeteredPreview(): TotalCaptureResult {
        // Consecutive shots may arrive before restored auto-preview has produced a new result.
        // Wait briefly for that result instead of rejecting the next shutter immediately.
        repeat(if (userControls?.manualExposure == true) 120 else 60) {
            val reference = suspendCancellableCoroutine<TotalCaptureResult?> { continuation ->
                if (!handler.post {
                        if (!continuation.isActive) return@post
                        if (closed.get()) {
                            continuation.resumeWithException(
                                IllegalStateException("RAW camera closed")
                            )
                        } else {
                            val current =
                                userControls == null || latestPreviewRequest?.tag === controlsEpoch
                            continuation.resume(if (current) latest else null)
                        }
                    }
                ) {
                    continuation.resumeWithException(IllegalStateException("RAW camera closed"))
                }
            }
            val ae = reference?.get(CaptureResult.CONTROL_AE_STATE)
            val awb = reference?.get(CaptureResult.CONTROL_AWB_STATE)
            val controls = onCameraThread { userControls }
            val exposureReady = if (controls?.manualExposure == true) {
                reference?.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF
            } else {
                ae in listOf(
                    CaptureResult.CONTROL_AE_STATE_CONVERGED,
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                )
            }
            val wbReady = if (controls != null && controls.whiteBalance != RawWhiteBalance.AUTO) {
                reference?.get(CaptureResult.CONTROL_AWB_MODE) == controls.whiteBalance.mode
            } else {
                awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED
            }
            if (reference != null && exposureReady && wbReady) {
                return reference
            }
            delay(25)
        }
        error("RAW metering not settled")
    }

    private fun configureLocked(
        builder: CaptureRequest.Builder,
        reference: TotalCaptureResult,
        rawRequest: Boolean
    ) {
        val info = requireNotNull(characteristics)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in
            (info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
        val exposure = reference.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = reference.get(CaptureResult.SENSOR_SENSITIVITY)
        if (manual && exposure != null && iso != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            reference.get(CaptureResult.SENSOR_FRAME_DURATION)?.let {
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, max(it, exposure))
            }
        } else {
            check(info.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                "RAW capture needs manual exposure or AE lock"
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (info.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        applyUserControls(builder, locked = true)
        val focusModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val focusDistance = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val manualFocus = info.availableCaptureRequestKeys
            .contains(CaptureRequest.LENS_FOCUS_DISTANCE)
        val focused = reference.get(CaptureResult.CONTROL_AF_STATE) in listOf(
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        ) && reference.get(CaptureResult.LENS_STATE) != CaptureResult.LENS_STATE_MOVING
        val canFreeze = CaptureRequest.CONTROL_AF_MODE_OFF in focusModes &&
            manualFocus && focusDistance != null
        if (userControls?.focusDiopters != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, userControls!!.focusDiopters!!)
        } else if (!rawRequest && !(focused && canFreeze) &&
            CaptureRequest.CONTROL_AF_MODE_AUTO in focusModes
        ) {
            // Acquire uncertain focus; retain already-focused previews without another AF scan.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        } else if (canFreeze) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, requireNotNull(focusDistance))
        } else if (CaptureRequest.CONTROL_AF_MODE_AUTO in focusModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        if (!rawRequest) return
        if (info.availableCaptureRequestKeys.contains(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE
            )
        ) {
            // This is the sensor lens-shading calibration map used by DNG, not an HDR gain map.
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
            )
        }
        val noiseModes = info.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        )
        if (CaptureRequest.NOISE_REDUCTION_MODE_OFF in (noiseModes ?: intArrayOf())) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        }
        val edgeModes = info.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        if (CaptureRequest.EDGE_MODE_OFF in edgeModes) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        // The actual result is kept with each frame; requested OFF is not an ISP-bypass claim.
    }

    private fun applyUserControls(builder: CaptureRequest.Builder, locked: Boolean) {
        val controls = userControls ?: return
        val info = requireNotNull(characteristics)
        val capability = rawControlSupport(info, physicalId != null, physicalRequestKeys)
        if (controls.manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, controls.exposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, controls.iso)
            val duration = max(33_333_333L, controls.exposureNs)
                .coerceAtMost(
                    requireNotNull(info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION))
                )
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, duration)
        } else if (!locked) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        if (capability.ev) {
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                controls.evIndex
            )
        }
        if (controls.whiteBalance in capability.whiteBalances &&
            capability.whiteBalances.size > 1
        ) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, controls.whiteBalance.mode)
        }
        if (info.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true &&
            (!locked || controls.whiteBalance != RawWhiteBalance.AUTO)
        ) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        if (controls.focusDiopters != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, controls.focusDiopters)
        } else if (!locked && tapAfActive) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
        tapRegion?.let { region ->
            if (capability.tapAf &&
                controls.focusDiopters == null
            ) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            if (capability.tapAe &&
                !controls.manualExposure
            ) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }
    }

    private fun restorePreview(
        tag: Any? = null,
        hold: NightPreviewHold? = null,
        resetFocus: Boolean = false,
        triggerTap: Boolean = false
    ) {
        if (closed.get()) return
        val camera = device ?: return
        val activeSession = session ?: return
        val surface = previewSurface ?: return
        try {
            val builder = newRequest(camera, CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                if (tag != null || controlsEpoch != null) setTag(tag ?: controlsEpoch)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?: intArrayOf()
                val focusMode = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                } else {
                    CaptureRequest.CONTROL_AF_MODE_OFF
                }
                set(CaptureRequest.CONTROL_AF_MODE, focusMode)
                applyUserControls(this, locked = false)
                if (hold != null) {
                    val appliedTag = tag ?: Any()
                    hold.requestTag = appliedTag
                    setTag(appliedTag)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, hold.calibration.focus)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }
            if (resetFocus) {
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                )
                applyPhysicalControls(builder)
                activeSession.capture(builder.build(), previewCallback, handler)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                )
            }
            if (triggerTap) {
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                )
                applyPhysicalControls(builder)
                activeSession.capture(builder.build(), previewCallback, handler)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                )
            }
            applyPhysicalControls(builder)
            activeSession.setRepeatingRequest(builder.build(), previewCallback, handler)
        } catch (error: Exception) {
            failOpen(error)
        }
    }

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val burst = pending ?: return
            val tag = request.tag as? BurstTag ?: return
            if (tag.burst !== burst) return
            val source = sourceResult(result)
            if (source == null) {
                failBurst(IllegalStateException("Physical RAW metadata missing"))
                return
            }
            val timestamp = source.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp == null || burst.results.size >= burst.count) {
                failBurst(IllegalStateException("RAW result timestamp missing or overflow"))
                return
            }
            burst.results[timestamp] = tag.index to source
            completeIfReady()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if ((request.tag as? BurstTag)?.burst !== pending) return
            failBurst(IllegalStateException("RAW capture failed: ${failure.reason}"))
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (pending?.sequenceId != sequenceId) return
            failBurst(IllegalStateException("RAW sequence aborted"))
        }
    }

    private fun onImage(source: ImageReader) {
        try {
            while (true) {
                val image = source.acquireNextImage() ?: break
                image.use {
                    val burst = pending ?: return@use
                    if (image.timestamp <= burst.referenceTimestamp) return@use
                    check(image.format == ImageFormat.RAW_SENSOR)
                    check(image.width == burst.width && image.height == burst.height)
                    check(burst.images.size < burst.count) { "RAW image queue overflow" }
                    check(!burst.images.containsKey(image.timestamp)) { "Duplicate RAW timestamp" }
                    burst.images[image.timestamp] = pack(image)
                }
                completeIfReady()
            }
        } catch (error: Exception) {
            failBurst(error)
        } catch (error: OutOfMemoryError) {
            failBurst(IllegalStateException("Insufficient memory for RAW burst", error))
        }
    }

    private fun completeIfReady() {
        val burst = pending ?: return
        preserveHdrBaseline(burst)
        if (burst.results.size != burst.count || burst.images.size != burst.count) return
        if (burst.results.keys != burst.images.keys) {
            failBurst(IllegalStateException("RAW image and result timestamps do not match"))
            return
        }
        val frames = burst.results.entries.sortedBy { it.value.first }.map { (timestamp, pair) ->
            RawFrame(
                requireNotNull(burst.images[timestamp]),
                burst.width,
                burst.height,
                pair.second,
                physicalId
            )
        }
        pending = null
        if (burst.nightTiming != null) {
            restoreNightPreview(burst, frames, null)
            return
        }
        restoreRawPreview()
        if (burst.continuation.isActive) {
            burst.continuation.resume(
                RawBurst(
                    frames,
                    burst.info,
                    burst.orientation,
                    SystemClock.elapsedRealtime() - burst.started,
                    device?.id,
                    opticalRatio,
                    requestedControls = userControls,
                    digitalZoom = burst.digitalZoom,
                    hdrRequested = burst.hdrRequested,
                    hdrFallbackReason = burst.hdrFallbackReason
                )
            )
        }
    }

    private fun preserveHdrBaseline(burst: PendingBurst) {
        val slot = burst.completedHdrBaseline ?: return
        if (slot.get() != null) return
        val timestamp = rawHdrBaselineTimestamp(
            burst.results.mapValues { it.value.first },
            burst.images.keys
        ) ?: return
        val baseline = RawFrame(
            requireNotNull(burst.images[timestamp]),
            burst.width,
            burst.height,
            requireNotNull(burst.results[timestamp]).second,
            physicalId
        )
        slot.compareAndSet(
            null,
            RawBurst(
                listOf(baseline), burst.info, burst.orientation,
                SystemClock.elapsedRealtime() - burst.started, device?.id, opticalRatio,
                requestedControls = userControls, digitalZoom = burst.digitalZoom,
                hdrRequested = burst.hdrRequested, hdrFallbackReason = burst.hdrFallbackReason
            )
        )
    }

    private fun restoreRawPreview() {
        latest = null
        latestPreviewRequest = null
        // Pre-burst queued preview callbacks have the old tag and cannot release shutter readiness.
        if (userControls != null) controlsEpoch = Any()
        restorePreview()
    }

    private fun failOpen(error: Exception) {
        nightRestore?.let { restoring ->
            nightRestore = null
            lastNightTiming = restoring.timing.copy(failure = error.message)
            if (restoring.continuation.isActive) {
                restoring.continuation.resumeWithException(error)
            }
        }
        opening?.let { if (it.isActive) it.resumeWithException(error) }
        opening = null
        preparing?.let { if (it.isActive) it.resumeWithException(error) }
        preparing = null
        failBurst(error)
    }

    private fun failBurst(error: Exception) {
        val burst = pending ?: return
        pending = null
        if (burst.nightTiming != null && !closed.get()) {
            restoreNightPreview(burst, null, error)
            burst.images.clear()
            burst.results.clear()
            return
        }
        preserveHdrBaseline(burst)
        val baseline = burst.completedHdrBaseline?.get()?.copy(
            elapsedMs = SystemClock.elapsedRealtime() - burst.started,
            hdrFallbackReason = "bracket_capture_failed_${error.javaClass.simpleName}"
        )
        burst.images.clear()
        burst.results.clear()
        if (baseline != null) {
            try {
                // Discard remaining requests before a fresh preview can make another capture ready.
                session?.abortCaptures()
            } catch (_: Exception) {
                // Closed/disconnected hardware cannot abort; the baseline still belongs to the job.
            }
        }
        if (burst.nightTiming == null) restoreRawPreview() else restorePreview()
        if (burst.continuation.isActive) {
            if (baseline != null) {
                burst.continuation.resume(baseline)
            } else {
                burst.continuation.resumeWithException(error)
            }
        }
    }

    private fun abortBurst(message: String) {
        try {
            session?.abortCaptures()
        } catch (_: Exception) {
            // Closing/disconnecting devices can no longer accept abort requests.
        }
        failBurst(IllegalStateException(message))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            failOpen(IllegalStateException("RAW camera closed"))
            clearNightHold()
            prepareTimeout?.let(handler::removeCallbacks)
            prepareTimeout = null
            session?.close()
            session = null
            device?.close()
            device = null
            reader?.close()
            reader = null
            previewSurface?.release()
            previewSurface = null
            latest = null
            thread.quitSafely()
        }
    }

    private class PendingBurst(
        val count: Int,
        val info: CameraCharacteristics,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val started: Long,
        val continuation: CancellableContinuation<RawBurst>,
        val referenceTimestamp: Long,
        val digitalZoom: Float = 1f,
        val hdrRequested: Boolean = false,
        val hdrFallbackReason: String? = null,
        val completedHdrBaseline: AtomicReference<RawBurst?>? = null,
        var nightTiming: NightCaptureTiming? = null,
        val completedNightFrames: AtomicReference<RawBurst?>? = null,
        var sequenceId: Int = -1,
        val images: MutableMap<Long, ByteBuffer> = linkedMapOf(),
        val results: MutableMap<Long, Pair<Int, TotalCaptureResult>> = linkedMapOf()
    )

    private data class BurstTag(val burst: PendingBurst, val index: Int)

    private class NightPreviewHold(
        val startedMs: Long,
        val referenceTimestamp: Long,
        val calibration: NightPreviewHoldPolicy.Calibration,
        var requestTag: Any? = null
    )

    private class NightRestore(
        val continuation: CancellableContinuation<RawBurst>,
        val timing: NightCaptureTiming,
        val afterTimestamp: Long,
        val burst: RawBurst?,
        val error: Exception?,
        val tag: Any = Any()
    )

    private fun restoreNightPreview(
        pendingBurst: PendingBurst,
        frames: List<RawFrame>?,
        error: Exception?
    ) {
        val timing = requireNotNull(pendingBurst.nightTiming).copy(
            rawReceivedElapsedMs = if (frames != null) SystemClock.elapsedRealtime() else null,
            failure = error?.message
        )
        lastNightTiming = timing
        val burst = frames?.let {
            RawBurst(
                it,
                pendingBurst.info,
                pendingBurst.orientation,
                SystemClock.elapsedRealtime() - pendingBurst.started,
                device?.id,
                opticalRatio,
                timing
            )
        }
        if (burst != null) pendingBurst.completedNightFrames?.set(burst)
        val restoring = NightRestore(
            pendingBurst.continuation,
            timing,
            pendingBurst.results.keys.maxOrNull() ?: pendingBurst.referenceTimestamp,
            burst,
            error
        )
        nightRestore = restoring
        nightPreviewEpoch = restoring.tag
        latest = null
        val hold = nightHold?.takeIf {
            NightPreviewHoldPolicy.active(it.startedMs, SystemClock.elapsedRealtime())
        }
        restorePreview(restoring.tag, hold)
    }

    private fun outputConfiguration(surface: Surface) = OutputConfiguration(surface).apply {
        physicalId?.let(::setPhysicalCameraId)
    }

    private fun sourceResult(result: TotalCaptureResult): TotalCaptureResult? =
        physicalId?.let { result.physicalCameraTotalResults[it] } ?: run {
            if (physicalId == null) result else null
        }

    private fun newRequest(camera: CameraDevice, template: Int): CaptureRequest.Builder =
        physicalId?.let { camera.createCaptureRequest(template, setOf(it)) }
            ?: camera.createCaptureRequest(template)

    @Suppress("UNCHECKED_CAST")
    private fun applyPhysicalControls(builder: CaptureRequest.Builder) {
        val id = physicalId ?: return
        // Physical metadata and settings must refer to the pinned stream, including AF triggers.
        // Copy only controls used here, never logical template crop/calibration defaults.
        val controls = listOf(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_LOCK,
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.SENSOR_FRAME_DURATION,
            CaptureRequest.CONTROL_AWB_LOCK,
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.LENS_FOCUS_DISTANCE,
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE,
            CaptureRequest.EDGE_MODE
        ) + if (userControls != null) {
            listOf(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                CaptureRequest.CONTROL_AE_REGIONS,
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AF_REGIONS
            )
        } else {
            emptyList()
        }
        for (key in controls.filter { it in physicalRequestKeys }) {
            val typed = key as CaptureRequest.Key<Any>
            builder.get(typed)?.let { builder.setPhysicalCameraKey(typed, it, id) }
        }
    }

    private companion object {
        fun rawSize(info: CameraCharacteristics): Size? =
            info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.filter { it.width.toLong() * it.height <= 16_000_000 }
                ?.maxByOrNull { it.width.toLong() * it.height }

        fun orientation(info: CameraCharacteristics, rotation: Int): Int {
            val degrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val sensor = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val front = info.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            return (sensor + (if (front) degrees else -degrees) + 360) % 360
        }

        fun applyTransform(
            view: TextureView,
            size: Size,
            info: CameraCharacteristics,
            zoom: Float = 1f
        ) {
            if (view.width == 0 || view.height == 0) return
            val rotation = (view.display?.rotation ?: Surface.ROTATION_0) * 90
            val values = RawPreviewTransform.matrixValues(
                view.width,
                view.height,
                size.width,
                size.height,
                info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                rotation
            )
            // Applying the full RAW/DNG orientation here would rotate the sensor a second time.
            view.setTransform(
                Matrix().apply {
                    setValues(values)
                    postScale(zoom, zoom, view.width / 2f, view.height / 2f)
                }
            )
        }

        suspend fun awaitSurface(view: TextureView): SurfaceTexture {
            view.surfaceTexture?.let { return it }
            return suspendCancellableCoroutine { continuation ->
                val listener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        w: Int,
                        h: Int
                    ) {
                        view.surfaceTextureListener = null
                        if (continuation.isActive) continuation.resume(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        w: Int,
                        h: Int
                    ) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
                view.surfaceTextureListener = listener
                continuation.invokeOnCancellation {
                    view.post {
                        if (view.surfaceTextureListener === listener) {
                            view.surfaceTextureListener = null
                        }
                    }
                }
            }
        }

        fun pack(image: Image): ByteBuffer {
            val plane = image.planes.single()
            check(plane.pixelStride == 2) { "Unsupported RAW pixel stride" }
            val rowBytes = image.width * 2
            check(plane.rowStride >= rowBytes)
            val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
            val start = source.position()
            val sourceEnd = source.limit()
            val packed = ByteBuffer.allocateDirect(rowBytes * image.height)
                .order(ByteOrder.nativeOrder())
            for (row in 0 until image.height) {
                val offset = start + row * plane.rowStride
                check(offset.toLong() + rowBytes <= sourceEnd) { "Truncated RAW image" }
                source.limit(offset + rowBytes)
                source.position(offset)
                packed.put(source)
            }
            packed.flip()
            return packed
        }
    }
}
