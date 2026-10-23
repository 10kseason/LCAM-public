package dev.lunacam.domain.preview

/** Why the camera stopped and cannot be retried automatically. */
enum class BlockReason {
    CAMERA_DISABLED_BY_POLICY,
    CAMERA_BUSY,
    SERVICE_RESTART_REQUIRED,
    SCREEN_NOT_VISIBLE,
    UNRECOVERABLE
}

sealed interface RecoveryAction {
    /** Close and reopen the `CameraDevice` after [backoffMs]. */
    data class ReopenDevice(val backoffMs: Long) : RecoveryAction

    /** Keep the device and rebuild only the capture session after [backoffMs]. */
    data class RecreateSession(val backoffMs: Long) : RecoveryAction

    /** Stop retrying and tell the user why. */
    data class Blocked(val reason: BlockReason) : RecoveryAction
}

/** How many recovery attempts the current camera generation has already spent. */
data class RecoveryAttempts(val sessionRecreateCount: Int = 0, val deviceReopenCount: Int = 0)

/**
 * Encodes the per-error recovery rules of DESIGN.md section 9.3 as a pure decision so they can be
 * tested without a device. Retry budgets are per camera generation and reset on a clean preview.
 */
object RecoveryPolicy {
    const val IN_USE_MAX_REOPENS = 2
    const val IN_USE_BACKOFF_MS = 500L

    const val DISCONNECTED_MAX_REOPENS = 2
    const val DISCONNECTED_BACKOFF_MS = 300L

    const val DEVICE_MAX_SESSION_RECREATES = 1
    const val DEVICE_MAX_REOPENS = 1

    const val CONFIGURE_MAX_SESSION_RECREATES = 1
    const val OPEN_TIMEOUT_MAX_REOPENS = 1
    const val ACCESS_ERROR_MAX_REOPENS = 1

    fun decide(
        failure: CameraFailure,
        attempts: RecoveryAttempts,
        isScreenVisible: Boolean
    ): RecoveryAction {
        if (!isScreenVisible) return RecoveryAction.Blocked(BlockReason.SCREEN_NOT_VISIBLE)

        return when (failure.kind) {
            // Administrator policy is plausible, so never retry on the user's behalf.
            CameraFailureKind.DISABLED ->
                RecoveryAction.Blocked(BlockReason.CAMERA_DISABLED_BY_POLICY)

            // Short backoff, bounded retries, only while the user is still on this screen.
            CameraFailureKind.IN_USE, CameraFailureKind.MAX_IN_USE ->
                if (attempts.deviceReopenCount < IN_USE_MAX_REOPENS) {
                    RecoveryAction.ReopenDevice(IN_USE_BACKOFF_MS)
                } else {
                    RecoveryAction.Blocked(BlockReason.CAMERA_BUSY)
                }

            // One session rebuild, then one camera reopen, then stop.
            CameraFailureKind.DEVICE ->
                when {
                    attempts.sessionRecreateCount < DEVICE_MAX_SESSION_RECREATES ->
                        RecoveryAction.RecreateSession(0L)

                    attempts.deviceReopenCount < DEVICE_MAX_REOPENS ->
                        RecoveryAction.ReopenDevice(0L)

                    else -> RecoveryAction.Blocked(BlockReason.UNRECOVERABLE)
                }

            // Needs a longer recovery than an app retry loop can provide.
            CameraFailureKind.SERVICE ->
                RecoveryAction.Blocked(BlockReason.SERVICE_RESTART_REQUIRED)

            CameraFailureKind.DISCONNECTED ->
                if (attempts.deviceReopenCount < DISCONNECTED_MAX_REOPENS) {
                    RecoveryAction.ReopenDevice(DISCONNECTED_BACKOFF_MS)
                } else {
                    RecoveryAction.Blocked(BlockReason.CAMERA_BUSY)
                }

            CameraFailureKind.OPEN_TIMEOUT ->
                if (attempts.deviceReopenCount < OPEN_TIMEOUT_MAX_REOPENS) {
                    RecoveryAction.ReopenDevice(0L)
                } else {
                    RecoveryAction.Blocked(BlockReason.UNRECOVERABLE)
                }

            CameraFailureKind.CONFIGURE_FAILED ->
                if (attempts.sessionRecreateCount < CONFIGURE_MAX_SESSION_RECREATES) {
                    RecoveryAction.RecreateSession(0L)
                } else {
                    RecoveryAction.Blocked(BlockReason.UNRECOVERABLE)
                }

            CameraFailureKind.ACCESS_ERROR ->
                if (attempts.deviceReopenCount < ACCESS_ERROR_MAX_REOPENS) {
                    RecoveryAction.ReopenDevice(IN_USE_BACKOFF_MS)
                } else {
                    RecoveryAction.Blocked(BlockReason.UNRECOVERABLE)
                }
        }
    }
}
