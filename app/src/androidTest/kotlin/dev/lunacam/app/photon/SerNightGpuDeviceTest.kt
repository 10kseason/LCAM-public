package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.GpuSerNight
import dev.lunacam.processing.nativebridge.GpuSerNightResult
import dev.lunacam.processing.nativebridge.NativeSerNight
import dev.lunacam.processing.nativebridge.NativeSerRender
import dev.lunacam.processing.nativebridge.RawFusionFrame
import dev.lunacam.processing.nativebridge.RawFusionMetadata
import dev.lunacam.processing.nativebridge.SerNightColor
import dev.lunacam.processing.nativebridge.SerNightPlan
import dev.lunacam.processing.nativebridge.SerNightShading
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated Bayer buffers only. Never opens the camera, reads photos, or publishes images. */
@RunWith(AndroidJUnit4::class)
class SerNightGpuDeviceTest {
    @Test
    fun clippedNeutralExposureRampsAcrossCfaAndShading() {
        val reports = JSONArray()
        for (cfa in 0..3) {
            for (corner in listOf(1f, 2f)) {
                for (lowWb in listOf(false, true)) {
                    reports.put(neutralExposureRamp(cfa, corner, lowWb))
                }
            }
        }
        write(
            "photon-ser-partial-clip-parity.json",
            JSONObject().put("syntheticOnly", true)
                .put("fixtures", reports)
        )
    }

