package dev.lunacam.camera.camera2.preview

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.view.Surface
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraFailureKind
import dev.lunacam.domain.preview.CameraState
import dev.lunacam.domain.preview.CameraStateMachine
import dev.lunacam.domain.preview.MeteringRegion
import dev.lunacam.domain.preview.MeteringRegionMapper
import dev.lunacam.domain.preview.PointD
import dev.lunacam.domain.preview.PreviewCameraSelector
import dev.lunacam.domain.preview.PreviewRuntime
import dev.lunacam.domain.preview.PreviewSizeSelector
import dev.lunacam.domain.preview.PreviewSizeTier
import dev.lunacam.domain.preview.PreviewTransformFactory
import dev.lunacam.domain.preview.RecoveryAction
import dev.lunacam.domain.preview.RecoveryAttempts
import dev.lunacam.domain.preview.RecoveryPolicy
import dev.lunacam.domain.preview.StreamProfile
import dev.lunacam.domain.preview.TransitionResult
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the preview camera generation.
 *
 * DESIGN.md section 9.3 requires open and close to be serialized by a `Mutex` and a generation
 * token; section 7.3 requires at most one active camera generation. Both live here: every public
 * entry point takes [mutex], and every asynchronous framework callback carries the generation it
 * was created under so a superseded callback can only release resources.
 */
