package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.GpuTextureGuard
import dev.lunacam.processing.nativebridge.GpuTextureResult
import dev.lunacam.processing.nativebridge.NativeDetailGuard
import dev.lunacam.processing.nativebridge.NativeTextureGuard
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic only; explicitly enable with instrumentation argument photonDetail=1. */
@RunWith(AndroidJUnit4::class)
class DetailDeviceTest {
    private class Unavailable(reason: String) : RuntimeException(reason)

    @Test fun optInDetailParityAndBenchmark() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("photonDetail") == "1")
        val report = JSONObject().put("syntheticOnly", true)
            .put("detailDefault", false).put("fixtureFirstCallMayHaveWarmDriverCache", true)
        val full = JSONArray()
        val small = JSONArray()
        report.put("fullResolution", full).put("smallFixtures", small)
        var status = "running"
        try {
            for (kind in listOf("noise", "soft_edge")) {
                val item = JSONObject().put("fixture", kind)
                full.put(item)
                benchmark(kind, item)
            }
            var changedSum = 0L
            var textureChangedSum = 0L
            for (pattern in 0 until 72) {
                val input = smallFixture(pattern)
                val original = bytes(input)
                val texture = input.copy(Bitmap.Config.ARGB_8888, true)
                var detail: Bitmap? = null
                try {
                    val textureCount = NativeTextureGuard.process(texture)
                    textureChangedSum += textureCount
                    val oldGpu = success(
                        if (pattern % 2 == 0) {
                            GpuTextureGuard.process(input)
                        } else {
                            GpuTextureGuard.process(input, detailEnabled = false)
                        }
                    )
                    try {
                        assertEquals(textureCount, oldGpu.changedPixels)
                        assertEquals(0L, oldGpu.detailChangedPixels)
                        equalRaw(texture, oldGpu.bitmap, "OFF pattern=$pattern")
                    } finally {
                        oldGpu.bitmap.recycle()
                    }
                    val cpu = texture.copy(Bitmap.Config.ARGB_8888, true)
                    detail = cpu
                    val detailCount = NativeDetailGuard.process(cpu)
                    if (pattern in 36..71) {
                        // Faint lines, weave and lettering retain their local extrema.
                        assertEquals("weak texture pattern=$pattern", 0L, detailCount)
                        equalRaw(texture, cpu, "weak texture preserved pattern=$pattern")
                    }
                    val gpu = success(GpuTextureGuard.process(input, detailEnabled = true))
                    try {
                        assertEquals(textureCount, gpu.changedPixels)
                        assertEquals(detailCount, gpu.detailChangedPixels)
                        equalRaw(cpu, gpu.bitmap, "ON pattern=$pattern")
                        bounded(texture, gpu.bitmap)
                        assertArrayEquals("source pattern=$pattern", original, bytes(input))
                        changedSum += detailCount
                        small.put(
                            JSONObject().put("pattern", pattern).put("width", input.width)
                                .put("height", input.height).put("textureChanged", textureCount)
                                .put("detailChanged", detailCount)
                        )
                    } finally {
                        gpu.bitmap.recycle()
                    }
                } finally {
                    detail?.recycle()
                    texture.recycle()
                    input.recycle()
                }
            }
            assertTrue("Small fixtures must exercise detail changes", changedSum > 0)
            assertTrue("Small fixtures must also exercise TextureGuard", textureChangedSum > 0)
            status = "parity_passed"
        } catch (failure: Unavailable) {
            status = "gpu_unavailable"
            report.put("reason", failure.message)
        } catch (failure: Throwable) {
            status = "failed"
            report.put("reason", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("status", status)
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "photon-detail-parity-${System.currentTimeMillis()}.json"
            ).writeText(report.toString(2))
        }
    }

    private fun benchmark(kind: String, report: JSONObject) {
        val input = Bitmap.createBitmap(4080, 3060, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(input.width)
            for (y in 0 until input.height) {
                for (x in row.indices) {
                    val luminance = if (kind == "noise") {
                        val seed = (x * 73856093) xor (y * 19349663)
                        128 + (seed and Int.MAX_VALUE) % 7 - 3
                    } else {
                        edge[x % edge.size] + (y / 128 % 5) * 3
                    }
                    row[x] = rgb(luminance, luminance, luminance)
                }
                input.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            val sourceHash = pixelHash(input)
            val runs = JSONArray()
            report.put("width", input.width).put("height", input.height).put("runs", runs)
            val cpuTimes = mutableListOf<Long>()
            val gpuTimes = mutableListOf<Long>()
            repeat(3) { run ->
                val copyStart = now()
                val cpu = input.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val cpuStart = now()
                    val textureCount = NativeTextureGuard.process(cpu)
                    val detailStart = now()
                    if (run == 0) {
                        val oldStart = now()
                        val oldGpu = success(GpuTextureGuard.process(input, false))
                        val oldTotal = now() - oldStart
                        try {
                            assertEquals(textureCount, oldGpu.changedPixels)
                            assertEquals(0L, oldGpu.detailChangedPixels)
                            equalOpaque(cpu, oldGpu.bitmap)
                            report.put("legacyGpuTotalNs", oldTotal)
                        } finally {
                            oldGpu.bitmap.recycle()
                        }
                    }
                    // Exclude the OFF comparison above from CPU compute measurements.
                    val computeDetailStart = now()
                    val detailCount = NativeDetailGuard.process(cpu)
                    val cpuDetailNs = now() - computeDetailStart
                    val textureNs = detailStart - cpuStart
                    val cpuTotalNs = textureNs + cpuDetailNs
                    cpuTimes += cpuTotalNs
                    assertTrue("$kind must exercise detail", detailCount > 0)
                    val gpuStart = now()
                    val gpuResult = GpuTextureGuard.process(input, true)
                    val gpuTotalNs = now() - gpuStart
                    val gpu = success(gpuResult)
                    try {
                        assertEquals(textureCount, gpu.changedPixels)
                        assertEquals(detailCount, gpu.detailChangedPixels)
                        equalOpaque(cpu, gpu.bitmap)
                        assertEquals(sourceHash, pixelHash(input))
                        gpuTimes += gpuTotalNs
                        runs.put(
                            JSONObject().put("run", run).put("cpuTextureNs", textureNs)
                                .put("cpuDetailNs", cpuDetailNs).put("cpuInPlaceNs", cpuTotalNs)
                                .put("cpuCopyNs", cpuStart - copyStart)
                                .put("gpuTotalNs", gpuTotalNs)
                                .put("gpuStagesNs", JSONObject(gpu.stagesNs))
                                .put("textureChanged", textureCount)
                                .put("detailChanged", detailCount)
                        )
                    } finally {
                        gpu.bitmap.recycle()
                    }
                } finally {
                    cpu.recycle()
                }
            }
            report.put("allPixelsBitExact", true).put("inputPreserved", true)
                .put("firstGpuTotalNs", gpuTimes.first())
                .put("warmGpuMeanNs", gpuTimes.drop(1).average())
                .put("warmCpuMeanNs", cpuTimes.drop(1).average())
                .put("gpuFaster", gpuTimes.drop(1).average() < cpuTimes.drop(1).average())
        } finally {
            input.recycle()
        }
    }

    private fun smallFixture(pattern: Int): Bitmap {
        val width = if (pattern < 5) pattern + 1 else 67
        val height = if (pattern == 0) 1 else 53
        val random = Random(8100 + pattern)
        val levels = intArrayOf(0, 19, 20, 21, 128, 234, 235, 236, 255)
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when (pattern) {
                in 5..13 -> {
                    val l = levels[pattern - 5] + random.nextInt(-3, 4)
                    rgb(l, l, l)
                }
                14 -> random.nextInt() or (255 shl 24)
                15 -> random.nextInt()
                16 -> rgb(edge[x % edge.size], edge[x % edge.size], edge[x % edge.size])
                17 -> rgb(edge[y % edge.size], edge[y % edge.size], edge[y % edge.size])
                18 -> {
                    val l = edge[(x + y) % edge.size]
                    rgb(l, l, l)
                }
                19, 20 -> {
                    val l = 128 + random.nextInt(-3, 4)
                    val alpha = if ((x + 2 * y) % 17 == 0) {
                        if (pattern == 19) 254 else 0
                    } else {
                        255
                    }
                    rgb(l, l, l, alpha)
                }
                21 -> if ((x + y) % 2 == 0) rgb(40, 40, 40) else rgb(210, 210, 210)
                22 -> rgb(0, 100 + random.nextInt(-3, 4), 100)
                23 -> rgb(255, 150 + random.nextInt(-3, 4), 150)
                24 -> rgb(30, 70, 120)
                in 25..33 -> {
                    // Near branch/rounding thresholds and repeated 5x5 neighborhoods.
                    val l = 128 + (pattern - 29) * if (x % 5 == 2 && y % 5 == 2) 3 else 0
                    rgb(l, l, l)
                }
                34, 35 -> {
                    val l = if (pattern == 34) {
                        128 + random.nextInt(-3, 4)
                    } else {
                        edge[x % edge.size]
                    }
                    val co = random.nextInt(-5, 6)
                    val cg = random.nextInt(-2, 3)
                    rgb(l + co - cg, l + cg, l - co - cg)
                }
                in 36..70 -> {
                    val contrast = 2 + (pattern - 36) / 5
                    val ink = when ((pattern - 36) % 5) {
                        0 -> x % 4 == 0
                        1 -> y % 4 == 0
                        2 -> (x + y) % 5 == 0
                        3 -> (x % 4 < 2) xor (y % 4 < 2)
                        else -> {
                            // Repeated 3x5 glyphs include corners and one-pixel strokes.
                            val glyph = intArrayOf(7, 5, 7, 5, 5)
                            val gx = x % 5
                            val gy = y % 7
                            gx < 3 && gy < 5 && (glyph[gy] and (1 shl gx)) != 0
                        }
                    }
                    val l = 128 + if (ink) contrast else 0
                    rgb(l, l, l)
                }
                71 -> {
                    val gx = x % 8
                    val gy = y % 10
                    val ink = gx < 5 && gy < 7 && (gx == 0 || gx == 4 || gy == 3)
                    val l = 128 + if (ink) 6 else 0
                    rgb(l, l, l)
                }
                else -> rgb(128, 128, 128)
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun bounded(before: Bitmap, after: Bitmap) {
        val a = bytes(before)
        val b = bytes(after)
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val ai = y * before.rowBytes + x * 4
                val bi = y * after.rowBytes + x * 4
                val d = (b[bi].toInt() and 255) - (a[ai].toInt() and 255)
                assertTrue(abs(d) <= 2)
                for (channel in 1..2) {
                    assertEquals(
                        d,
                        (b[bi + channel].toInt() and 255) -
                            (a[ai + channel].toInt() and 255)
                    )
                }
                assertEquals(a[ai + 3], b[bi + 3])
                if (x < 2 || y < 2 || x + 2 >= before.width || y + 2 >= before.height) {
                    assertEquals(0, d)
                }
            }
        }
    }

    private fun equalRaw(expected: Bitmap, actual: Bitmap, label: String) {
        val a = bytes(expected)
        val b = bytes(actual)
        for (y in 0 until expected.height) {
            assertArrayEquals(
                "$label row=$y",
                a.copyOfRange(y * expected.rowBytes, y * expected.rowBytes + expected.width * 4),
                b.copyOfRange(y * actual.rowBytes, y * actual.rowBytes + actual.width * 4)
            )
        }
    }

    private fun equalOpaque(expected: Bitmap, actual: Bitmap) {
        // Opaque ARGB/RGBA permutation is bijective, so every compared Int checks all four bytes.
        val a = IntArray(expected.width)
        val b = IntArray(expected.width)
        for (y in 0 until expected.height) {
            expected.getPixels(a, 0, a.size, 0, y, a.size, 1)
            actual.getPixels(b, 0, b.size, 0, y, b.size, 1)
            assertArrayEquals("row=$y", a, b)
        }
    }

    private fun pixelHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val buffer = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            buffer.clear()
            for (pixel in row) buffer.putInt(pixel)
            digest.update(buffer.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bytes(bitmap: Bitmap): ByteArray =
        ByteBuffer.allocate(bitmap.rowBytes * bitmap.height).also {
            bitmap.copyPixelsToBuffer(it)
        }.array()

    private fun success(result: GpuTextureResult): GpuTextureResult.Success = when (result) {
        is GpuTextureResult.Success -> result
        is GpuTextureResult.Unavailable -> throw Unavailable(result.reason)
    }

    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun now() = SystemClock.elapsedRealtimeNanos()

    private val edge = intArrayOf(
        64, 64, 64, 68, 78, 96, 124, 158, 180, 190, 194, 194,
        194, 194, 194, 190, 180, 158, 124, 96, 78, 68, 64, 64
    )
}
