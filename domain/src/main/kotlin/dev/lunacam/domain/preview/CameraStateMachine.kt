package dev.lunacam.domain.preview

sealed interface TransitionResult {
    data class Applied(val state: CameraState) : TransitionResult

    /** A callback from a superseded generation: close resources, leave the state alone. */
    data class IgnoredStaleGeneration(val callbackGeneration: Long, val currentGeneration: Long) :
        TransitionResult

    data class Rejected(val from: CameraState, val to: CameraState) : TransitionResult
}

/**
 * Enforces the transition table and the generation rule of DESIGN.md section 7.1 and the
 * "at most one active camera generation" invariant of section 7.3.
 *
 * This holder is not thread safe by design. It is confined to the single threaded camera
 * executor of section 6.5 so that Camera2 callback ordering is preserved.
 */
class CameraStateMachine(initialState: CameraState = CameraState.Closed) {
    var state: CameraState = initialState
        private set

    var generation: Long = 0L
        private set

    /** Starts a new camera generation and invalidates every in-flight callback. */
    fun beginGeneration(): Long {
        generation += 1
        return generation
    }

    fun transition(target: CameraState, callbackGeneration: Long): TransitionResult {
        if (callbackGeneration != generation) {
            return TransitionResult.IgnoredStaleGeneration(callbackGeneration, generation)
        }
        if (!isAllowed(state, target)) {
            return TransitionResult.Rejected(state, target)
        }
        state = target
        return TransitionResult.Applied(target)
    }

    private fun isAllowed(from: CameraState, to: CameraState): Boolean = when (from) {
        is CameraState.Closed ->
            to is CameraState.Opening ||
                to is CameraState.PermissionRequired ||
                to is CameraState.Blocked

        is CameraState.PermissionRequired ->
            to is CameraState.Opening || to is CameraState.Closed

        is CameraState.Opening ->
            to is CameraState.Configuring ||
                to is CameraState.Recovering ||
                to is CameraState.Blocked ||
                to is CameraState.Closing ||
                to is CameraState.Closed

        is CameraState.Configuring ->
            to is CameraState.Previewing ||
                to is CameraState.Recovering ||
                to is CameraState.Blocked ||
                to is CameraState.Closing ||
                to is CameraState.Closed

        // Previewing to Previewing carries a new PreviewTransform on rotation or resize,
        // which must not force a camera reopen (DESIGN.md section 5.6).
        is CameraState.Previewing ->
            to is CameraState.Previewing ||
                to is CameraState.Capturing ||
                to is CameraState.Recovering ||
                to is CameraState.Blocked ||
                to is CameraState.Closing ||
                to is CameraState.Closed

        is CameraState.Capturing ->
            to is CameraState.Capturing ||
                to is CameraState.Previewing ||
                to is CameraState.Recovering ||
                to is CameraState.Blocked ||
                to is CameraState.Closing

        is CameraState.Recovering ->
            to is CameraState.Recovering ||
                to is CameraState.Opening ||
                to is CameraState.Configuring ||
                to is CameraState.Blocked ||
                to is CameraState.Closing ||
                to is CameraState.Closed

        is CameraState.Blocked ->
            to is CameraState.Closed || to is CameraState.Opening

        is CameraState.Closing ->
            to is CameraState.Closed
    }
}
