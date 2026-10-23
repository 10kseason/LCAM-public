package dev.lunacam.app.photon.raw

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewTransformTest {
    @Test
    fun uprightPortraitBufferIsNotRotatedOrMirroredAgain() {
        for (sensor in listOf(90, 270)) {
            val matrix = RawPreviewTransform.matrixValues(1080, 1920, 1920, 1080, sensor, 0)
            assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), matrix, 0.0001f)
        }
    }

    @Test
    fun landscapeNaturalSensorRemainsUprightWithoutAnAxisSwap() {
        val matrix = RawPreviewTransform.matrixValues(1920, 1080, 1920, 1080, 0, 0)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), matrix, 0.0001f)
    }

    @Test
    fun displayRotationOnlyTurnsByTheOppositeQuarterTurn() {
        val expectedX = listOf(1f to 0f, 0f to -1f, -1f to 0f, 0f to 1f)
        for (quarterTurn in 0..3) {
            val matrix = RawPreviewTransform.matrixValues(
                1000,
                1000,
                1920,
                1080,
                90,
                quarterTurn * 90
            )
            val origin = map(matrix, 500f, 500f)
            val x = map(matrix, 501f, 500f)
            val dx = x.first - origin.first
            val dy = x.second - origin.second
            val length = hypot(dx, dy)
            assertEquals(expectedX[quarterTurn].first, dx / length, 0.0001f)
            assertEquals(expectedX[quarterTurn].second, dy / length, 0.0001f)
        }
    }

    @Test
    fun squaresStaySquareCenteredAndCoverEveryViewport() {
        val views = listOf(1080 to 1200, 1920 to 600, 1000 to 1000, 480 to 1000)
        for (sensor in listOf(0, 90, 180, 270)) {
            for (rotation in listOf(0, 90, 180, 270)) {
                for ((bufferWidth, bufferHeight) in listOf(1920 to 1080, 1280 to 960)) {
                    for ((width, height) in views) {
                        val matrix = RawPreviewTransform.matrixValues(
                            width,
                            height,
                            bufferWidth,
                            bufferHeight,
                            sensor,
                            rotation
                        )
                        val naturalWidth = if (sensor % 180 == 0) bufferWidth else bufferHeight
                        val naturalHeight = if (sensor % 180 == 0) bufferHeight else bufferWidth
                        val center = map(matrix, width / 2f, height / 2f)
                        assertEquals(width / 2f, center.first, 0.001f)
                        assertEquals(height / 2f, center.second, 0.001f)
                        // A ten-pixel square after TextureView's default nonuniform stretch.
                        val x = map(matrix, width / 2f + 10f * width / naturalWidth, height / 2f)
                        val y = map(matrix, width / 2f, height / 2f + 10f * height / naturalHeight)
                        val dx = x.first - center.first
                        val dy = x.second - center.second
                        val ex = y.first - center.first
                        val ey = y.second - center.second
                        assertEquals(hypot(dx, dy), hypot(ex, ey), 0.001f)
                        assertEquals(0f, dx * ex + dy * ey, 0.001f)
                        assertTrue("Do not mirror the stream again", dx * ey - dy * ex > 0)
                        val corners = listOf(
                            map(matrix, 0f, 0f),
                            map(matrix, width.toFloat(), 0f),
                            map(matrix, 0f, height.toFloat()),
                            map(matrix, width.toFloat(), height.toFloat())
                        )
                        assertTrue(corners.minOf { it.first } <= 0.001f)
                        assertTrue(corners.minOf { it.second } <= 0.001f)
                        assertTrue(corners.maxOf { it.first } >= width - 0.001f)
                        assertTrue(corners.maxOf { it.second } >= height - 0.001f)
                        assertTrue(abs(matrix[6]) + abs(matrix[7]) == 0f)
                    }
                }
            }
        }
    }

    private fun map(matrix: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (matrix[0] * x + matrix[1] * y + matrix[2]) to
            (matrix[3] * x + matrix[4] * y + matrix[5])
}
