package dev.lunacam.app.photon.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawControlsTest {
    private val supported = RawControlCapabilities(
        100_000, 1_000_000_000, 50, 3200, 10f, -6, 6, 1f / 3,
        listOf(RawWhiteBalance.AUTO, RawWhiteBalance.DAYLIGHT), true, true
    )

    @Test fun ignoredAndMismatchedSettingsCannotMarkControlsReady() {
        val request =
            RawControls(true, 10_000_000, 400, 0f, whiteBalance = RawWhiteBalance.DAYLIGHT)
        val actual = RawControlObservation(10_000_000, 400, 0f, 0, 0, 5, 0, 0)
        assertTrue(actual.matches(request, supported))
        assertTrue(actual.copy(exposureNs = 10_010_000, iso = 398).matches(request, supported))
        assertFalse(actual.copy(aeMode = 1).matches(request, supported))
        assertFalse(actual.copy(exposureNs = 1_000_000).matches(request, supported))
        assertFalse(actual.copy(iso = 100).matches(request, supported))
        assertFalse(actual.copy(awbMode = 1).matches(request, supported))
        assertFalse(actual.copy(focusDiopters = Float.NaN).matches(request, supported))
        assertFalse(actual.copy(afMode = 1).matches(request, supported))
        assertFalse(
            actual.copy(
                aeMode = 1,
                awbMode = 1,
                evIndex = 0
            ).matches(RawControls(evIndex = 3), supported)
        )
    }

    @Test fun unsupportedLensResetsEveryManualControl() {
        val value = RawControls(true, 1, 1, 8f, 9, RawWhiteBalance.DAYLIGHT)
            .bounded(RawControlCapabilities())
        assertFalse(value.manualExposure)
        assertNull(value.focusDiopters)
        assertEquals(0, value.evIndex)
        assertEquals(RawWhiteBalance.AUTO, value.whiteBalance)
    }

    @Test fun sensorBoundsAndInvalidFocusAreRespected() {
        val value = RawControls(
            true,
            Long.MAX_VALUE,
            -1,
            100f,
            9,
            RawWhiteBalance.SHADE
        ).bounded(supported)
        assertTrue(value.manualExposure)
        assertEquals(1_000_000_000, value.exposureNs)
        assertEquals(50, value.iso)
        assertEquals(10f, value.focusDiopters)
        assertEquals(6, value.evIndex)
        assertEquals(RawWhiteBalance.AUTO, value.whiteBalance)
        assertNull(RawControls(focusDiopters = Float.NaN).bounded(supported).focusDiopters)
        assertNull(
            RawControls(focusDiopters = Float.POSITIVE_INFINITY).bounded(supported).focusDiopters
        )
    }

    @Test fun logarithmicSlidersPreserveBoundsAndRoundTrip() {
        assertEquals(100L, rawLogValue(Float.NaN, 100, 1_000_000))
        assertEquals(100L, rawLogValue(-1f, 100, 1_000_000))
        assertEquals(1_000_000L, rawLogValue(2f, 100, 1_000_000))
        assertEquals(100L, rawLogValue(.7f, 100, 100))
        for (index in 0..100) {
            val fraction = index / 100f
            val value = rawLogValue(fraction, 100_000, 1_000_000_000)
            assertEquals(fraction, rawLogFraction(value, 100_000, 1_000_000_000), .0001f)
        }
    }

    @Test fun meteringCenterRemainsCenteredAcrossRotationsAndFacing() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                for (front in listOf(false, true)) {
                    val p =
                        requireNotNull(
                            rawMeteringPoint(50f, 100f, 100, 200, 400, 300, sensor, display, front)
                        )
                    assertEquals(.5f, p.first, .0001f)
                    assertEquals(.5f, p.second, .0001f)
                }
            }
        }
    }

    @Test fun meteringCornersAccountForRotationMirrorAndCenterCrop() {
        fun point(sensor: Int, front: Boolean, vw: Int, vh: Int) =
            requireNotNull(rawMeteringPoint(0f, 0f, vw, vh, 400, 300, sensor, 0, front))
        assertEquals(0f to 0f, point(0, false, 400, 300))
        assertEquals(0f to 1f, point(90, false, 300, 400))
        assertEquals(1f to 1f, point(180, false, 400, 300))
        assertEquals(1f to 0f, point(270, false, 300, 400))
        assertEquals(1f to 0f, point(0, true, 400, 300))
        assertEquals(0f to 0f, point(90, true, 300, 400))
        val cropped = point(0, false, 100, 100)
        assertEquals(.125f, cropped.first, .0001f)
        assertEquals(0f, cropped.second, .0001f)
    }

    @Test fun invalidAndOutsideTouchesAreRejected() {
        assertNull(rawMeteringPoint(Float.NaN, 0f, 100, 100, 400, 300, 90, 0, false))
        assertNull(rawMeteringPoint(-1f, 0f, 100, 100, 400, 300, 90, 0, false))
        assertNull(rawMeteringPoint(0f, 101f, 100, 100, 400, 300, 90, 0, false))
        assertNull(rawMeteringPoint(0f, 0f, 0, 100, 400, 300, 90, 0, false))
    }
}