    private fun neutralExposureRamp(cfa: Int, corner: Float, lowWb: Boolean): JSONObject {
        val width = 128
        val height = 96
        val channels = arrayOf(
            intArrayOf(0, 1, 2, 3),
            intArrayOf(1, 0, 3, 2),
            intArrayOf(1, 3, 0, 2),
            intArrayOf(3, 1, 2, 0)
        )
        val exposures = doubleArrayOf(.7, .9, 1.0, 1.1, 1.4, 1.8, 2.1, 2.5)
        val original = frames(width, height).first()
        val frame = original.copy(metadata = original.metadata.copy(whiteLevel = 1023.0))
        val wb = if (lowWb) {
            floatArrayOf(.8f, .9f, 1.1f, 1.5f)
        } else {
            floatArrayOf(2.0976562f, 1f, 1f, 1.5957031f)
        }
        val matrix = if (lowWb) {
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        } else {
            floatArrayOf(
                1.609375f, -.6015625f, 0f, -.3203125f, 1.453125f, -.1328125f,
                .0390625f, -.9140625f, 1.8671875f
            )
        }
        val raw = frame.pixels.asShortBuffer()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ch = channels[cfa][(y and 1) * 2 + (x and 1)]
                val signal = minOf(1.0, exposures[x / 16] / (wb[ch] * corner))
                val dn = 64 + kotlin.math.floor(signal * 959 + .5).toInt()
                raw.put(y * width + x, dn.toShort())
            }
        }
        val shading = SerNightShading(
            1,
            1,
            FloatArray(4) { corner },
            intArrayOf(0, 0, width, height)
        )
        val color = SerNightColor(cfa, FloatArray(4) { 64f }, 1023f, wb, matrix, shading)
        return compare(
            width,
            height,
            listOf(frame),
            plan(width, height).copy(acceptedMask = 1),
            cfa,
            false,
            colorOverride = color,
            verifyPixels = { bitmap ->
                for (band in 0..7) {
                    for (y in 32..35) {
                        val sampleXs = band * 16 + 6..band * 16 + 9
                        for (x in sampleXs) {
                            val pixel = bitmap.getPixel(x, y)
                            val r = (pixel ushr 16) and 255
                            val g = (pixel ushr 8) and 255
                            val b = pixel and 255
                            assertTrue(
                                "Neutral clipped ramp acquired color",
                                maxOf(r, g, b) - minOf(r, g, b) <= 3
                            )
                            if (lowWb && corner == 1f && band == 1) {
                                assertTrue(abs(r - 231) <= 1)
                                assertTrue(abs(g - 231) <= 1)
                                assertTrue(abs(b - 231) <= 1)
                            }
                        }
                    }
                }
            }
        ).put("shadingGain", corner).put("lowWb", lowWb)
    }

    @Test
    fun fullRawClippingNeutralizesWithoutReconstructingPartialClips() {
        val width = 128
        val height = 96
        val original = frames(width, height).first()
        val frame = original.copy(metadata = original.metadata.copy(whiteLevel = 1023.0))
        val pixels = frame.pixels.asShortBuffer()
        val levels = intArrayOf(300, 1018, 1019, 1020, 1021, 1022, 1023)
        val partial = intArrayOf(400, 300, 1023, 400) // GBRG: only R clips.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val band = x / 16
                val value = if (band < 7) levels[band] else partial[(y and 1) * 2 + (x and 1)]
                pixels.put(y * width + x, value.toShort())
            }
        }
        val color = SerNightColor(
            2,
            FloatArray(4) { 64f },
            1023f,
            floatArrayOf(2.0976562f, 1f, 1f, 1.5957031f),
            floatArrayOf(
                1.609375f, -.6015625f, 0f, -.3203125f, 1.453125f, -.1328125f,
                .0390625f, -.9140625f, 1.8671875f
            )
        )
        val report = compare(
            width,
            height,
            listOf(frame),
            plan(width, height).copy(acceptedMask = 1),
            2,
            false,
            colorOverride = color,
            verifyPixels = { bitmap ->
                for (y in 0 until height) {
                    for (x in 96 until 112) {
                        assertEquals("Fully clipped cell must be white", -1, bitmap.getPixel(x, y))
                    }
                }
                // Independent expected RGB at an R site, with one clipped channel only.
                val rgb = bitmap.getPixel(118, 33)
                assertEquals(255, (rgb ushr 16) and 255)
                assertTrue(abs(((rgb ushr 8) and 255) - 88) <= 1)
                assertTrue(abs((rgb and 255) - 154) <= 1)
            }
        )
        write("photon-ser-clip-guard-parity.json", report.put("syntheticOnly", true))
    }

    @Test
    fun colorGamutAndShadingRamps() {
        val results = JSONArray()
        val width = 128
        val height = 96
        for (cfa in 0..3) {
            val frame = frames(width, height).first()
            val values = frame.pixels.asShortBuffer()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    values.put(y * width + x, (64 + (x / 2) * 4031 / 63).toShort())
                }
            }
            for (kind in 0..2) {
                val matrix = when (kind) {
                    1 -> floatArrayOf(2f, 0f, 0f, 0f, .75f, 0f, 0f, 0f, .25f)
                    2 -> floatArrayOf(-.25f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f)
                    else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                }
                val gains = FloatArray(16) { i ->
                    1f + (i / 4 % 2) * .5f + (i / 8) * .25f + (i % 4) * .125f
                }
                val color = SerNightColor(
                    cfa,
                    FloatArray(4) { 64f },
                    4095f,
                    FloatArray(4) { 1f },
                    matrix,
                    SerNightShading(2, 2, gains, intArrayOf(12, 8, 112, 88))
                )
                results.put(
                    compare(
                        width,
                        height,
                        listOf(frame),
                        plan(width, height).copy(acceptedMask = 1),
                        cfa,
                        false,
                        colorOverride = color
                    )
                        .put("fixture", "color_shading_ramp_$kind")
                )
            }
        }
        write(
            "photon-ser-color-shading-parity.json",
            JSONObject().put("syntheticOnly", true)
                .put("fixtures", results)
        )
    }

    @Test fun syntheticCfaFusionMaskAndRgbParity() {
        val results = JSONArray()
        for (cfa in 0..3) {
            val frames = frames(128, 96)
            for (enabled in listOf(false, true)) {
                results.put(compare(128, 96, frames, plan(128, 96), cfa, enabled))
            }
            // Equal 10-DN gradients with very different directional means expose tie instability.
            val tied = frames(128, 96).take(1)
            val values = tied.single().pixels.asShortBuffer()
            for (i in 0 until 128 * 96) values.put(i, 384)
            val x = 16 + if (cfa == 1 || cfa == 2) 1 else 0
            val y = 16
            values.put(y * 128 + x - 1, 300)
            values.put(y * 128 + x + 1, 310)
            values.put((y - 1) * 128 + x, 400)
            values.put((y + 1) * 128 + x, 410)
            results.put(
                compare(128, 96, tied, plan(128, 96).copy(acceptedMask = 1), cfa, false)
                    .put("fixture", "equal_gradient_distinct_means")
            )
        }
        val single = frames(128, 96).take(1)
        results.put(compare(128, 96, single, plan(128, 96).copy(acceptedMask = 1), 0, false))
        val frames = frames(128, 96)
        val rejected = plan(128, 96).copy(tileBounds = FloatArray(2 * 2 * 4) { 1f })
        results.put(compare(128, 96, frames, rejected, 0, true, expectLift = false))
        // A moving auxiliary creates a central region that must retain reference RAW exactly.
        val moved = frames[1].pixels.asShortBuffer()
        for (y in 30 until 66) for (x in 40 until 88) moved.put(y * 128 + x, 1600)
        results.put(compare(128, 96, frames, plan(128, 96), 0, true, motion = true))
        write(
            "photon-ser-gpu-parity.json",
            JSONObject().put("syntheticOnly", true)
                .put("fixtures", results)
        )
    }

    @Test fun optInFullResolutionTransferInclusiveTimingAndParity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("photonSerGpu") == "1")
        val width = 4080
        val height = 3060
        val frames = frames(width, height)
        val wb = floatArrayOf(2.0976562f, 1f, 1f, 1.5957031f)
        // Keep the shadow-noise body, adding a physical green-first/full-clip exposure strip.
        for (frame in frames) {
            val pixels = frame.pixels.asShortBuffer()
            for (y in height - 64 until height) {
                for (x in 0 until width) {
                    val exposure = .98 + (x / 2 % 64) * (1.52 / 63)
                    val ch = (y and 1) * 2 + (x and 1) // RGGB for this fixture.
                    val value = 64 + kotlin.math.floor(4031 * minOf(1.0, exposure / wb[ch]) + .5)
                    pixels.put(y * width + x, value.toInt().toShort())
                }
            }
        }
        val prepared = NativeSerNight.prepare(width, height, frames)
        val shadingColumns = 17
        val shadingRows = 13
        val shading = SerNightShading(
            shadingColumns,
            shadingRows,
            FloatArray(shadingColumns * shadingRows * 4) { i ->
                val x = (i / 4 % shadingColumns).toFloat() / (shadingColumns - 1)
                val y = (i / 4 / shadingColumns).toFloat() / (shadingRows - 1)
                1f + (x * x + y * y) * .25f + (i % 4) * .0625f
            },
            intArrayOf(0, 0, width, height)
        )
        val color = SerNightColor(
            0,
            FloatArray(4) { 64f },
            4095f,
            wb,
            floatArrayOf(
                1.609375f, -.6015625f, 0f, -.3203125f, 1.453125f, -.1328125f,
                .0390625f, -.9140625f, 1.8671875f
            ),
            shading
        )
        val report = compare(
            width,
            height,
            frames,
            plan(width, height),
            0,
            true,
            colorOverride = color
        )
        report.put("preparedAcceptedFrames", prepared.acceptedFrames)
            .put("prepareMs", prepared.elapsedMs).put("syntheticOnly", true)
            .put("shadingColumns", shadingColumns).put("shadingRows", shadingRows)
            .put("nearClipBandRows", 64).put("captureWbAndCcmFixture", true)
            .put("shutterTimingMeasured", false)
        write("photon-ser-gpu-12mp.json", report)
    }

    private fun compare(
        width: Int,
        height: Int,
        frames: List<RawFusionFrame>,
        plan: SerNightPlan,
        cfa: Int,
        enabled: Boolean,
        expectLift: Boolean = enabled,
        motion: Boolean = false,
        colorOverride: SerNightColor? = null,
        verifyPixels: ((Bitmap) -> Unit)? = null
    ): JSONObject {
        val sourceHashes = frames.map { sha(it.pixels) }
        val color = colorOverride ?: SerNightColor(
            cfa,
            FloatArray(4) { 64f },
            4095f,
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        val fusion = NativeSerNight.fuse(width, height, frames, plan, enabled)
        val cpuStarted = SystemClock.elapsedRealtimeNanos()
        val reference = NativeSerRender.render(width, height, fusion.packedPixels, color)
        val renderNs = SystemClock.elapsedRealtimeNanos() - cpuStarted
        try {
            verifyPixels?.invoke(reference)
            val candidate = GpuSerNight.process(width, height, frames, plan, color, enabled, true)
            // This regression requires the actual GPU. An unavailable backend cannot pass parity.
            assertTrue(
                "Actual GLES backend required: $candidate",
                candidate is GpuSerNightResult.Success
            )
            val gpu = candidate as GpuSerNightResult.Success
            try {
                verifyPixels?.invoke(gpu.bitmap)
                val a = fusion.packedPixels.asIntBuffer()
                val b = requireNotNull(gpu.packedPixels).asIntBuffer()
                var maxRaw = 0
                var maxEv = 0
                var maskDifferences = 0
                for (i in 0 until width * height) {
                    val av = a.get(i)
                    val bv = b.get(i)
                    maxRaw = maxOf(maxRaw, abs((av and 65535) - (bv and 65535)))
                    maxEv = maxOf(maxEv, abs((av ushr 16) - (bv ushr 16)))
                    if ((av ushr 16 == 0) != (bv ushr 16 == 0)) maskDifferences++
                    if (!enabled || !expectLift ||
                        (motion && i % width in 48..80 && i / width in 38..58)
                    ) {
                        assertEquals(0, bv ushr 16)
                        assertEquals(
                            frames[plan.baselineIndex].pixels.getShort(i * 2).toInt() and 65535,
                            bv and 65535
                        )
                    }
                }
                assertEquals("Mask disagreement", 0, maskDifferences)
                assertTrue("RAW error $maxRaw DN", maxRaw <= 1)
                assertTrue("EV quantization error $maxEv", maxEv <= 4)
                if (expectLift) {
                    assertTrue(gpu.stats.liftedCells > 0)
                } else {
                    assertEquals(0L, gpu.stats.liftedCells)
                }
                val maxRgb = compareRgb(reference, gpu.bitmap)
                if (maxRgb > 1) {
                    // Aggregate diagnostics only, generated fixture pixels never leave the device.
                    // Isolate renderer divergence from permitted upstream RAW quantization error.
                    val sameRawReference = NativeSerRender.render(
                        width,
                        height,
                        requireNotNull(gpu.packedPixels),
                        color
                    )
                    val sameRawError = try {
                        compareRgb(sameRawReference, gpu.bitmap)
                    } finally {
                        sameRawReference.recycle()
                    }
                    val report = JSONObject().put("syntheticOnly", true)
                        .put("width", width).put("height", height).put("cfa", cfa)
                        .put("enabled", enabled).put("maxRawDnError", maxRaw)
                        .put("maxEvQuantError", maxEv).put("maskDifferences", maskDifferences)
                        .put("maxRgbError", maxRgb).put("sameRawRenderError", sameRawError)
                    write("photon-ser-gpu-parity-failure.json", report)
                    assertTrue(
                        "RGB disagreement $maxRgb; same RAW renderer $sameRawError; " +
                            "cfa=$cfa enabled=$enabled raw=$maxRaw ev=$maxEv",
                        maxRgb <= 1
                    )
                }
                assertEquals(sourceHashes, frames.map { sha(it.pixels) })
                return JSONObject().put("width", width).put("height", height).put("cfa", cfa)
                    .put("enabled", enabled).put("backend", "gpu_gles31")
                    .put("changedPixels", gpu.stats.changedPixels)
                    .put("liftedCells", gpu.stats.liftedCells).put("maxRawDnError", maxRaw)
                    .put("maxEvQuantError", maxEv).put("maxRgbError", maxRgb)
                    .put("maskDifferences", maskDifferences).put("gpuTotalNs", gpu.totalNs)
                    .put("gpuStagesNs", JSONObject(gpu.stagesNs))
                    .put("cpuFusionMs", fusion.elapsedMs)
                    .put("cpuRenderNs", renderNs).put("diagnosticReadbackIncluded", true)
            } finally {
                gpu.bitmap.recycle()
            }
        } finally {
            reference.recycle()
        }
    }

    private fun compareRgb(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val left = IntArray(a.width)
        val right = IntArray(a.width)
        var maximum = 0
        for (y in 0 until a.height) {
            a.getPixels(left, 0, a.width, 0, y, a.width, 1)
            b.getPixels(right, 0, a.width, 0, y, a.width, 1)
            for (x in left.indices) {
                assertEquals(255, right[x] ushr 24)
                for (channel in 0..2) {
                    val shift = channel * 8
                    maximum = maxOf(
                        maximum,
                        abs(
                            ((left[x] ushr shift) and 255) -
                                ((right[x] ushr shift) and 255)
                        )
                    )
                }
            }
        }
        return maximum
    }

    private fun plan(width: Int, height: Int): SerNightPlan {
        val columns = (width + 63) / 64
        val rows = (height + 63) / 64
        return SerNightPlan(
            0, IntArray(6), 7, 64, columns, rows,
            FloatArray(columns * rows * 4), intArrayOf(0, 0, width, height), 0,
            "synthetic_known_noise"
        )
    }

    private fun frames(width: Int, height: Int): List<RawFusionFrame> = List(3) { frame ->
        val pixels = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
        val values = pixels.asShortBuffer()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val structure = ((x / 2 * 37 + y / 2 * 23) xor (x / 16 * 11)) % 96
                val noise = ((x * 73856093) xor (y * 19349663) xor (frame * 83492791)) and 3
                values.put((320 + structure + noise - 1).toShort())
            }
        }
        RawFusionFrame(
            pixels,
            RawFusionMetadata(
                DoubleArray(4) { 64.0 },
                4095.0,
                1.0,
                DoubleArray(4),
                DoubleArray(4) { 0.000001 }
            )
        )
    }

    private fun sha(source: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.duplicate())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun write(name: String, report: JSONObject) {
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
            .writeText(report.toString(2))
    }
}
