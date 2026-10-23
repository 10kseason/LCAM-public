package dev.lunacam.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionUiStateResolverTest {
    @Test
    fun `shows onboarding before the first request`() {
        assertEquals(
            CameraPermissionUiState.NOT_REQUESTED,
            CameraPermissionUiStateResolver.resolve(
                isGranted = false,
                hasRequested = false,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `shows rationale after a reversible denial`() {
        assertEquals(
            CameraPermissionUiState.RATIONALE,
            CameraPermissionUiStateResolver.resolve(
                isGranted = false,
                hasRequested = true,
                shouldShowRationale = true
            )
        )
    }

    @Test
    fun `uses settings after a permanent denial`() {
        assertEquals(
            CameraPermissionUiState.SETTINGS_REQUIRED,
            CameraPermissionUiStateResolver.resolve(
                isGranted = false,
                hasRequested = true,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `granted state wins over request history`() {
        assertEquals(
            CameraPermissionUiState.GRANTED,
            CameraPermissionUiStateResolver.resolve(
                isGranted = true,
                hasRequested = true,
                shouldShowRationale = true
            )
        )
    }
}
