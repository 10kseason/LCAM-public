package dev.lunacam.app.photon

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.NativeHdrFusion
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated pixels only. Does not open a camera, save a photo, or access user photos. */
@RunWith(AndroidJUnit4::class)
class PhotonHdrDeviceTest {
    @Test fun synthetic12MpUsesShortExposureAndPreservesMidtones() {
        val width = 4080
        val height = 3060
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val short = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val lut = IntArray(16385) { index ->
                val linear = (index / 4096.0).coerceIn(0.0, 1.0)
                val encoded = if (linear <= 0.0031308) {
                    linear * 12.92
                } else {
                    1.055 * linear.pow(1.0 / 2.4) - 0.055
                }
                (encoded * 255).roundToInt().coerceIn(0, 255)
            }
            val baseRow = IntArray(width)
            val shortRow = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val bx = (x + 10000) / 5
                    val by = (y + 10000) / 5
                    val hash = (bx * 1664525) xor (by * 1013904223) xor (bx * by * 31)
                    val highlight = x % 256 in 96..159 && y % 256 in 96..159
                    val value = if (highlight) {
                        1.2 + (x % 8) * 0.16 + (y % 7) * 0.02
                    } else {
                        0.12 + (hash.toUInt() % 450u).toInt() / 1000.0
                    }
                    val index = (value * 4096).roundToInt()
                    val a = lut[index]
                    val b = lut[index / 4]
                    baseRow[x] = rgb(a)
                    shortRow[x] = rgb(b)
                }
                base.setPixels(baseRow, 0, width, 0, y, width, 1)
                short.setPixels(shortRow, 0, width, 0, y, width, 1)
            }
            val middleBefore = base.getPixel(80, 80)
            val shortBefore = short.getPixel(104, 128)
            val stats = NativeHdrFusion.merge(base, short, 4.0)
            assertTrue("No actual short-frame evidence accepted: ${stats.reason}", stats.accepted)
            assertTrue(stats.changedPixels > 10_000)
            assertEquals(0, stats.shiftX)
            assertEquals(0, stats.shiftY)
            assertEquals(middleBefore, base.getPixel(80, 80))
            assertEquals(shortBefore, short.getPixel(104, 128))
            assertTrue(stats.diagnostics.calibrationInlierFraction >= 0.85)
            assertTrue(stats.diagnostics.calibrationSamples >= 176)
            assertTrue((base.getPixel(104, 128) and 255) < (base.getPixel(110, 128) and 255))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val report = JSONObject().put("width", width).put("height", height)
                .put("nativeMs", stats.elapsedMs).put("changedPixels", stats.changedPixels)
                .put("shiftX", stats.shiftX).put("shiftY", stats.shiftY)
                .put("accepted", stats.accepted).put("reason", stats.reason)
                .put("alignmentAnchors", stats.diagnostics.alignmentAnchors)
                .put("alignmentCost", stats.diagnostics.alignmentCost)
                .put("calibrationInlierFraction", stats.diagnostics.calibrationInlierFraction)
                .put("calibrationRmse", stats.diagnostics.calibrationRmse)
                .put("syntheticOnly", true).put("realPhotoQualityVerified", false)
                .put("midtonesPreserved", true).put("shortInputUnchanged", true)
                .put("target500msMet", stats.elapsedMs < 500)
            File(context.filesDir, "photon-hdr-benchmark.json").writeText(report.toString(2))
            // This is a regression timeout; 500 ms remains a measured goal, not an assumed result.
            assertTrue(
                "Native HDR unexpectedly slow: ${stats.elapsedMs} ms",
                stats.elapsedMs < 5000
            )
        } finally {
            base.recycle()
            short.recycle()
        }
    }

    @Test fun nonlinearJpegResponseUsesValidatedShortExposure() {
        val base = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val short = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        fun encode(linear: Double): Int {
            val value = linear.coerceIn(0.0, 1.0)
            val encoded = if (value <=
                0.0031308
            ) {
                value * 12.92
            } else {
                1.055 * value.pow(1.0 / 2.4) - 0.055
            }
            return (encoded * 255).roundToInt().coerceIn(0, 255)
        }
        try {
            for (y in 0 until 256) {
                for (x in 0 until 256) {
                    val bx = (x + 10000) / 5
                    val by = (y + 10000) / 5
                    val hash = (bx * 1664525) xor (by * 1013904223) xor (bx * by * 31)
                    val value = if (x in 96..159 && y in 96..159) {
                        1.2 + (x % 8) * 0.16 + (y % 7) * 0.02
                    } else {
                        0.12 + (hash.toUInt() % 450u).toInt() / 1000.0
                    }
                    base.setPixel(x, y, rgb(encode(value)))
                    short.setPixel(x, y, rgb(encode(0.8 * (value / 4).pow(0.75))))
                }
            }
            val before = base.getPixel(80, 80)
            val stats = NativeHdrFusion.merge(base, short, 4.0)
            assertTrue("Nonlinear response rejected: $stats", stats.accepted)
            assertTrue(stats.changedPixels >= 256)
            assertTrue(stats.diagnostics.calibrationInlierFraction >= 0.85)
            assertTrue(stats.diagnostics.calibrationRmse <= 0.035)
            assertEquals(before, base.getPixel(80, 80))
        } finally {
            base.recycle()
            short.recycle()
        }
    }

    @Test fun ambiguityAndInvalidInputsKeepBaseAndReleaseLocks() {
        val base = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val shortMutable = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        base.eraseColor(rgb(170))
        shortMutable.eraseColor(rgb(90))
        val short = requireNotNull(shortMutable.copy(Bitmap.Config.ARGB_8888, false))
        shortMutable.recycle()
        try {
            val stats = NativeHdrFusion.merge(base, short, 4.0)
            assertFalse(stats.accepted)
            assertEquals(0L, stats.changedPixels)
            assertEquals(rgb(170), base.getPixel(64, 64))
            assertThrows(IllegalArgumentException::class.java) {
                NativeHdrFusion.merge(base, base, 4.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeHdrFusion.merge(short, base, 4.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeHdrFusion.merge(base, short, Double.NaN)
            }
            base.setPixel(64, 64, rgb(120))
            assertEquals(rgb(120), base.getPixel(64, 64))
        } finally {
            base.recycle()
            short.recycle()
        }
    }

    private fun rgb(value: Int) = (255 shl 24) or (value shl 16) or (value shl 8) or value
}