class Camera2PreviewController internal constructor(
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
    private val cameraExecutor: Executor,
    private val openTimeoutMs: Long,
    private val hardwareReader: PreviewCameraHardwareReader
) : PreviewCameraController {
    constructor(
        cameraManager: CameraManager,
        scope: CoroutineScope,
        cameraExecutor: Executor,
        openTimeoutMs: Long = CameraDeviceOpener.DEFAULT_OPEN_TIMEOUT_MS
    ) : this(
        cameraManager = cameraManager,
        scope = scope,
        cameraExecutor = cameraExecutor,
        openTimeoutMs = openTimeoutMs,
        hardwareReader = PreviewCameraHardwareReader(cameraManager)
    )

    private val mutex = Mutex()
    private val machine = CameraStateMachine()
    private val mutableState = MutableStateFlow<CameraState>(CameraState.Closed)

    override val state: StateFlow<CameraState> = mutableState.asStateFlow()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var surfaceProvider: PreviewSurfaceProvider? = null
    private var geometry: PreviewViewGeometry? = null
    private var hardware: PreviewCameraHardware? = null
    private var attempts = RecoveryAttempts()
    private var sizeTier = PreviewSizeTier.FULL_HD
    private var isScreenVisible = false
    private var exposureCompensationStep = 0
    private var meteringRegion: MeteringRegion? = null

    override suspend fun start(
        geometry: PreviewViewGeometry,
        surfaceProvider: PreviewSurfaceProvider
    ) {
        mutex.withLock {
            this.geometry = geometry
            this.surfaceProvider = surfaceProvider
            isScreenVisible = true
            attempts = RecoveryAttempts()
            sizeTier = PreviewSizeTier.FULL_HD
            if (device != null && session != null) return@withLock
            openAndConfigureLocked()
        }
    }

    override suspend fun updateGeometry(geometry: PreviewViewGeometry) {
        mutex.withLock {
            this.geometry = geometry
            val previewing = machine.state as? CameraState.Previewing ?: return@withLock
            val hardware = this.hardware ?: return@withLock
            // A rotation or resize only rebuilds the transform. The buffer size and the open
            // camera are deliberately untouched (DESIGN.md section 5.6).
            val transform =
                PreviewTransformFactory.create(
                    activeArray = hardware.activeArray,
                    bufferSize = previewing.runtime.profile.previewSize,
                    viewSize = geometry.viewSize,
                    sensorOrientationDegrees = hardware.sensorOrientationDegrees,
                    displayRotationDegrees = geometry.displayRotationDegrees,
                    mirrored = hardware.mirrored
                ) ?: return@withLock
            publish(CameraState.Previewing(previewing.runtime.copy(transform = transform)))
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            isScreenVisible = false
            publish(CameraState.Closing)
            teardownLocked()
            publish(CameraState.Closed)
            // Invalidate every callback still in flight from the generation just closed.
            machine.beginGeneration()
        }
    }

    override suspend fun focusAt(tap: PointD) {
        mutex.withLock {
            val previewing = machine.state as? CameraState.Previewing ?: return@withLock
            val hardware = this.hardware ?: return@withLock
            val geometry = this.geometry ?: return@withLock
            val region =
                MeteringRegionMapper.map(
                    transform = previewing.runtime.transform,
                    viewSize = geometry.viewSize,
                    tap = tap,
                    activeArray = hardware.activeArray
                ) ?: return@withLock

            meteringRegion = region
            val applied =
                submitPreviewRequestsLocked(
                    hardware = hardware,
                    spec = PreviewRequestSpec(exposureCompensationStep, region),
                    triggerSpec = PreviewRequestSpec(exposureCompensationStep, region, true)
                )
            if (applied) {
                publish(CameraState.Previewing(previewing.runtime.copy(meteringRegion = region)))
            }
        }
    }

    override suspend fun setExposureCompensationEv(requestedEv: Double) {
        mutex.withLock {
            val previewing = machine.state as? CameraState.Previewing ?: return@withLock
            val hardware = this.hardware ?: return@withLock
            val step = hardware.exposureCompensation.quantize(requestedEv)
            if (step == exposureCompensationStep) return@withLock

            exposureCompensationStep = step
            val applied =
                submitPreviewRequestsLocked(
                    hardware = hardware,
                    spec = PreviewRequestSpec(step, meteringRegion),
                    triggerSpec = null
                )
            if (applied) {
                publish(
                    CameraState.Previewing(
                        previewing.runtime.copy(exposureCompensationStep = step)
                    )
                )
            }
        }
    }

    /**
     * Best-effort synchronous release. The owner is expected to call [stop] first; this exists so
     * a cancelled scope cannot leak a `CameraDevice`.
     */
    override fun close() {
        closeQuietly { session?.close() }
        session = null
        closeQuietly { device?.close() }
        device = null
        closeQuietly { surfaceProvider?.releaseSurface() }
        surface = null
    }

    private suspend fun openAndConfigureLocked() {
        val geometry = this.geometry ?: return
        this.surfaceProvider ?: return
        if (!isScreenVisible) return

        val generation = machine.beginGeneration()
        try {
            val candidate =
                PreviewCameraSelector.selectMainBack(hardwareReader.candidates())
                    ?: throw CameraFailureException(
                        CameraFailure(CameraFailureKind.ACCESS_ERROR, "no back camera candidate")
                    )
            val hardware =
                hardwareReader.read(candidate.cameraKey)
                    ?: throw CameraFailureException(
                        CameraFailure(
                            CameraFailureKind.ACCESS_ERROR,
                            "preview characteristics unavailable"
                        )
                    )
            this.hardware = hardware
            publish(CameraState.Opening(candidate.cameraKey), generation)

            val surface = acquireSurfaceLocked(hardware) ?: return
            val opened =
                CameraDeviceOpener.open(
                    cameraManager = cameraManager,
                    cameraId = candidate.cameraKey,
                    executor = cameraExecutor,
                    timeoutMs = openTimeoutMs
                ) { failure -> onAsynchronousFailure(failure, generation) }
            device = opened

            configureSessionLocked(opened, hardware, surface, generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleFailureLocked(Camera2FailureMapper.fromThrowable(error), generation)
        }
    }

    private suspend fun configureSessionLocked(
        device: CameraDevice,
        hardware: PreviewCameraHardware,
        surface: Surface,
        generation: Long
    ) {
        val geometry = this.geometry ?: return
        val previewSize = selectedPreviewSize(hardware)
        val profile = StreamProfile(hardware.cameraId, previewSize)
        publish(CameraState.Configuring(profile), generation)

        val configured =
            CaptureSessionConfigurator.configure(device, listOf(surface), cameraExecutor)
        session = configured
        configured.setSingleRepeatingRequest(
            PreviewRequestFactory.build(
                device = device,
                surface = surface,
                hardware = hardware,
                spec = PreviewRequestSpec(exposureCompensationStep, meteringRegion)
            ),
            cameraExecutor,
            NoOpCaptureCallback
        )

        val transform =
            PreviewTransformFactory.create(
                activeArray = hardware.activeArray,
                bufferSize = previewSize,
                viewSize = geometry.viewSize,
                sensorOrientationDegrees = hardware.sensorOrientationDegrees,
                displayRotationDegrees = geometry.displayRotationDegrees,
                mirrored = hardware.mirrored
            ) ?: throw CameraFailureException(
                CameraFailure(CameraFailureKind.CONFIGURE_FAILED, "preview transform unavailable")
            )

        publish(
            CameraState.Previewing(
                PreviewRuntime(
                    profile = profile,
                    transform = transform,
                    exposureCompensationSpec = hardware.exposureCompensation,
                    exposureCompensationStep = exposureCompensationStep,
                    meteringRegion = meteringRegion
                )
            ),
            generation
        )
        // A clean preview resets the per-generation recovery budget.
        attempts = RecoveryAttempts()
    }

    /**
     * Returns null while the surface is not ready yet. That is a wait, not a failure, so the
     * state stays where it is and the next [start] retries (DESIGN.md section 9.1).
     */
    private suspend fun acquireSurfaceLocked(hardware: PreviewCameraHardware): Surface? {
        val previewSize = selectedPreviewSize(hardware)
        val acquired = surfaceProvider?.acquireSurface(previewSize) ?: return null
        surface = acquired
        return acquired
    }

    /**
     * The preview size is chosen against the sensor active array, not the view, so the viewfinder
     * frames exactly what a RAW capture records. [geometry] therefore does not take part here; it
     * only decides how the result is letterboxed on screen.
     */
    private fun selectedPreviewSize(hardware: PreviewCameraHardware) = PreviewSizeSelector
        .select(
            candidates = hardware.previewSizes,
            targetAspectRatio = PreviewSizeSelector.normalizedAspectRatioOf(hardware.activeArray),
            startTier = sizeTier
        )
        ?.size
        ?: throw CameraFailureException(
            CameraFailure(CameraFailureKind.CONFIGURE_FAILED, "no usable preview size")
        )

    private fun submitPreviewRequestsLocked(
        hardware: PreviewCameraHardware,
        spec: PreviewRequestSpec,
        triggerSpec: PreviewRequestSpec?
    ): Boolean {
        val device = this.device ?: return false
        val session = this.session ?: return false
        val surface = this.surface ?: return false
        return try {
            triggerSpec?.let {
                session.captureSingleRequest(
                    PreviewRequestFactory.build(device, surface, hardware, it),
                    cameraExecutor,
                    NoOpCaptureCallback
                )
            }
            session.setSingleRepeatingRequest(
                PreviewRequestFactory.build(device, surface, hardware, spec),
                cameraExecutor,
                NoOpCaptureCallback
            )
            true
        } catch (error: Throwable) {
            scope.launch {
                mutex.withLock {
                    handleFailureLocked(
                        Camera2FailureMapper.fromThrowable(error),
                        machine.generation
                    )
                }
            }
            false
        }
    }

    private suspend fun handleFailureLocked(failure: CameraFailure, generation: Long) {
        if (generation != machine.generation) return

        when (val action = RecoveryPolicy.decide(failure, attempts, isScreenVisible)) {
            is RecoveryAction.ReopenDevice -> {
                attempts = attempts.copy(deviceReopenCount = attempts.deviceReopenCount + 1)
                publish(CameraState.Recovering(attempts.deviceReopenCount, failure), generation)
                teardownLocked()
                if (action.backoffMs > 0L) delay(action.backoffMs)
                openAndConfigureLocked()
            }

            is RecoveryAction.RecreateSession -> {
                attempts = attempts.copy(sessionRecreateCount = attempts.sessionRecreateCount + 1)
                // A rejected stream combination descends one rung of DESIGN.md section 8.5.
                if (failure.kind == CameraFailureKind.CONFIGURE_FAILED) {
                    sizeTier = PreviewSizeSelector.nextTier(sizeTier) ?: sizeTier
                }
                publish(CameraState.Recovering(attempts.sessionRecreateCount, failure), generation)
                if (action.backoffMs > 0L) delay(action.backoffMs)
                recreateSessionLocked(generation)
            }

            is RecoveryAction.Blocked -> {
                publish(CameraState.Blocked(failure), generation)
                teardownLocked()
            }
        }
    }

    /** Keeps the open `CameraDevice` and rebuilds only the session, per DESIGN.md section 9.3. */
    private suspend fun recreateSessionLocked(generation: Long) {
        val device = this.device
        val hardware = this.hardware
        val geometry = this.geometry
        if (device == null || hardware == null || geometry == null) {
            teardownLocked()
            openAndConfigureLocked()
            return
        }

        closeQuietly { session?.close() }
        session = null
        closeQuietly { surfaceProvider?.releaseSurface() }
        surface = null

        try {
            val surface = acquireSurfaceLocked(hardware) ?: return
            configureSessionLocked(device, hardware, surface, generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleFailureLocked(Camera2FailureMapper.fromThrowable(error), generation)
        }
    }

    private fun onAsynchronousFailure(failure: CameraFailure, generation: Long) {
        scope.launch { mutex.withLock { handleFailureLocked(failure, generation) } }
    }

    private fun teardownLocked() {
        closeQuietly { session?.close() }
        session = null
        closeQuietly { device?.close() }
        device = null
        closeQuietly { surfaceProvider?.releaseSurface() }
        surface = null
    }

    private fun publish(target: CameraState, generation: Long = machine.generation) {
        val result = machine.transition(target, generation)
        if (result is TransitionResult.Applied) {
            mutableState.value = result.state
        }
    }

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
    }

    private object NoOpCaptureCallback : CameraCaptureSession.CaptureCallback()
}
