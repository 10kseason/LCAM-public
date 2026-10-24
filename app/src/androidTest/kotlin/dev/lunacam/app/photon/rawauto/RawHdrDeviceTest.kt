package dev.lunacam.app.photon.rawauto

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.NativeRawHdr
import dev.lunacam.processing.nativebridge.NativeSerRender
import dev.lunacam.processing.nativebridge.RawFusionFrame
import dev.lunacam.processing.nativebridge.RawFusionMetadata
import dev.lunacam.processing.nativebridge.SerNightColor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Bayer data only: no CameraDevice, shutter, gallery access or publication. */
@RunWith(AndroidJUnit4::class)
class RawHdrDeviceTest {
    @Test fun deepShadowHdrBeatsMeteredSingleThroughSameRenderer() {
        val width = 256
        val height = 192
        val levels = doubleArrayOf(.01, .016, .03, .06)
        fun scene(x: Int, y: Int): Double = if (x in 80 until 176 && y in 64 until 128) {
            levels[(x - 80) / 24]
        } else {
            .22 + .07 * sin(x * .13) + .06 * cos(y * .19) + .05 * sin((x + y) * .08)
        }
        val random = Random(17017)
        val frames = doubleArrayOf(1.0, .25, 4.0).map { exposure ->
            val pixels = ByteBuffer.allocateDirect(
                width * height * 2
            ).order(ByteOrder.nativeOrder())
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val signal = scene(x, y) * exposure
                    val noisy = signal + random.nextGaussian() * sqrt(.00012 * signal + .000004)
                    pixels.putShort(
                        (y * width + x) * 2,
                        (64 + 959 * noisy).roundToInt().coerceIn(0, 1023).toShort()
                    )
                }
            }
            RawFusionFrame(
                pixels,
                RawFusionMetadata(
                    DoubleArray(4) { 64.0 },
                    1023.0,
                    exposure,
                    DoubleArray(4) { .00012 },
                    DoubleArray(4) { .000004 }
                )
            )
        }
        val hdr = NativeRawHdr.fuse(width, height, frames)
        assertEquals(7, hdr.acceptedMask)
        assertArrayEquals(IntArray(6), hdr.shifts)
        val metered = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val ideal = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = y * width + x
                metered.putInt(p * 4, frames[0].pixels.getShort(p * 2).toInt() and 65535)
                ideal.putInt(p * 4, (scene(x, y) * 65535).roundToInt())
            }
        }
        val normalizedColor = color().copy(blackLevels = FloatArray(4), whiteLevel = 65535f)
        val singleBitmap = NativeSerRender.renderNatural(
            width,
            height,
            metered,
            color(),
            1.587401f,
            .35f,
            .01f
        )
        try {
            val hdrBitmap = NativeSerRender.renderNatural(
                width,
                height,
                hdr.packedPixels,
                normalizedColor,
                1.587401f * hdr.exposureGainScale,
                .35f,
                .01f
            )
            try {
                val idealBitmap = NativeSerRender.renderNatural(
                    width,
                    height,
                    ideal,
                    normalizedColor,
                    1.587401f,
                    .35f,
                    .01f
                )
                try {
                    val ratios = JSONObject()
                    for (band in levels.indices) {
                        var singleError = 0.0
                        var hdrError = 0.0
                        for (y in 72 until 120) {
                            for (x in 86 + band * 24 until 98 + band * 24) {
                                val expected = idealBitmap.getPixel(x, y)
                                val single = singleBitmap.getPixel(x, y)
                                val fused = hdrBitmap.getPixel(x, y)
                                for (shift in listOf(0, 8, 16)) {
                                    val truth = (expected shr shift) and 255
                                    singleError += (((single shr shift) and 255) - truth)
                                        .toDouble().pow(2)
                                    hdrError += (((fused shr shift) and 255) - truth)
                                        .toDouble().pow(2)
                                }
                            }
                        }
                        val ratio = hdrError / singleError
                        assertTrue("band=${levels[band]} rendered MSE ratio=$ratio", ratio < .6)
                        ratios.put(levels[band].toString(), ratio)
                    }
                    write(
                        "photon-raw-hdr-deep-shadow.json",
                        JSONObject()
                            .put("syntheticOnly", true).put("photosCaptured", 0)
                            .put("sameNaturalRenderer", true).put("renderedMseRatios", ratios)
                    )
                } finally {
                    idealBitmap.recycle()
                }
            } finally {
                hdrBitmap.recycle()
            }
        } finally {
            singleBitmap.recycle()
        }
    }

    private fun frames(width: Int, height: Int): List<RawFusionFrame> {
        val exposure = doubleArrayOf(1.0, .25, 4.0)
        val xWave = DoubleArray(width) { .07 * sin(it * .13) }
        val yWave = DoubleArray(height) { .06 * cos(it * .19) }
        val diagonal = DoubleArray(width + height) { .05 * sin(it * .08) }
        return exposure.map { scale ->
            val pixels = ByteBuffer.allocateDirect(
                width * height * 2
            ).order(ByteOrder.nativeOrder())
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val scene = .22 + xWave[x] + yWave[y] + diagonal[x + y]
                    val value = (64 + 959 * scene * scale).roundToInt().coerceIn(0, 1023)
                    pixels.putShort((y * width + x) * 2, value.toShort())
                }
            }
            RawFusionFrame(
                pixels,
                RawFusionMetadata(
                    DoubleArray(4) { 64.0 },
                    1023.0,
                    scale,
                    DoubleArray(4),
                    DoubleArray(4) { .000004 }
                )
            )
        }
    }

    @Test fun bracketJniPreservesMotionAndSkyWhileMergingStaticShadow() {
        val width = 256
        val height = 192
        val frames = frames(width, height)
        for (y in 80..109) {
            for (x in 100..144) {
                for (index in listOf(0, 2)) {
                    val buffer = frames[index].pixels
                    val offset = (y * width + x) * 2
                    buffer.putShort(
                        offset,
                        ((buffer.getShort(offset).toInt() and 65535) + 150)
                            .coerceAtMost(1023).toShort()
                    )
                }
            }
        }
        for (y in 135..154) {
            for (x in 100..139) {
                val offset = (y * width + x) * 2
                frames[0].pixels.putShort(offset, 1023)
                frames[2].pixels.putShort(offset, 1023)
                frames[1].pixels.putShort(offset, (700 + x - 100).toShort())
            }
        }
        val hdr = NativeRawHdr.fuse(width, height, frames)
        assertEquals(1, hdr.referenceIndex)
        assertEquals(7, hdr.acceptedMask)
        assertEquals(3, hdr.acceptedFrames)
        assertEquals(4f, hdr.exposureGainScale, 0f)
        assertEquals(65535f, hdr.outputWhiteLevel, 0f)
        assertArrayEquals(FloatArray(4), hdr.outputBlackLevels, 0f)
        assertArrayEquals(IntArray(6), hdr.shifts)
        assertTrue(hdr.mergedPixels > 1000)
        assertTrue(hdr.rejectedCells > 0)
        fun reference(x: Int, y: Int): Int {
            val dn = frames[1].pixels.getShort((y * width + x) * 2).toInt() and 65535
            return ((dn - 64) / 959.0 * 65535).roundToInt().coerceIn(0, 65535)
        }
        for (y in 85..104) {
            for (x in 105..134) {
                assertEquals(reference(x, y), hdr.packedPixels.getInt((y * width + x) * 4))
            }
        }
        for (y in 138..151) {
            for (x in 105..134) {
                assertEquals(reference(x, y), hdr.packedPixels.getInt((y * width + x) * 4))
            }
        }
        repeat(width * height) { assertTrue(hdr.packedPixels.getInt(it * 4) in 0..65535) }
        val color = color().copy(
            blackLevels = hdr.outputBlackLevels,
            whiteLevel = hdr.outputWhiteLevel
        )
        val bitmap = NativeSerRender.renderNatural(
            width,
            height,
            hdr.packedPixels,
            color,
            1.587401f * hdr.exposureGainScale,
            .35f,
            .01f
        )
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(120, 140)) > Color.red(bitmap.getPixel(30, 30)))
        } finally {
            bitmap.recycle()
        }
        write(
            "photon-raw-hdr-synthetic.json",
            JSONObject()
                .put("syntheticOnly", true).put("photosCaptured", 0)
                .put("referenceIndex", hdr.referenceIndex).put("acceptedMask", hdr.acceptedMask)
                .put("mergedPixels", hdr.mergedPixels).put("rejectedCells", hdr.rejectedCells)
                .put("fusionMs", hdr.elapsedMs)
        )
    }

    @Test fun normalizedSixteenBitHdrGainMatchesIndependentTone() {
        for (gain in listOf(6.349604f, 16f)) {
            for (level in listOf(0, 32, 128, 1024, 4096, 16384, 40000)) {
                val packed = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder())
                repeat(256) { packed.putInt(it * 4, level) }
                val bitmap = NativeSerRender.renderNatural(
                    16,
                    16,
                    packed,
                    color().copy(blackLevels = FloatArray(4), whiteLevel = 65535f),
                    gain,
                    .35f,
                    .01f
                )
                try {
                    val t = level / 65535.0 * gain
                    val lifted = t + .35 * .18 * t / (t + .01) * t / (t + .10)
                    val mapped = if (lifted <=
                        .65
                    ) {
                        lifted
                    } else {
                        .65 + .35 * (lifted - .65) / (lifted - .30)
                    }
                    val encoded = if (mapped <=
                        .0031308
                    ) {
                        12.92 * mapped
                    } else {
                        1.055 * mapped.pow(1.0 / 2.4) - .055
                    }
                    val actual = bitmap.getPixel(8, 8)
                    assertEquals(floor(encoded * 255 + .5), Color.red(actual).toDouble(), 1.0)
                    assertEquals(Color.red(actual), Color.green(actual))
                    assertEquals(Color.red(actual), Color.blue(actual))
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun fullResolutionHdrFusionSyntheticTiming() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("photonRawHdrFull") == "1")
        val width = 4080
        val height = 3060
        val result = NativeRawHdr.fuse(width, height, frames(width, height))
        assertEquals(1, result.referenceIndex)
        assertEquals(width * height * 4, result.packedPixels.capacity())
        assertTrue(result.mergedPixels > 1000)
        write(
            "photon-raw-hdr-timing.json",
            JSONObject().put("syntheticOnly", true)
                .put("width", width).put("height", height).put("fusionMs", result.elapsedMs)
                .put("mergedPixels", result.mergedPixels).put("photosCaptured", 0)
        )
    }

    private fun color() = SerNightColor(
        0,
        FloatArray(4) { 64f },
        1023f,
        FloatArray(4) { 1f },
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    )

    private fun write(name: String, value: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).writeText(value.toString(2))
    }
}
