package dev.lunacam.app.photon.raw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPreviewHoldPolicyTest {
    private fun calibration() = NightPreviewHoldPolicy.Calibration(
        1.2f,
        floatArrayOf(1.8f, 1f, 1f, 1.6f),
        floatArrayOf(1.2f, -0.1f, -0.1f, -0.2f, 1.3f, -0.1f, 0f, -0.3f, 1.3f)
    )

    @Test
    fun windowExpiresAgainstOriginalStartIncludingRepeatedShots() {
        assertTrue(NightPreviewHoldPolicy.active(1000, 1000))
        assertTrue(NightPreviewHoldPolicy.active(1000, 1500))
        assertTrue(NightPreviewHoldPolicy.active(1000, 2999))
        assertFalse(NightPreviewHoldPolicy.active(1000, 3000))
        assertFalse(NightPreviewHoldPolicy.active(1000, 100_000))
        assertFalse(NightPreviewHoldPolicy.active(1000, 999))
        assertFalse(NightPreviewHoldPolicy.active(-1, 0))
    }

    @Test
    fun freshTaggedPostReferenceResultIsRequired() {
        assertTrue(NightPreviewHoldPolicy.usable(1000, 1500, 50, true, true))
        assertFalse(NightPreviewHoldPolicy.usable(1000, 1500, 251, true, true))
        assertFalse(NightPreviewHoldPolicy.usable(1000, 1500, -1, true, true))
        assertFalse(NightPreviewHoldPolicy.usable(1000, 1500, 50, false, true))
        assertFalse(NightPreviewHoldPolicy.usable(1000, 1500, 50, true, false))
        assertFalse(NightPreviewHoldPolicy.usable(1000, 3000, 50, true, true))
    }

    @Test
    fun actualFocusAndColorMustMatchWithinSmallMetadataQuantizationTolerance() {
        val expected = calibration()
        assertTrue(NightPreviewHoldPolicy.matches(expected, calibration()))
        assertTrue(NightPreviewHoldPolicy.matches(expected, expected.copy(focus = 1.205f)))
        assertFalse(NightPreviewHoldPolicy.matches(expected, expected.copy(focus = 1.3f)))
        assertFalse(NightPreviewHoldPolicy.matches(expected, expected.copy(focus = Float.NaN)))
        assertFalse(
            NightPreviewHoldPolicy.matches(expected, expected.copy(gains = floatArrayOf(1f)))
        )
        val differentColor = calibration().apply { gains[0] = 2.2f }
        assertFalse(NightPreviewHoldPolicy.matches(expected, differentColor))
        val invalidColor = calibration().apply { gains[2] = 0f }
        assertFalse(NightPreviewHoldPolicy.matches(expected, invalidColor))
        val differentTransform = calibration().apply { transform[3] = -0.3f }
        assertFalse(NightPreviewHoldPolicy.matches(expected, differentTransform))
        val invalidTransform = calibration().apply { transform[8] = Float.POSITIVE_INFINITY }
        assertFalse(NightPreviewHoldPolicy.matches(expected, invalidTransform))
    }
}
