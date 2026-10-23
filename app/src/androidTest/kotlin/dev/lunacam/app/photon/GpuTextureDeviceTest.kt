package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.GpuTextureGuard
import dev.lunacam.processing.nativebridge.GpuTextureResult
import dev.lunacam.processing.nativebridge.NativeTextureGuard
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No camera access. Run explicitly with instrumentation argument photonGpuTexture=1. */
@RunWith(AndroidJUnit4::class)
class GpuTextureDeviceTest {
    private class Unavailable(reason: String) : RuntimeException(reason)

    @Test fun optInSparseAndFlatTransferInclusiveBenchmark() {
        assumeTrue(
            "GPU experiment is opt-in",
            InstrumentationRegistry.getArguments().getString("photonGpuTexture") == "1"
        )
        val report = JSONObject().put("syntheticOnly", true).put("productionSelected", false)
            .put("contextPolicy", "fresh_context_each_call_no_persistent_worker")
            .put("coldMeaning", "first_call_per_fixture_driver_cache_may_be_warm")
        val fixtures = JSONArray()
        report.put("fixtures", fixtures)
        var status = "running"
        try {
            for (name in listOf("flat_zero_change", "varied_edges_sparse_chroma")) {
                val fixtureReport = JSONObject().put("name", name)
                fixtures.put(fixtureReport)
                val input = preservedScene(name == "flat_zero_change")
                benchmarkFullInput(
                    input,
                    fixtureReport,
                    if (name == "flat_zero_change") 0L..0L else 1L..2_000L
                )
            }
            status = "parity_passed"
        } catch (failure: Unavailable) {
            status = "unavailable_keep_cpu"
            report.put("reason", failure.message)
        } catch (failure: Throwable) {
            status = "failed_keep_cpu"
            report.put("reason", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("status", status)
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "photon-gpu-texture-sparse-${System.currentTimeMillis()}.json"
            ).writeText(report.toString(2))
        }
    }

