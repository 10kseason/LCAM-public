package dev.lunacam.camera.camera2.preview

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraFailureKind
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens a `CameraDevice` without blocking a thread while the framework callback is outstanding
 * (DESIGN.md section 9.3).
 *
 * Errors that arrive after the open completed are routed to [onDeviceEvent] instead of being
 * dropped, and a device that arrives after the timeout is closed immediately.
 */
internal object CameraDeviceOpener {
    const val DEFAULT_OPEN_TIMEOUT_MS = 3_000L

    suspend fun open(
        cameraManager: CameraManager,
        cameraId: String,
        executor: Executor,
        timeoutMs: Long,
        onDeviceEvent: (CameraFailure) -> Unit
    ): CameraDevice {
        val device =
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    var openedDevice: CameraDevice? = null
                    continuation.invokeOnCancellation { openedDevice?.close() }

                    val callback =
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                openedDevice = camera
                                if (continuation.isActive) {
                                    continuation.resume(camera)
                                } else {
                                    // The open already timed out or was cancelled.
                                    camera.close()
                                }
                            }

                            override fun onDisconnected(camera: CameraDevice) {
                                camera.close()
                                report(
                                    continuation.isActive,
                                    CameraFailure(
                                        CameraFailureKind.DISCONNECTED,
                                        "onDisconnected"
                                    )
                                ) { failure ->
                                    continuation.resumeWithException(
                                        CameraFailureException(failure)
                                    )
                                }
                            }

                            override fun onError(camera: CameraDevice, error: Int) {
                                camera.close()
                                report(
                                    continuation.isActive,
                                    Camera2FailureMapper.fromDeviceError(error)
                                ) { failure ->
                                    continuation.resumeWithException(
                                        CameraFailureException(failure)
                                    )
                                }
                            }

                            private fun report(
                                isOpening: Boolean,
                                failure: CameraFailure,
                                resumeWithFailure: (CameraFailure) -> Unit
                            ) {
                                if (isOpening) {
                                    resumeWithFailure(failure)
                                } else {
                                    onDeviceEvent(failure)
                                }
                            }
                        }

                    try {
                        cameraManager.openCamera(cameraId, executor, callback)
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                CameraFailureException(Camera2FailureMapper.fromThrowable(error))
                            )
                        }
                    }
                }
            }

        return device
            ?: throw CameraFailureException(
                CameraFailure(CameraFailureKind.OPEN_TIMEOUT, "openCamera exceeded ${timeoutMs}ms")
            )
    }
}
