package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun `a camera disabled by policy is never retried automatically`() {
        val action = decide(CameraFailureKind.DISABLED)

        assertEquals(RecoveryAction.Blocked(BlockReason.CAMERA_DISABLED_BY_POLICY), action)
    }

    @Test
    fun `a busy camera is retried at most twice after a short backoff`() {
        for (kind in listOf(CameraFailureKind.IN_USE, CameraFailureKind.MAX_IN_USE)) {
            assertEquals(
                "kind=$kind",
                RecoveryAction.ReopenDevice(RecoveryPolicy.IN_USE_BACKOFF_MS),
                decide(kind, RecoveryAttempts(deviceReopenCount = 0))
            )
            assertEquals(
                "kind=$kind",
                RecoveryAction.ReopenDevice(RecoveryPolicy.IN_USE_BACKOFF_MS),
                decide(kind, RecoveryAttempts(deviceReopenCount = 1))
            )
            assertEquals(
                "kind=$kind",
                RecoveryAction.Blocked(BlockReason.CAMERA_BUSY),
                decide(kind, RecoveryAttempts(deviceReopenCount = 2))
            )
        }
    }

    @Test
    fun `a device error recreates the session once then reopens once then stops`() {
        assertEquals(
            RecoveryAction.RecreateSession(0L),
            decide(CameraFailureKind.DEVICE, RecoveryAttempts())
        )
        assertEquals(
            RecoveryAction.ReopenDevice(0L),
            decide(CameraFailureKind.DEVICE, RecoveryAttempts(sessionRecreateCount = 1))
        )
        assertEquals(
            RecoveryAction.Blocked(BlockReason.UNRECOVERABLE),
            decide(
                CameraFailureKind.DEVICE,
                RecoveryAttempts(sessionRecreateCount = 1, deviceReopenCount = 1)
            )
        )
    }

    @Test
    fun `a camera service error asks for a restart instead of looping`() {
        assertEquals(
            RecoveryAction.Blocked(BlockReason.SERVICE_RESTART_REQUIRED),
            decide(CameraFailureKind.SERVICE)
        )
        assertEquals(
            RecoveryAction.Blocked(BlockReason.SERVICE_RESTART_REQUIRED),
            decide(CameraFailureKind.SERVICE, RecoveryAttempts(deviceReopenCount = 5))
        )
    }

    @Test
    fun `a disconnect is treated as recoverable rather than a user mistake`() {
        assertEquals(
            RecoveryAction.ReopenDevice(RecoveryPolicy.DISCONNECTED_BACKOFF_MS),
            decide(CameraFailureKind.DISCONNECTED)
        )
        assertEquals(
            RecoveryAction.Blocked(BlockReason.CAMERA_BUSY),
            decide(CameraFailureKind.DISCONNECTED, RecoveryAttempts(deviceReopenCount = 2))
        )
    }

    @Test
    fun `an open timeout reopens once and then stops`() {
        assertEquals(RecoveryAction.ReopenDevice(0L), decide(CameraFailureKind.OPEN_TIMEOUT))
        assertEquals(
            RecoveryAction.Blocked(BlockReason.UNRECOVERABLE),
            decide(CameraFailureKind.OPEN_TIMEOUT, RecoveryAttempts(deviceReopenCount = 1))
        )
    }

    @Test
    fun `a failed configuration rebuilds the session once and then stops`() {
        assertEquals(
            RecoveryAction.RecreateSession(0L),
            decide(CameraFailureKind.CONFIGURE_FAILED)
        )
        assertEquals(
            RecoveryAction.Blocked(BlockReason.UNRECOVERABLE),
            decide(CameraFailureKind.CONFIGURE_FAILED, RecoveryAttempts(sessionRecreateCount = 1))
        )
    }

    @Test
    fun `an access error reopens once and then stops`() {
        assertEquals(
            RecoveryAction.ReopenDevice(RecoveryPolicy.IN_USE_BACKOFF_MS),
            decide(CameraFailureKind.ACCESS_ERROR)
        )
        assertEquals(
            RecoveryAction.Blocked(BlockReason.UNRECOVERABLE),
            decide(CameraFailureKind.ACCESS_ERROR, RecoveryAttempts(deviceReopenCount = 1))
        )
    }

    @Test
    fun `nothing is retried while the screen is not visible`() {
        for (kind in CameraFailureKind.entries) {
            assertEquals(
                "kind=$kind",
                RecoveryAction.Blocked(BlockReason.SCREEN_NOT_VISIBLE),
                RecoveryPolicy.decide(CameraFailure(kind), RecoveryAttempts(), false)
            )
        }
    }

    @Test
    fun `every failure kind has a decision`() {
        for (kind in CameraFailureKind.entries) {
            assertNotNull("kind=$kind", decide(kind))
        }
    }

    private fun decide(
        kind: CameraFailureKind,
        attempts: RecoveryAttempts = RecoveryAttempts()
    ): RecoveryAction = RecoveryPolicy.decide(CameraFailure(kind), attempts, isScreenVisible = true)
}
