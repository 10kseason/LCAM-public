package dev.lunacam.camera.camera2.preview

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraFailureKind

/** Maps Camera2 error codes and exceptions onto the domain classification. */
internal object Camera2FailureMapper {
    fun fromDeviceError(errorCode: Int): CameraFailure = when (errorCode) {
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
            CameraFailure(CameraFailureKind.DISABLED, "ERROR_CAMERA_DISABLED")

        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
            CameraFailure(CameraFailureKind.IN_USE, "ERROR_CAMERA_IN_USE")

        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
            CameraFailure(CameraFailureKind.MAX_IN_USE, "ERROR_MAX_CAMERAS_IN_USE")

        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
            CameraFailure(CameraFailureKind.DEVICE, "ERROR_CAMERA_DEVICE")

        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
            CameraFailure(CameraFailureKind.SERVICE, "ERROR_CAMERA_SERVICE")

        // An unknown code is treated as a device error so the bounded device recovery of
        // DESIGN.md section 9.3 applies instead of an unbounded retry.
        else -> CameraFailure(CameraFailureKind.DEVICE, "unknown error code $errorCode")
    }

    fun fromThrowable(error: Throwable): CameraFailure = when (error) {
        is CameraFailureException -> error.failure
        is CameraAccessException -> fromAccessException(error)
        is SecurityException ->
            CameraFailure(CameraFailureKind.DISABLED, "SecurityException")

        is IllegalArgumentException ->
            CameraFailure(CameraFailureKind.ACCESS_ERROR, "IllegalArgumentException")

        is IllegalStateException ->
            CameraFailure(CameraFailureKind.DEVICE, "IllegalStateException")

        else -> CameraFailure(CameraFailureKind.ACCESS_ERROR, error.javaClass.simpleName)
    }

    private fun fromAccessException(error: CameraAccessException): CameraFailure =
        when (error.reason) {
            CameraAccessException.CAMERA_DISABLED ->
                CameraFailure(CameraFailureKind.DISABLED, "CAMERA_DISABLED")

            CameraAccessException.CAMERA_IN_USE ->
                CameraFailure(CameraFailureKind.IN_USE, "CAMERA_IN_USE")

            CameraAccessException.MAX_CAMERAS_IN_USE ->
                CameraFailure(CameraFailureKind.MAX_IN_USE, "MAX_CAMERAS_IN_USE")

            CameraAccessException.CAMERA_DISCONNECTED ->
                CameraFailure(CameraFailureKind.DISCONNECTED, "CAMERA_DISCONNECTED")

            CameraAccessException.CAMERA_ERROR ->
                CameraFailure(CameraFailureKind.DEVICE, "CAMERA_ERROR")

            else -> CameraFailure(CameraFailureKind.ACCESS_ERROR, "reason ${error.reason}")
        }
}
