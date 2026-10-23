package dev.lunacam.domain.preview

/**
 * Camera failures classified the way DESIGN.md section 9.3 distinguishes them.
 *
 * [OPEN_TIMEOUT], [CONFIGURE_FAILED], and [ACCESS_ERROR] are P2 additions: section 9.3 names the
 * `CameraDevice.StateCallback` error codes and the three second open timeout but does not give
 * those three cases a recovery rule, so this module defines one and DESIGN.md records it.
 */
enum class CameraFailureKind {
    /** `ERROR_CAMERA_DISABLED`: possible device policy, never auto retried. */
    DISABLED,

    /** `ERROR_CAMERA_IN_USE`. */
    IN_USE,

    /** `ERROR_MAX_CAMERAS_IN_USE`. */
    MAX_IN_USE,

    /** `ERROR_CAMERA_DEVICE`. */
    DEVICE,

    /** `ERROR_CAMERA_SERVICE`: needs a longer recovery than the app may loop on. */
    SERVICE,

    /** `onDisconnected`: a recoverable device event, not a user mistake. */
    DISCONNECTED,

    /** `openCamera` did not call back within the open timeout. */
    OPEN_TIMEOUT,

    /** `onConfigureFailed` for the preview session. */
    CONFIGURE_FAILED,

    /** `CameraAccessException` or an equivalent framework refusal. */
    ACCESS_ERROR
}

data class CameraFailure(val kind: CameraFailureKind, val detail: String? = null)
