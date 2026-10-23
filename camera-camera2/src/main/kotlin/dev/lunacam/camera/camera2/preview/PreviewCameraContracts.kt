package dev.lunacam.camera.camera2.preview

import android.view.Surface
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraState
import dev.lunacam.domain.preview.IntSize
import dev.lunacam.domain.preview.PointD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * View geometry the preview depends on.
 *
 * FR-PRV-002 keeps display rotation separate from sensor orientation, so it travels with the view
 * size rather than being folded into one angle by the caller.
 */
data class PreviewViewGeometry(val viewSize: IntSize, val displayRotationDegrees: Int)

/**
 * Supplies the preview `Surface`.
 *
 * The camera picks the buffer size, so [acquireSurface] receives it and the implementation is
 * expected to apply it (`SurfaceHolder.setFixedSize`) before returning a valid surface. Returning
 * null means "not ready yet", which is not an error per DESIGN.md section 9.1.
 */
interface PreviewSurfaceProvider {
    suspend fun acquireSurface(bufferSize: IntSize): Surface?

    fun releaseSurface()
}

/**
 * The preview lifecycle owner.
 *
 * Every entry point is a suspend function so the `Mutex` serialization of DESIGN.md section 9.3
 * can hold across the whole open/configure sequence without blocking the main thread.
 */
interface PreviewCameraController : AutoCloseable {
    val state: StateFlow<CameraState>

    suspend fun start(geometry: PreviewViewGeometry, surfaceProvider: PreviewSurfaceProvider)

    /** Applies a rotation or resize without reopening the camera (DESIGN.md section 5.6). */
    suspend fun updateGeometry(geometry: PreviewViewGeometry)

    suspend fun stop()

    suspend fun focusAt(tap: PointD)

    suspend fun setExposureCompensationEv(requestedEv: Double)
}

fun interface PreviewCameraControllerFactory {
    fun create(scope: CoroutineScope): PreviewCameraController
}

/** Carries a classified [CameraFailure] through the suspend call chain. */
class CameraFailureException(val failure: CameraFailure) :
    Exception("${failure.kind}: ${failure.detail ?: "no detail"}")
