package dev.lunacam.processing.nativebridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

/** All four-element arrays use Bayer raster positions TL, TR, BL, BR. */
data class RawFusionMetadata(
    val blackLevels: DoubleArray,
    val whiteLevel: Double,
    /** Exposure time * sensitivity relative to frame zero; supported range 0.5..2. */
    val exposureScale: Double,
    /** Android normalized RAW noise model variance = slope * signal + intercept. */
    val noiseSlope: DoubleArray,
    val noiseIntercept: DoubleArray
)

data class RawFusionFrame(val pixels: ByteBuffer, val metadata: RawFusionMetadata)

data class RawFusionResult(
    val pixels: ByteBuffer,
    val baselineIndex: Int,
    val changedPixels: Long,
    val mergedPixels: Long,
    val rejectedPixels: Long,
    val acceptedFrames: Int,
    /** Candidate sample offset relative to baseline, dx/dy RAW pixels for each input. */
    val shifts: IntArray,
    val meanEffectiveSamples: Double,
    val elapsedMs: Long
)

/** Conservative translation-only Bayer fusion. Does not mutate input frames. */
object NativeRawFusion {
    init {
        System.loadLibrary("lunacore")
    }

    fun fuse(
        width: Int,
        height: Int,
        frames: List<RawFusionFrame>,
        activeArea: IntArray = intArrayOf(0, 0, width, height)
    ): RawFusionResult {
        require(width >= 64 && height >= 64 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 16_000_000)
        require(frames.size == 3)
        require(activeArea.size == 4)
        require(activeArea[0] >= 0 && activeArea[1] >= 0)
        require(activeArea[2] <= width && activeArea[3] <= height)
        require(activeArea[2] - activeArea[0] >= 64 && activeArea[3] - activeArea[1] >= 64)
        val bytes = width * height * 2
        val metadata = DoubleArray(3 * 14)
        frames.forEachIndexed { index, frame ->
            require(frame.pixels.isDirect && frame.pixels.order() == ByteOrder.nativeOrder())
            require(frame.pixels.capacity() == bytes && frame.pixels.position() == 0)
            require(frame.pixels.limit() == bytes)
            val m = frame.metadata
            require(m.blackLevels.size == 4 && m.noiseSlope.size == 4 && m.noiseIntercept.size == 4)
            require(m.whiteLevel.isFinite() && m.whiteLevel in 64.0..65535.0) {
                "Unsupported RAW white level"
            }
            require(m.exposureScale.isFinite() && m.exposureScale in 0.5..2.0) {
                "Unsupported RAW exposure ratio"
            }
            for (channel in 0..3) {
                val black = m.blackLevels[channel]
                val slope = m.noiseSlope[channel]
                val intercept = m.noiseIntercept[channel]
                require(black.isFinite() && black >= 0 && m.whiteLevel - black >= 32) {
                    "Unsupported RAW black level"
                }
                // Some devices report a valid shot-noise-only model (S > 0, O == 0).
                require(
                    slope.isFinite() && intercept.isFinite() &&
                        slope in 0.0..0.1 && intercept in 0.0..0.1 &&
                        (slope > 0 || intercept > 0)
                ) { "Invalid RAW noise profile" }
            }
            val offset = index * 14
            m.blackLevels.copyInto(metadata, offset)
            metadata[offset + 4] = m.whiteLevel
            metadata[offset + 5] = m.exposureScale
            m.noiseSlope.copyInto(metadata, offset + 6)
            m.noiseIntercept.copyInto(metadata, offset + 10)
        }
        val output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val stats = DoubleArray(12)
        val elapsed = measureNanoTime {
            val accepted = nativeFuse(
                width,
                height,
                frames.map { it.pixels }.toTypedArray(),
                metadata,
                output,
                stats,
                activeArea
            )
            check(accepted) {
                "RAW fusion rejected unsupported metadata or unavailable working memory"
            }
        }
        return RawFusionResult(
            pixels = output,
            baselineIndex = stats[0].toInt(),
            changedPixels = stats[2].toLong(),
            mergedPixels = stats[3].toLong(),
            rejectedPixels = stats[4].toLong(),
            acceptedFrames = stats[1].toInt(),
            shifts = IntArray(6) { stats[6 + it].toInt() },
            meanEffectiveSamples = stats[5],
            elapsedMs = elapsed / 1_000_000
        )
    }

    private external fun nativeFuse(
        width: Int,
        height: Int,
        frames: Array<ByteBuffer>,
        metadata: DoubleArray,
        output: ByteBuffer,
        stats: DoubleArray,
        activeArea: IntArray
    ): Boolean
}
