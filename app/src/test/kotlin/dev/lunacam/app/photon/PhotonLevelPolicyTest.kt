package dev.lunacam.app.photon

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonLevelPolicyTest {
    @Test
    fun levelTracksAllDisplayRotationsAndUpsideDown() {
        assertAngle(0f, 0f, 9.81f, 0)
        assertAngle(0f, 9.81f, 0f, 90)
        assertAngle(0f, 0f, -9.81f, 180)
        assertAngle(0f, -9.81f, 0f, 270)
        assertAngle(0f, 0f, -9.81f, 0)
        assertAngle(45f, 6.9367f, 6.9367f, 0)
        assertAngle(-45f, 6.9367f, 6.9367f, 90)
    }

    @Test
    fun flatInvalidAndStrongAccelerationAreUnavailable() {
        assertNull(PhotonLevelPolicy.horizonDegrees(0f, 0f, 9.81f, 0))
        assertNull(PhotonLevelPolicy.horizonDegrees(1f, 1f, -9.7f, 270))
        assertNull(PhotonLevelPolicy.horizonDegrees(Float.NaN, 9.81f, 0f, 0))
        assertNull(PhotonLevelPolicy.horizonDegrees(0f, Float.POSITIVE_INFINITY, 0f, 0))
        assertNull(PhotonLevelPolicy.horizonDegrees(0f, 20f, 0f, 0))
        assertNull(PhotonLevelPolicy.horizonDegrees(0f, 3f, 0f, 0))
        assertNull(PhotonLevelPolicy.horizonDegrees(0f, 9.81f, 0f, 45))
    }

    @Test
    fun smoothingTakesShortPathAcrossWrapAndBoundsElapsedTime() {
        val wrapped = requireNotNull(PhotonLevelPolicy.smooth(89f, -89f, 100))
        assertTrue(abs(wrapped) >= 89f)
        assertEquals(12f, requireNotNull(PhotonLevelPolicy.smooth(12f, 40f, -1)), 0f)
        assertEquals(
            requireNotNull(PhotonLevelPolicy.smooth(0f, 20f, 1_000)),
            requireNotNull(PhotonLevelPolicy.smooth(0f, 20f, Long.MAX_VALUE)),
            0f
        )
        assertEquals(30f, requireNotNull(PhotonLevelPolicy.smooth(null, 30f, 100)), 0f)
        assertNull(PhotonLevelPolicy.smooth(0f, Float.NaN, 100))
    }

    private fun assertAngle(expected: Float, x: Float, y: Float, display: Int) {
        val actual = requireNotNull(PhotonLevelPolicy.horizonDegrees(x, y, 0f, display))
        assertEquals(expected, actual, 0.01f)
        assertTrue(actual >= -90f && actual <= 90f)
    }
}
