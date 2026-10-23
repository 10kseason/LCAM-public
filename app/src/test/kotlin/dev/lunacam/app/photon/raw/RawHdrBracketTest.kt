package dev.lunacam.app.photon.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHdrBracketTest {
    @Test fun keepsMeteredFallbackFirstAndVariesOnlyShutter() {
        assertEquals(
            listOf(10_000_000L, 2_500_000L, 40_000_000L),
            rawHdrBracket(10_000_000, 100_000, 1_000_000_000)
        )
    }

    @Test fun capsLongFrameAtEightyMillisecondsAndSensorMaximum() {
        assertEquals(
            listOf(30_000_000L, 7_500_000L, 80_000_000L),
            rawHdrBracket(30_000_000, 100_000, 1_000_000_000)
        )
        assertEquals(
            listOf(10_000_000L, 2_500_000L, 20_000_000L),
            rawHdrBracket(10_000_000, 100_000, 20_000_000)
        )
        assertEquals(
            listOf(10_000_000L, 4_000_000L, 40_000_000L),
            rawHdrBracket(10_000_000, 4_000_000, 100_000_000)
        )
    }

    @Test fun insufficientRealExposureSeparationFallsBack() {
        assertNull(rawHdrBracket(1_000_000, 600_000, 80_000_000))
        assertNull(rawHdrBracket(50_000_000, 100_000, 1_000_000_000))
        assertNull(rawHdrBracket(10_000_000, 100_000, 17_999_999))
        assertEquals(
            listOf(10_000_000L, 2_500_000L, 18_000_000L),
            rawHdrBracket(10_000_000, 100_000, 18_000_000)
        )
    }

    @Test fun invalidRangesAndLongOverflowNeverGenerateCaptureRequests() {
        assertNull(rawHdrBracket(0, 1, 100))
        assertNull(rawHdrBracket(10, 0, 100))
        assertNull(rawHdrBracket(10, 20, 100))
        assertNull(rawHdrBracket(10, 1, 9))
        assertNull(rawHdrBracket(10, 100, 1))
        assertNull(rawHdrBracket(Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertNull(rawHdrBracket(Long.MAX_VALUE / 2, 1, Long.MAX_VALUE))
    }

    @Test fun acceptedBracketsStayWithinSensorAndBoundedRatio() {
        for (baseline in 1_000_000L..80_000_000L step 317_113L) {
            for (minimum in listOf(1L, 100_000L, 2_000_000L)) {
                val bracket = rawHdrBracket(baseline, minimum, 1_000_000_000) ?: continue
                assertEquals(baseline, bracket[0])
                assertTrue(bracket.all { it in minimum..80_000_000L })
                assertTrue(bracket[1] < baseline && baseline < bracket[2])
                assertTrue(bracket[2].toDouble() / bracket[1] <= 16.001)
            }
        }
    }
}