    @Test fun optInSyntheticParityAndTransferInclusiveBenchmark() {
        assumeTrue(
            "GPU experiment is opt-in",
            InstrumentationRegistry.getArguments().getString("photonGpuTexture") == "1"
        )
        val report = JSONObject().put("syntheticOnly", true).put("productionSelected", false)
            .put("contextPolicy", "fresh_context_each_call_no_persistent_worker")
        var status = "running"
        try {
            // First invocation includes first shader compilation/driver-cache population.
            fullResolution(report)
            val fixtures = JSONArray()
            var totalChanged = 0L
            for (pattern in 0 until 32) {
                val width = when (pattern) {
                    0 -> 1
                    1 -> 2
                    2 -> 3
                    else -> 67
                }
                val height = when (pattern) {
                    0 -> 1
                    1 -> 19
                    2 -> 3
                    else -> 53
                }
                val input = fixture(width, height, pattern)
                try {
                    val original = rawBytes(input)
                    val cpu = input.copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        val expectedChanged = NativeTextureGuard.process(cpu)
                        val gpu = success(
                            GpuTextureGuard.process(input),
                            "fixture_$pattern"
                        )
                        try {
                            assertEquals(
                                "changed pattern=$pattern",
                                expectedChanged,
                                gpu.changedPixels
                            )
                            assertRawPixelsEqual(cpu, gpu.bitmap, "pattern=$pattern")
                            assertArrayEquals("input pattern=$pattern", original, rawBytes(input))
                            totalChanged += expectedChanged
                            fixtures.put(
                                JSONObject().put("pattern", pattern).put("width", width)
                                    .put("height", height).put("changed", expectedChanged)
                                    .put("totalNs", gpu.totalNs)
                            )
                        } finally {
                            gpu.bitmap.recycle()
                        }
                    } finally {
                        cpu.recycle()
                    }
                } finally {
                    input.recycle()
                }
            }
            assertTrue("Fixtures must exercise actual filtering", totalChanged > 0)
            report.put("fixtures", fixtures).put("smallRawByteParity", true)
            val unsupported = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
            try {
                val original = rawBytes(unsupported)
                assertTrue(
                    GpuTextureGuard.process(unsupported) is GpuTextureResult.Unavailable
                )
                assertArrayEquals(original, rawBytes(unsupported))
            } finally {
                unsupported.recycle()
            }
            status = "parity_passed"
        } catch (failure: Unavailable) {
            status = "unavailable_keep_cpu"
            report.put("reason", failure.message)
        } catch (failure: Throwable) {
            status = "failed_keep_cpu"
            report.put("reason", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("status", status)
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "photon-gpu-texture-prototype-${System.currentTimeMillis()}.json"
            ).writeText(report.toString(2))
        }
    }

    private fun fullResolution(report: JSONObject) {
        val width = 4080
        val height = 3060
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in row.indices) {
                val seed = (x * 73856093) xor (y * 19349663)
                val co = (seed and Int.MAX_VALUE) % 11 - 5
                val cg = ((seed ushr 8) and 255) % 5 - 2
                val luminance = if (x in 1000..1100) 70 else 128
                row[x] = rgb(luminance + co - cg, luminance + cg, luminance - co - cg)
            }
            input.setPixels(row, 0, width, 0, y, width, 1)
        }
        benchmarkFullInput(input, report, 1L..width.toLong() * height)
    }

    /** Consumes and recycles input, including unsupported GPU and failed parity paths. */
    private fun benchmarkFullInput(input: Bitmap, report: JSONObject, expectedChanges: LongRange) {
        val width = input.width
        val height = input.height
        val cpuTimes = mutableListOf<Long>()
        val cpuCopyTimes = mutableListOf<Long>()
        val gpuTimes = mutableListOf<Long>()
        val gpuRuns = JSONArray()
        var cpu: Bitmap? = null
        try {
            val originalHash = opaquePixelHash(input)
            var expectedChanged = -1L
            repeat(3) { run ->
                val copyStart = now()
                val reference = input.copy(Bitmap.Config.ARGB_8888, true)
                val computeStart = now()
                val changed = NativeTextureGuard.process(reference)
                cpuTimes += now() - computeStart
                cpuCopyTimes += now() - copyStart
                if (run == 0) {
                    cpu = reference
                    expectedChanged = changed
                } else {
                    try {
                        assertEquals(expectedChanged, changed)
                    } finally {
                        reference.recycle()
                    }
                }
                val gpuStart = now()
                val gpuResult = GpuTextureGuard.process(input)
                val gpuCallNs = now() - gpuStart
                val gpu = success(gpuResult, "full_$run")
                try {
                    assertEquals(expectedChanged, gpu.changedPixels)
                    // All pixels in this large fixture are opaque. ARGB equality is therefore
                    // byte equality after the one-to-one RGBA/ARGB channel permutation.
                    assertOpaquePixelsEqual(requireNotNull(cpu), gpu.bitmap)
                    assertEquals("Source changed in run $run", originalHash, opaquePixelHash(input))
                    gpuTimes += gpuCallNs
                    gpuRuns.put(
                        JSONObject().put("run", run).put("totalNs", gpuCallNs)
                            .put("internalTotalNs", gpu.totalNs)
                            .put("stagesNs", JSONObject(gpu.stagesNs))
                            .put("changedPixels", gpu.changedPixels)
                    )
                } finally {
                    gpu.bitmap.recycle()
                }
            }
            assertTrue(
                "Changed count $expectedChanged outside fixture range $expectedChanges",
                expectedChanged in expectedChanges
            )
            val warmGpu = gpuTimes.drop(1).average()
            val warmCpu = cpuTimes.drop(1).average()
            report.put("width", width).put("height", height).put("fullPixelParity", true)
                .put("inputPreserved", true).put("coldGpuTotalNs", gpuTimes.first())
                .put("warmGpuMeanTotalNs", warmGpu).put("cpuInPlaceNs", JSONArray(cpuTimes))
                .put("cpuCopyAndProcessNs", JSONArray(cpuCopyTimes)).put("gpuRuns", gpuRuns)
                .put("fasterThanCpuInPlace", warmGpu < warmCpu)
                .put("coldFasterThanCpuInPlace", gpuTimes.first() < cpuTimes.first())
                .put("changedPixels", expectedChanged)
                .put("changedFraction", expectedChanged.toDouble() / (width.toLong() * height))
                .put("decision", "experiment_only_manual_review_required")
        } finally {
            cpu?.recycle()
            input.recycle()
        }
    }

    private fun preservedScene(flat: Boolean): Bitmap {
        val width = 4080
        val height = 3060
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (flat) {
                bitmap.eraseColor(rgb(128, 128, 128))
                return bitmap
            }
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in row.indices) {
                    // A deterministic scene of smooth shading, sharp edges, fine detail,
                    // shadows, highlights and colored panels. No photographic data is read.
                    val region = (x / 680 + 2 * (y / 510)) % 6
                    val luminance = when (region) {
                        0 -> 32 + (x * 160 / width + y * 32 / height) % 176
                        1 -> 72 + (x * 17 + y * 31) % 96
                        2 -> if ((x / 13 + y / 11) % 2 == 0) 70 else 180
                        3 -> 8 + (x / 31 + y / 29) % 7
                        4 -> 240 + (x / 37 + y / 41) % 8
                        else -> 100 + (x / 71 + y / 67) % 20
                    }
                    row[x] = if (region == 5) {
                        rgb(luminance - 40, luminance + 10, luminance + 60)
                    } else {
                        rgb(luminance, luminance, luminance)
                    }
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            // 144 isolated chroma defects in small uniform patches. Their centers should
            // change, while the overwhelming majority of the 12MP scene stays untouched.
            val patch = IntArray(17 * 17) { rgb(128, 128, 128) }
            patch[8 * 17 + 8] = rgb(136, 128, 120)
            for (gy in 0 until 12) {
                for (gx in 0 until 12) {
                    bitmap.setPixels(patch, 0, 17, 232 + 300 * gx, 192 + 220 * gy, 17, 17)
                }
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun fixture(width: Int, height: Int, pattern: Int): Bitmap {
        val random = Random(0x7300 + pattern)
        val boundaries = intArrayOf(0, 1, 15, 16, 17, 127, 128, 238, 239, 240, 254, 255)
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when (pattern) {
                0, 1 -> rgb(128, 128, 128)
                2 -> if (index == 4) rgb(136, 128, 120) else rgb(128, 128, 128)
                in 3..14 -> {
                    val l = boundaries[pattern - 3]
                    val co = random.nextInt(-6, 7)
                    val cg = random.nextInt(-3, 4)
                    rgb(l + co - cg, l + cg, l - co - cg)
                }
                15 -> random.nextInt() or (255 shl 24)
                16 -> random.nextInt()
                17 -> rgb(if (x < width / 2) 70 else 170, 128, 128)
                18 -> rgb(128, if (y < height / 2) 70 else 170, 128)
                19 -> if ((x + y) % 2 == 0) rgb(255, 0, 0) else rgb(0, 255, 255)
                20 -> rgb(128 + random.nextInt(-14, 15), 128, 128)
                21 -> rgb(128, 128 + random.nextInt(-14, 15), 128)
                22 -> rgb(128, 128, 128 + random.nextInt(-14, 15))
                23 -> rgb(128, 128, 128, if (index % 37 == 0) 254 else 255)
                24 -> rgb(128, 128, 128, if (index % 37 == 0) 0 else 255)
                in 28..31 -> {
                    // Independent 3x3 patches with a one-pixel dark separator. Perturb a
                    // center or opposing pair across exact luminance/chroma vote thresholds.
                    val deltas = intArrayOf(
                        -41, -40, -25, -24, -13, -12, -7, -6,
                        -1, 0, 1, 6, 7, 12, 13, 24, 25, 40, 41
                    )
                    val cell = x / 4 + (y / 4) * ((width + 3) / 4)
                    val localX = x % 4
                    val localY = y % 4
                    if (localX == 3 || localY == 3) {
                        rgb(0, 0, 0)
                    } else {
                        val delta = deltas[cell % deltas.size]
                        val adjust = if (pattern == 28) {
                            localX == 1 && localY == 1
                        } else {
                            localX == 0 && localY == 1
                        }
                        val value = if (adjust) delta else 0
                        when (pattern) {
                            28, 29 -> rgb(128 + value, 128, 128 - value)
                            30 -> rgb(128, 128 + value, 128)
                            else -> rgb(128 + value, 128 + value, 128 + value)
                        }
                    }
                }
                else -> {
                    // Flat noisy patches separated by preserved luminance edges.
                    val l = 40 + ((x / 9 + y / 7) % 4) * 50
                    rgb(l + random.nextInt(-7, 8), l + random.nextInt(-3, 4), l)
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun success(result: GpuTextureResult, phase: String): GpuTextureResult.Success =
        when (result) {
            is GpuTextureResult.Success -> result
            is GpuTextureResult.Unavailable -> throw Unavailable("$phase:${result.reason}")
        }

    private fun rawBytes(bitmap: Bitmap): ByteArray {
        val buffer = ByteBuffer.allocate(bitmap.rowBytes * bitmap.height)
        bitmap.copyPixelsToBuffer(buffer)
        return buffer.array()
    }

    private fun assertRawPixelsEqual(expected: Bitmap, actual: Bitmap, label: String) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val a = rawBytes(expected)
        val b = rawBytes(actual)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width * 4) {
                assertEquals(
                    "$label byte($x,$y)",
                    a[y * expected.rowBytes + x],
                    b[y * actual.rowBytes + x]
                )
            }
        }
    }

    private fun assertOpaquePixelsEqual(expected: Bitmap, actual: Bitmap) {
        val a = IntArray(expected.width)
        val b = IntArray(expected.width)
        for (y in 0 until expected.height) {
            expected.getPixels(a, 0, a.size, 0, y, a.size, 1)
            actual.getPixels(b, 0, b.size, 0, y, b.size, 1)
            assertArrayEquals("full row=$y", a, b)
        }
    }

    private fun opaquePixelHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            bytes.clear()
            for (pixel in row) bytes.putInt(pixel)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()
}
