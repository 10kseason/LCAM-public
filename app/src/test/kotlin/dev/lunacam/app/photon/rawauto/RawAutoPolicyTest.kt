package dev.lunacam.app.photon.rawauto

import dev.lunacam.app.photon.raw.RawControlCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawAutoPolicyTest {
    @Test fun biasUsesSupportedStepsAndBounds() {
        val support = RawControlCapabilities(minEvIndex = -6, maxEvIndex = 6, evStep = 1f / 3f)
        assertEquals(-2, rawAutoEvIndex(support))
        assertEquals(-1, rawAutoEvIndex(support.copy(minEvIndex = -1)))
        assertEquals(0, rawAutoEvIndex(RawControlCapabilities()))
    }

    @Test fun compensationUsesOnlyTheFixedProtectiveBias() {
        assertEquals(1f, rawAutoExposureGain(0f), 0f)
        assertEquals(2f, rawAutoExposureGain(-1f), 0f)
        assertEquals(4f, rawAutoExposureGain(-2f), 0f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, -3f).forEach {
            assertTrue(runCatching { rawAutoExposureGain(it) }.isFailure)
        }
    }

    @Test fun fullUserEvRangeRemainsRenderableAndDoesNotCancelBrightnessChanges() {
        val support = RawControlCapabilities(minEvIndex = -18, maxEvIndex = 18, evStep = 1f / 3f)
        val bias = rawAutoCompensationBias(support)
        assertEquals(-2f / 3f, bias, .00001f)
        val baseline = rawAutoTone(bias, .35f, null, null, 1f, bias)
        assertEquals(rawAutoExposureGain(bias), baseline.exposureGain, .00001f)
        for (index in support.minEvIndex..support.maxEvIndex) {
            val tone = rawAutoTone(index * support.evStep, .35f, null, null, 1f, bias)
            // A -1 EV captured signal must stay one stop below a 0 EV signal. A fixed gain
            // preserves that ratio; compensating the measured user EV would erase it.
            assertEquals(baseline, tone)
        }
        assertEquals(0f, rawAutoCompensationBias(RawControlCapabilities(evStep = Float.NaN)), 0f)
    }

    @Test fun absentOrMalformedNoiseCannotEnableStrongerLift() {
        val fallback = rawAutoTone(0f, .6f, null, null, 1f)
        assertEquals(.35f, fallback.strength, 0f)
        assertEquals(.01f, fallback.noiseFloor, 0f)
        val invalid = rawAutoTone(0f, .6f, DoubleArray(4) { Double.NaN }, DoubleArray(4), 1f)
        assertEquals(fallback, invalid)
    }

    @Test fun noisierDataAndColorAmplificationIncreaseFloor() {
        fun tone(slope: Double, color: Float) = rawAutoTone(
            -1f,
            .6f,
            DoubleArray(4) { slope },
            DoubleArray(4) { .000001 },
            color
        )
        val low = tone(.0001, 1f)
        val high = tone(.01, 1f)
        assertEquals(.6f, low.strength, 0f)
        assertTrue(high.noiseFloor > low.noiseFloor)
        assertTrue(tone(.0001, 2f).noiseFloor > low.noiseFloor)
        assertTrue(tone(.1, 100f).noiseFloor <= .1f)
    }

    @Test fun invalidStrengthAndColorBoundsAreRejected() {
        assertTrue(runCatching { rawAutoTone(0f, Float.NaN, null, null, 1f) }.isFailure)
        assertTrue(runCatching { rawAutoTone(0f, .8f, null, null, 1f) }.isFailure)
        assertTrue(runCatching { rawAutoTone(0f, .35f, null, null, Float.NaN) }.isFailure)
    }
}
