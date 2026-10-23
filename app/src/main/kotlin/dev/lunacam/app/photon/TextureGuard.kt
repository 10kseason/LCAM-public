package dev.lunacam.app.photon

import kotlin.math.abs
import kotlin.math.roundToInt

data class TextureGuardStats(val examinedPixels: Long, val changedPixels: Long)

/**
 * PHOTON Texture Guard v0.1: conservative, bounded chroma correction in encoded sRGB.
 * It keeps R + 2G + B exactly constant, adds no sharpening/tone curve, and rejects ambiguity.
 * This is a JPEG experiment, not a RAW noise model or a claim of recovered detail.
 */
object TextureGuard {
    const val VERSION = "texture-guard-0.1"

    /** Row streaming permits read/write on the same bitmap without feeding results back as input. */
    fun process(
        width: Int,
        height: Int,
        readRow: (Int, IntArray) -> Unit,
        writeRow: (Int, IntArray) -> Unit,
        checkpoint: () -> Unit = {}
    ): TextureGuardStats {
        require(width > 0 && height > 0)
        if (width < 3 || height < 3) return TextureGuardStats(0, 0)
        var above = IntArray(width)
        var center = IntArray(width)
        var below = IntArray(width)
        val output = IntArray(width)
        readRow(0, above)
        readRow(1, center)
        readRow(2, below)
        var changed = 0L
        for (y in 1 until height - 1) {
            checkpoint()
            center.copyInto(output)
            for (x in 1 until width - 1) {
                val filtered = filter(center[x], x, above, center, below)
                output[x] = filtered
                if (filtered != center[x]) changed++
            }
            writeRow(y, output)
            val recycled = above
            above = center
            center = below
            below = recycled
            if (y + 2 < height) readRow(y + 2, below)
        }
        return TextureGuardStats((width - 2).toLong() * (height - 2), changed)
    }

    private fun filter(
        pixel: Int,
        x: Int,
        above: IntArray,
        center: IntArray,
        below: IntArray
    ): Int {
        val r = red(pixel)
        val g = green(pixel)
        val b = blue(pixel)
        val y = (r + 2 * g + b) / 4f
        if (pixel ushr 24 != 255 || y < 16 || y > 239) return pixel
        val co = (r - b) / 2f
        val cg = g - y
        var minY = y
        var maxY = y
        var minCo = Float.POSITIVE_INFINITY
        var maxCo = Float.NEGATIVE_INFINITY
        var minCg = Float.POSITIVE_INFINITY
        var maxCg = Float.NEGATIVE_INFINITY
        var sumCo = 0f
        var sumCg = 0f
        var votes = 0
        // Opposite horizontal, vertical and diagonal neighbours vote on the center's chroma.
        for (direction in 0..3) {
            val a = when (direction) {
                0 -> center[x - 1]
                1 -> above[x]
                2 -> above[x - 1]
                else -> above[
                    x +
                        1
                ]
            }
            val z = when (direction) {
                0 -> center[x + 1]
                1 -> below[x]
                2 -> below[x + 1]
                else -> below[
                    x -
                        1
                ]
            }
            if (a ushr 24 != 255 || z ushr 24 != 255) return pixel
            val ay = luma(a)
            val zy = luma(z)
            minY = minOf(minY, ay, zy)
            maxY = maxOf(maxY, ay, zy)
            if (maxY - minY > 10f) return pixel
            val aCo = (red(a) - blue(a)) / 2f
            val zCo = (red(z) - blue(z)) / 2f
            val aCg = green(a) - ay
            val zCg = green(z) - zy
            if (abs(ay - zy) <= 6f && abs(aCo - zCo) <= 6f && abs(aCg - zCg) <= 6f) {
                val pairCo = (aCo + zCo) / 2f
                val pairCg = (aCg + zCg) / 2f
                minCo = minOf(minCo, pairCo)
                maxCo = maxOf(maxCo, pairCo)
                minCg = minOf(minCg, pairCg)
                maxCg = maxOf(maxCg, pairCg)
                sumCo += pairCo
                sumCg += pairCg
                votes++
            }
        }
        // Three directions must agree. Contradictory directional evidence keeps texture intact.
        if (votes < 3 || maxCo - minCo > 4f || maxCg - minCg > 3f) return pixel
        val residualCo = sumCo / votes - co
        val residualCg = sumCg / votes - cg
        if (abs(residualCo) > 12f || abs(residualCg) > 12f) return pixel
        val dc = (residualCo * 0.35f).roundToInt().coerceIn(-2, 2)
        val dg = (residualCg * 0.35f).roundToInt().coerceIn(-1, 1)
        // Coupled integer increments preserve luma exactly and bound every channel change to 3.
        val nr = r + dc - dg
        val ng = g + dg
        val nb = b - dc - dg
        if (nr !in 0..255 || ng !in 0..255 || nb !in 0..255) return pixel
        return (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    // Avoid hundreds of millions of tiny method calls in an unoptimized debug runtime.
    @Suppress("NOTHING_TO_INLINE")
    private inline fun red(pixel: Int) = (pixel ushr 16) and 255

    @Suppress("NOTHING_TO_INLINE")
    private inline fun green(pixel: Int) = (pixel ushr 8) and 255

    @Suppress("NOTHING_TO_INLINE")
    private inline fun blue(pixel: Int) = pixel and 255

    @Suppress("NOTHING_TO_INLINE")
    private inline fun luma(pixel: Int) = (red(pixel) + 2 * green(pixel) + blue(pixel)) / 4f
}
