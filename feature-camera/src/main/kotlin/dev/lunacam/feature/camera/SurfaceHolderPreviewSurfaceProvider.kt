package dev.lunacam.feature.camera

import android.view.Surface
import android.view.SurfaceHolder
import dev.lunacam.camera.camera2.preview.PreviewSurfaceProvider
import dev.lunacam.domain.preview.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges a `SurfaceView` holder to the camera controller.
 *
 * The camera chooses the buffer size, so [acquireSurface] applies it with
 * `SurfaceHolder.setFixedSize` and waits for the matching `surfaceChanged` before handing the
 * surface over. Configuring a session against a stale buffer size is exactly the stretch that
 * FR-PRV-001 forbids.
 *
 * Both the holder callbacks and the controller coroutine run on the main thread, so the mutable
 * fields below need no extra synchronization.
 */
class SurfaceHolderPreviewSurfaceProvider :
    PreviewSurfaceProvider,
    SurfaceHolder.Callback {
    private var holder: SurfaceHolder? = null
    private var currentBufferSize: IntSize? = null
    private var pendingBufferSize: IntSize? = null
    private var pendingResize: CompletableDeferred<Unit>? = null

    var onSurfaceReady: (() -> Unit)? = null
    var onSurfaceLost: (() -> Unit)? = null

    override suspend fun acquireSurface(bufferSize: IntSize): Surface? {
        val holder = this.holder ?: return null
        if (!bufferSize.isPositive) return null

        if (currentBufferSize != bufferSize) {
            val awaited = CompletableDeferred<Unit>()
            pendingBufferSize = bufferSize
            pendingResize = awaited
            holder.setFixedSize(bufferSize.width, bufferSize.height)
            val resized = withTimeoutOrNull(SURFACE_RESIZE_TIMEOUT_MS) { awaited.await() }
            pendingBufferSize = null
            pendingResize = null
            if (resized == null) return null
        }

        val surface = holder.surface
        return if (surface != null && surface.isValid) surface else null
    }

    override fun releaseSurface() {
        pendingResize?.cancel()
        pendingResize = null
        pendingBufferSize = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        this.holder = holder
        onSurfaceReady?.invoke()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        this.holder = holder
        currentBufferSize = IntSize(width, height)
        if (pendingBufferSize == currentBufferSize) {
            pendingResize?.complete(Unit)
        }
        onSurfaceReady?.invoke()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        this.holder = null
        currentBufferSize = null
        releaseSurface()
        onSurfaceLost?.invoke()
    }

    private companion object {
        const val SURFACE_RESIZE_TIMEOUT_MS = 1_000L
    }
}
