package dev.lunacam.camera.camera2.preview

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraFailureKind
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Configures the preview-only session of DESIGN.md section 9.2 (output 1 alone).
 *
 * P3 adds the RAW `ImageReader` as a second output rather than replacing this path.
 */
internal object CaptureSessionConfigurator {
    suspend fun configure(
        device: CameraDevice,
        surfaces: List<Surface>,
        executor: Executor
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        var configuredSession: CameraCaptureSession? = null
        continuation.invokeOnCancellation { configuredSession?.close() }

        val callback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    configuredSession = session
                    if (continuation.isActive) {
                        continuation.resume(session)
                    } else {
                        session.close()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            CameraFailureException(
                                CameraFailure(
                                    CameraFailureKind.CONFIGURE_FAILED,
                                    "onConfigureFailed"
                                )
                            )
                        )
                    }
                }
            }

        try {
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    surfaces.map(::OutputConfiguration),
                    executor,
                    callback
                )
            )
        } catch (error: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    CameraFailureException(Camera2FailureMapper.fromThrowable(error))
                )
            }
        }
    }
}
