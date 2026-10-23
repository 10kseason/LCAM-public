package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.NativeTextureGuard
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTextureDeviceTest {
    @Test fun nativeAndKotlinPixelsAndJpegAreIdentical() {
        val random = Random(8506)
        for (pattern in 0..7) {
            val width = if (pattern == 0) 2 else 193
            val height = if (pattern == 0) 5 else 127
            val pixels = IntArray(width * height) { index ->
                val y = when (pattern) {
                    1 -> 128
                    2 -> 16
                    3 -> 239
                    4 -> if (index % width < width / 2) 70 else 170
                    else -> random.nextInt(12, 244)
                }
                val co = random.nextInt(-6, 7)
                val cg = random.nextInt(-3, 4)
                (255 shl 24) or ((y + co - cg) shl 16) or ((y + cg) shl 8) or (y - co - cg)
            }
            val reference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            reference.setPixels(pixels, 0, width, 0, 0, width, height)
            val actual = reference.copy(Bitmap.Config.ARGB_8888, true)
            try {
                val stats = TextureGuard.process(
                    width,
                    height,
                    { y, row -> reference.getPixels(row, 0, width, 0, y, width, 1) },
                    { y, row -> reference.setPixels(row, 0, width, 0, y, width, 1) }
                )
                assertEquals(stats.changedPixels, NativeTextureGuard.process(actual))
                val expectedPixels = IntArray(width * height)
                val actualPixels = IntArray(width * height)
                reference.getPixels(expectedPixels, 0, width, 0, 0, width, height)
                actual.getPixels(actualPixels, 0, width, 0, 0, width, height)
                assertArrayEquals("pattern=$pattern", expectedPixels, actualPixels)
                assertArrayEquals(jpeg(reference), jpeg(actual))
            } finally {
                reference.recycle()
                actual.recycle()
            }
        }
    }

    @Test fun fullResolutionParityAndSpeed() {
        val width = 4080
        val height = 3060
        val reference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in row.indices) {
                val seed = (x / 2 * 73856093) xor (y / 2 * 19349663)
                val co = (seed and Int.MAX_VALUE) % 9 - 4
                val cg = ((seed ushr 8) and 255) % 5 - 2
                row[x] = (255 shl 24) or ((128 + co - cg) shl 16) or
                    ((128 + cg) shl 8) or (128 - co - cg)
            }
            reference.setPixels(row, 0, width, 0, y, width, 1)
        }
        val actual = reference.copy(Bitmap.Config.ARGB_8888, true)
        try {
            var start = SystemClock.elapsedRealtime()
            val stats = TextureGuard.process(
                width,
                height,
                { y, buffer -> reference.getPixels(buffer, 0, width, 0, y, width, 1) },
                { y, buffer -> reference.setPixels(buffer, 0, width, 0, y, width, 1) }
            )
            val referenceMs = SystemClock.elapsedRealtime() - start
            start = SystemClock.elapsedRealtime()
            val changed = NativeTextureGuard.process(actual)
            val nativeMs = SystemClock.elapsedRealtime() - start
            assertEquals(stats.changedPixels, changed)
            val referenceHash = pixelHash(reference)
            assertEquals(referenceHash, pixelHash(actual))
            assertTrue(nativeMs < referenceMs)
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "photon-native-parity.json"
            ).writeText(
                JSONObject().put("width", width).put("height", height)
                    .put("referenceMs", referenceMs).put("nativeMs", nativeMs)
                    .put("pixelsBitExact", true).put("pixelSha256", referenceHash)
                    .put("changedPixels", changed).toString(2)
            )
        } finally {
            reference.recycle()
            actual.recycle()
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it))
        it.toByteArray()
    }

    private fun pixelHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear()
            for (pixel in row) bytes.putInt(pixel)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
