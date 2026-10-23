package dev.lunacam.app.photon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturePreviewHistogramTest {
    @Test fun grayscaleRampSpansEveryBin() {
        val pixels = IntArray(256) { -0x1000000 or (it shl 16) or (it shl 8) or it }
        val histogram = requireNotNull(textureRgbHistogram(pixels))
        assertEquals(256, histogram.samples)
        assertTrue(histogram.bins.all { it == 4 })
    }

    @Test fun weightedRgbUsesDisplayLumaAndIgnoresFailedCopyPixels() {
        val histogram = requireNotNull(
            textureRgbHistogram(
                intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0)
            )
        )
        assertEquals(3, histogram.samples)
        assertEquals(1, histogram.bins[77 / 4])
        assertEquals(1, histogram.bins[149 / 4])
        assertEquals(1, histogram.bins[29 / 4])
        assertNull(textureRgbHistogram(intArrayOf(0, 0x7fffffff)))
        assertNull(textureRgbHistogram(intArrayOf()))
    }

    @Test fun samplingRemainsBoundedForUnexpectedLargeInputs() {
        val histogram = requireNotNull(textureRgbHistogram(IntArray(100_000) { -1 }))
        assertTrue(histogram.samples <= 19_200)
        assertEquals(histogram.samples, histogram.bins[63])
        assertEquals(histogram.samples, histogram.bins.sum())
    }
}
