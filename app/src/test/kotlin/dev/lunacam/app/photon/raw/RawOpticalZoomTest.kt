package dev.lunacam.app.photon.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawOpticalZoomTest {
    @Test
    fun derivesOpticalRatiosFromFocalLengthAndSensorWidth() {
        assertEquals(1f, requireNotNull(RawOpticalZoom.ratio(6.3f, 9.792f, 6.3f, 9.792f)), 0f)
        assertEquals(
            0.599f,
            requireNotNull(RawOpticalZoom.ratio(2.2f, 5.712f, 6.3f, 9.792f)),
            0.002f
        )
        assertEquals(
            2.741f,
            requireNotNull(RawOpticalZoom.ratio(7.9f, 4.48f, 6.3f, 9.792f)),
            0.002f
        )
        assertNull(RawOpticalZoom.ratio(1f, 0f, 1f, 1f))
        assertNull(RawOpticalZoom.ratio(Float.NaN, 1f, 1f, 1f))
    }

    @Test
    fun hysteresisPreventsRepeatedSwitchingAtTheSameBoundary() {
        val ratios = listOf(0.6f, 1f, 2.7f, 5.1f)
        assertEquals(1, RawOpticalZoom.select(ratios, 1, 1.7f))
        assertEquals(2, RawOpticalZoom.select(ratios, 1, 1.8f))
        assertEquals(2, RawOpticalZoom.select(ratios, 2, 1.7f))
        assertEquals(1, RawOpticalZoom.select(ratios, 2, 1.5f))
        assertEquals(3, RawOpticalZoom.select(ratios, 0, 100f))
        assertEquals(0, RawOpticalZoom.select(ratios, 3, 0.1f))
        assertEquals(1, RawOpticalZoom.select(ratios, 1, Float.NaN))
    }
}
