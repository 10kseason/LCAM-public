package dev.lunacam.app.photon

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureGuardTest {
    @Test fun flatFieldsAndGrayscaleTextureStayBitExact() {
        for (color in listOf(rgb(0, 0, 0), rgb(128, 100, 80), rgb(255, 255, 255), rgb(255, 0, 0))) {
            val image = IntArray(32 * 32) { color }
            assertArrayEquals(image, process(image, 32, 32).first)
        }
        val fineTexture = IntArray(32 * 32) { i ->
            val v = if ((i + i / 32) % 2 ==
                0
            ) {
                121
            } else {
                129
            }
            rgb(v, v, v)
        }
        assertArrayEquals(fineTexture, process(fineTexture, 32, 32).first)
    }

    @Test fun flatFieldChromaErrorDropsWithoutChangingLuma() {
        val random = Random(73)
        val image = IntArray(128 * 128) {
            val co = random.nextInt(-6, 7)
            val cg = random.nextInt(-3, 4)
            rgb(128 + co - cg, 128 + cg, 128 - co - cg)
        }
        val (result, stats) = process(image, 128, 128)
        val before = error(image, 128)
        val after = error(result, 128)
        assertTrue("before=$before after=$after", after < before * 0.95)
        assertTrue(stats.changedPixels > 0)
        for (i in image.indices) {
            assertEquals(lumaNumerator(image[i]), lumaNumerator(result[i]))
            for (shift in listOf(0, 8, 16)) {
                assertTrue(abs(channel(image[i], shift) - channel(result[i], shift)) <= 3)
            }
        }
        println("Synthetic chroma MSE ratio=${after / before}; changed=${stats.changedPixels}")
    }

    @Test fun colorEdgesAndFineStrongChromaPatternArePreserved() {
        val image = IntArray(32 * 32) { i ->
            if ((i + i / 32) % 2 == 0) rgb(160, 128, 96) else rgb(96, 128, 160)
        }
        assertArrayEquals(image, process(image, 32, 32).first)
        val edge = IntArray(32 * 32) { i ->
            if (i % 32 <
                16
            ) {
                rgb(160, 128, 96)
            } else {
                rgb(96, 128, 160)
            }
        }
        assertArrayEquals(edge, process(edge, 32, 32).first)
    }

    @Test fun rowStreamingIsIndependentOfOutputWriteOrderAndPreservesBorders() {
        val random = Random(95)
        val image =
            IntArray(31 * 25) { rgb(120 + random.nextInt(10), 120, 120 + random.nextInt(10)) }
        val expected = process(image, 31, 25).first
        val inPlace = image.clone()
        TextureGuard.process(
            31,
            25,
            { y, row -> inPlace.copyInto(row, 0, y * 31, (y + 1) * 31) },
            { y, row -> row.copyInto(inPlace, y * 31) }
        )
        assertArrayEquals(expected, inPlace)
        for (x in 0..30) {
            assertEquals(image[x], inPlace[x])
            assertEquals(image[24 * 31 + x], inPlace[24 * 31 + x])
        }
        for (y in 0..24) {
            assertEquals(image[y * 31], inPlace[y * 31])
            assertEquals(image[y * 31 + 30], inPlace[y * 31 + 30])
        }
    }

    @Test fun smallInputsAreNoOpAndCheckpointsCanAbort() {
        val image = intArrayOf(rgb(128, 100, 80), rgb(120, 100, 80))
        assertArrayEquals(image, process(image, 2, 1).first)
        var aborted = false
        try {
            TextureGuard.process(
                10,
                10,
                { _, row -> row.fill(rgb(120, 120, 120)) },
                { _, _ -> },
                { throw IllegalStateException("cancelled") }
            )
        } catch (_: IllegalStateException) {
            aborted = true
        }
        assertTrue(aborted)
    }

    private fun process(
        image: IntArray,
        width: Int,
        height: Int
    ): Pair<IntArray, TextureGuardStats> {
        val output = image.clone()
        val stats = TextureGuard.process(
            width,
            height,
            { y, row -> image.copyInto(row, 0, y * width, (y + 1) * width) },
            { y, row -> row.copyInto(output, y * width) }
        )
        return output to stats
    }

    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    private fun channel(pixel: Int, shift: Int) = (pixel ushr shift) and 255
    private fun lumaNumerator(pixel: Int) =
        channel(pixel, 16) + 2 * channel(pixel, 8) + channel(pixel, 0)
    private fun error(image: IntArray, target: Int) = image.sumOf { pixel ->
        listOf(0, 8, 16).sumOf { shift ->
            (channel(pixel, shift) - target).toDouble().let { it * it }
        }
    } / (image.size * 3)
}
