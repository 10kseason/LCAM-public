package dev.lunacam.app.photon

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonHistogramTest {
    @Test fun unsignedRampUsesAllBinsWithoutMovingBuffer() {
        val buffer = ByteBuffer.wrap(ByteArray(256) { it.toByte() })
        val result = requireNotNull(previewHistogram(buffer, 16, 1, PhotonCrop(0, 0, 16, 16)))
        assertEquals(256, result.samples)
        assertTrue(result.bins.all { it == 4 })
        assertEquals(0, buffer.position())
    }

    @Test fun cropPaddingPixelStrideAndBufferOffsetExcludeUnseenPixels() {
        val buffer = ByteBuffer.allocate(3 + 8 * 4)
        for (index in 0 until buffer.capacity()) buffer.put(index, 255.toByte())
        for (y in 1..2) for (x in 1..2) buffer.put(3 + y * 8 + x * 2, 32)
        buffer.position(3)
        val result = requireNotNull(previewHistogram(buffer, 8, 2, PhotonCrop(1, 1, 2, 2)))
        assertEquals(4, result.samples)
        assertEquals(4, result.bins[8])
        assertEquals(0, result.bins[63])
        assertEquals(3, buffer.position())
    }

    @Test fun malformedPlanesAreRejectedWithoutReadingOutsideBuffer() {
        val buffer = ByteBuffer.allocate(16)
        assertNull(previewHistogram(buffer, 4, 1, PhotonCrop(0, 0, 4, 5)))
        assertNull(previewHistogram(buffer, 4, 2, PhotonCrop(0, 0, 4, 4)))
        assertNull(previewHistogram(buffer, 0, 1, PhotonCrop(0, 0, 4, 4)))
        assertNull(previewHistogram(buffer, 4, 1, PhotonCrop(-1, 0, 4, 4)))
        assertNull(previewHistogram(buffer, Int.MAX_VALUE, 2, PhotonCrop(Int.MAX_VALUE, 0, 4, 4)))
        buffer.limit(15)
        assertNull(previewHistogram(buffer, 4, 1, PhotonCrop(0, 0, 4, 4)))
    }

    @Test fun highResolutionInputHasBoundedSampling() {
        val width = 4080
        val height = 3060
        val result = requireNotNull(
            previewHistogram(
                ByteBuffer.allocate(width * height),
                width,
                1,
                PhotonCrop(0, 0, width, height)
            )
        )
        assertTrue(result.samples <= 160 * 120)
        assertEquals(result.samples, result.bins[0])
        assertEquals(result.samples, result.bins.sum())
    }
}
