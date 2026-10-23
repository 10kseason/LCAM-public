package dev.lunacam.app.photon.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightAcquisitionPolicyTest {
    @Test
    fun shortExposureKeepsMeteredIsoAndLongExposurePreservesProductWithinRange() {
        val short = NightAcquisitionPolicy.exposure(20_000_000, 100, 1_000, 1_000_000_000, 50, 6400)
        assertEquals(20_000_000L, short.exposureNs)
        assertEquals(100, short.iso)
        assertFalse(short.limited)
        val long = NightAcquisitionPolicy.exposure(500_000_000, 100, 1_000, 1_000_000_000, 50, 6400)
        assertEquals(50_000_000L, long.exposureNs)
        assertEquals(1000, long.iso)
        assertFalse(long.limited)
    }

    @Test
    fun isoCeilingIsHonestAndHugeMetadataDoesNotOverflow() {
        val clipped = NightAcquisitionPolicy.exposure(
            500_000_000,
            3200,
            1_000,
            1_000_000_000,
            50,
            6400
        )
        assertEquals(50_000_000L, clipped.exposureNs)
        assertEquals(6400, clipped.iso)
        assertTrue(clipped.limited)
        val huge = NightAcquisitionPolicy.exposure(
            Long.MAX_VALUE,
            Int.MAX_VALUE,
            1,
            Long.MAX_VALUE,
            1,
            6400
        )
        assertEquals(6400, huge.iso)
        assertTrue(huge.limited)
        assertEquals(50_000_000L, huge.exposureNs)
    }

    @Test
    fun physicalRangesAndFiftyMillisecondCapBothApply() {
        val lower = NightAcquisitionPolicy.exposure(500, 100, 1_000, 30_000_000, 100, 3200)
        assertEquals(1_000L, lower.exposureNs)
        assertEquals(100, lower.iso)
        val upper = NightAcquisitionPolicy.exposure(
            60_000_000,
            100,
            1_000,
            30_000_000,
            50,
            3200
        )
        assertEquals(30_000_000L, upper.exposureNs)
        assertEquals(200, upper.iso)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rangeAboveTheExposureCapIsUnsupported() {
        NightAcquisitionPolicy.exposure(60_000_000, 100, 60_000_000, 1_000_000_000, 50, 3200)
    }

    @Test
    fun choosesSingleBeforeSubmissionForOldPreviewOrOverBudgetFrames() {
        assertEquals(3, NightAcquisitionPolicy.frameCount(3, 80, 50_000_000, 10))
        assertEquals(1, NightAcquisitionPolicy.frameCount(3, 101, 50_000_000, 10))
        assertEquals(1, NightAcquisitionPolicy.frameCount(3, 80, 200_000_000, 10))
        assertEquals(1, NightAcquisitionPolicy.frameCount(3, -1, 50_000_000, 10))
        assertEquals(1, NightAcquisitionPolicy.frameCount(3, 0, Long.MAX_VALUE, 10))
        assertEquals(1, NightAcquisitionPolicy.frameCount(1, 0, 50_000_000, 10))
        for (frameMs in 1L..500L) {
            if (NightAcquisitionPolicy.frameCount(3, 50, frameMs * 1_000_000, 20) == 3) {
                assertTrue(20 + 3 * (frameMs + 90) + 300 <= 1000)
            }
        }
    }
}
