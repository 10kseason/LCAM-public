package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureCompensationSpecTest {
    @Test
    fun `a one third stop device quantizes onto its own grid`() {
        val spec = thirdStop()

        assertEquals(-3, spec.quantize(-1.0))
        assertEquals(0, spec.quantize(0.0))
        assertEquals(3, spec.quantize(1.0))
    }

    @Test
    fun `a one sixth stop device uses twice as many steps for the same EV`() {
        val spec = ExposureCompensationSpec(-40, 40, 1, 6)

        assertEquals(-6, spec.quantize(-1.0))
        assertEquals(6, spec.quantize(1.0))
        assertEquals(-1.0, spec.evForStep(-6), EPSILON)
    }

    @Test
    fun `a request between steps snaps to the nearest step`() {
        val spec = thirdStop()

        assertEquals(1, spec.quantize(0.3))
        assertEquals(2, spec.quantize(0.6))
        assertEquals(-1, spec.quantize(-0.3))
    }

    @Test
    fun `a request beyond the advertised range is clamped`() {
        val spec = thirdStop()

        assertEquals(20, spec.quantize(100.0))
        assertEquals(-20, spec.quantize(-100.0))
    }

    @Test
    fun `the advertised EV bounds follow the range and step`() {
        val spec = thirdStop()

        assertTrue(spec.isSupported)
        assertEquals(-20.0 / 3.0, spec.minEv, EPSILON)
        assertEquals(20.0 / 3.0, spec.maxEv, EPSILON)
        assertEquals(1.0 / 3.0, spec.stepEv, EPSILON)
    }

    @Test
    fun `evForStep clamps instead of reporting an EV the device cannot reach`() {
        val spec = thirdStop()

        assertEquals(spec.maxEv, spec.evForStep(999), EPSILON)
        assertEquals(spec.minEv, spec.evForStep(-999), EPSILON)
    }

    @Test
    fun `a device without EV control reports unsupported and neutral values`() {
        val spec = ExposureCompensationSpec.UNSUPPORTED

        assertFalse(spec.isSupported)
        assertEquals(0, spec.quantize(-1.0))
        assertEquals(0.0, spec.minEv, EPSILON)
        assertEquals(0.0, spec.maxEv, EPSILON)
        assertEquals(0.0, spec.evForStep(5), EPSILON)
    }

    @Test
    fun `a zero denominator is treated as unsupported rather than dividing by zero`() {
        val spec = ExposureCompensationSpec(-6, 6, 1, 0)

        assertFalse(spec.isSupported)
        assertEquals(0, spec.quantize(1.0))
        assertEquals(0.0, spec.stepEv, EPSILON)
    }

    @Test
    fun `a non finite request is treated as neutral`() {
        val spec = thirdStop()

        assertEquals(0, spec.quantize(Double.NaN))
        assertEquals(0, spec.quantize(Double.POSITIVE_INFINITY))
        assertEquals(0, spec.quantize(Double.NEGATIVE_INFINITY))
    }

    private fun thirdStop() = ExposureCompensationSpec(-20, 20, 1, 3)

    private companion object {
        const val EPSILON = 1e-9
    }
}
