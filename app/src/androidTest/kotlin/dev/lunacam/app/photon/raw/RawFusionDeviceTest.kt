package dev.lunacam.app.photon.raw

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.NativeRawFusion
import dev.lunacam.processing.nativebridge.RawFusionFrame
import dev.lunacam.processing.nativebridge.RawFusionMetadata
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic sensor samples only. No camera or stored photograph access. */
@RunWith(AndroidJUnit4::class)
class RawFusionDeviceTest {
    @Test fun fullResolutionFusionPreservesInputsAndReducesSyntheticNoise() {
        val width = 4080
        val height = 3060
        val blacks = doubleArrayOf(64.0, 72.0, 80.0, 96.0)
        val metadata = RawFusionMetadata(
            blacks,
            4095.0,
            1.0,
            DoubleArray(4),
            DoubleArray(4) { 0.000025 }
        )
        val xs = DoubleArray(width) { 0.055 * sin(it * 0.13) }
        val ys = DoubleArray(height) { 0.04 * cos(it * 0.19) }
        val ds = DoubleArray(width + height) { 0.03 * sin(it * 0.08) }
        var seed = 20261023
        val frames = List(3) {
            val pixels = ByteBuffer.allocateDirect(
                width * height * 2
            ).order(ByteOrder.nativeOrder())
            val shorts = pixels.asShortBuffer()
            val row = ShortArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val c = (y % 2) * 2 + x % 2
                    val flat = x in 1400..2600 && y in 1000..2000
                    val truth = 0.30 + c * 0.018 + if (flat) 0.0 else xs[x] + ys[y] + ds[x + y]
                    seed = seed * 1664525 + 1013904223
                    val noise = ((seed ushr 16) % 71 - 35) / 4095.0
                    row[x] =
                        ((truth + noise) * (4095 - blacks[c]) + blacks[c]).roundToInt().toShort()
                }
                shorts.put(row)
            }
            RawFusionFrame(pixels, metadata)
        }
        val hashes = frames.map { hash(it.pixels) }
        val result = NativeRawFusion.fuse(width, height, frames)
        assertEquals(3, result.acceptedFrames)
        assertTrue(result.changedPixels > 0)
        assertEquals(hashes, frames.map { hash(it.pixels) })
        val original = frames[result.baselineIndex].pixels.asShortBuffer()
        val processed = result.pixels.asShortBuffer()
        var before = 0.0
        var after = 0.0
        for (y in 1400 until 1500) {
            for (x in 1800 until 2000) {
                val c = (y % 2) * 2 + x % 2
                val expected = 0.30 + c * 0.018
                val a = ((original.get(y * width + x).toInt() and 65535) - blacks[c]) /
                    (4095 - blacks[c]) - expected
                val b = ((processed.get(y * width + x).toInt() and 65535) - blacks[c]) /
                    (4095 - blacks[c]) - expected
                before += a * a
                after += b * b
            }
        }
        val ratio = after / before
        assertTrue("mseRatio=$ratio", ratio < 0.8)
        for (x in 0 until width) {
            assertEquals(original.get(x), processed.get(x))
        }
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "photon-raw-fusion-benchmark.json"
        ).writeText(
            JSONObject().put("synthetic", true).put("width", width).put("height", height)
                .put("elapsedMs", result.elapsedMs).put("acceptedFrames", result.acceptedFrames)
                .put(
                    "baselineIndex",
                    result.baselineIndex
                ).put("changedPixels", result.changedPixels)
                .put("noiseMseRatio", ratio).put("sourceHashesUnchanged", true)
                .put("effectiveSamples", result.meanEffectiveSamples).toString(2)
        )
    }

    private fun hash(input: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(input.duplicate())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
