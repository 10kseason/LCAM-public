package dev.lunacam.feature.diagnostics

enum class CameraPermissionUiState {
    NOT_REQUESTED,
    RATIONALE,
    SETTINGS_REQUIRED,
    GRANTED
}

object CameraPermissionUiStateResolver {
    fun resolve(
        isGranted: Boolean,
        hasRequested: Boolean,
        shouldShowRationale: Boolean
    ): CameraPermissionUiState = when {
        isGranted -> CameraPermissionUiState.GRANTED
        !hasRequested -> CameraPermissionUiState.NOT_REQUESTED
        shouldShowRationale -> CameraPermissionUiState.RATIONALE
        else -> CameraPermissionUiState.SETTINGS_REQUIRED
    }
}
