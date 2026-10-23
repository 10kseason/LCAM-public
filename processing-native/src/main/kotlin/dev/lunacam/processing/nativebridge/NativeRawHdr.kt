package dev.lunacam.processing.nativebridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

data class RawHdrFusion(
    /** Native uint32, low16 normalized short-reference RAW, high16 zero. */
    val packedPixels: ByteBuffer,
    val referenceIndex: Int,
    val acceptedMask: Int,
    /** Candidate offsets in RAW pixels; all shifts preserve CFA parity. */
    val shifts: IntArray,
    val mergedPixels: Long,
    val rejectedCells: Long,
    /** Multiply the metered-baseline renderer gain by this actual exposure ratio. */
    val exposureGainScale: Float,
    val elapsedMs: Long
) {
    val acceptedFrames: Int get() = Integer.bitCount(acceptedMask)
    val outputBlackLevels: FloatArray get() = FloatArray(4)
    val outputWhiteLevel: Float get() = 65535f
}

/**
 * Opt-in RAW bracket fusion. Metadata exposureScale is actual time*ISO relative to the
 * metered baseline; unlike NativeRawFusion, ratios up to32 are allowed. All frames must
 * come from one physical sensor with frozen CFA, WB, lens shading and color calibration.
 * Use reference color metadata with black=0/white=65535 for this JPEG-only packed buffer.
 * No alignment or motion evidence means reference pixels; mergedPixels==0 lets the caller
 * choose its preserved metered baseline instead. Inputs are never modified.
 */
object NativeRawHdr {
    init {
        System.loadLibrary("lunacore")
    }

    fun fuse(
        width: Int,
        height: Int,
        frames: List<RawFusionFrame>,
        activeArea: IntArray = intArrayOf(0, 0, width, height)
    ): RawHdrFusion {
        require(width >= 64 && height >= 64 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 16_000_000 && frames.size == 3)
        require(activeArea.size == 4 && activeArea[0] >= 0 && activeArea[1] >= 0)
        require(activeArea[2] <= width && activeArea[3] <= height)
        require(activeArea[2] - activeArea[0] >= 64 && activeArea[3] - activeArea[1] >= 64)
        val bytes = width * height * 2
        val metadata = DoubleArray(42)
        frames.forEachIndexed { index, frame ->
            require(frame.pixels.isDirect && frame.pixels.order() == ByteOrder.nativeOrder())
            require(frame.pixels.capacity() == bytes && frame.pixels.position() == 0)
            require(frame.pixels.limit() == bytes)
            val m = frame.metadata
            require(m.blackLevels.size == 4 && m.noiseSlope.size == 4 && m.noiseIntercept.size == 4)
            require(m.whiteLevel.isFinite() && m.whiteLevel in 64.0..65535.0)
            require(m.exposureScale.isFinite() && m.exposureScale in (1.0 / 64)..64.0)
            repeat(4) { c ->
                require(m.blackLevels[c].isFinite() && m.blackLevels[c] >= 0)
                require(m.whiteLevel - m.blackLevels[c] >= 32)
                require(m.noiseSlope[c].isFinite() && m.noiseIntercept[c].isFinite())
                require(m.noiseSlope[c] in 0.0..0.1 && m.noiseIntercept[c] in 0.0..0.1)
                require(m.noiseSlope[c] > 0 || m.noiseIntercept[c] > 0)
            }
            val offset = index * 14
            m.blackLevels.copyInto(metadata, offset)
            metadata[offset + 4] = m.whiteLevel
            metadata[offset + 5] = m.exposureScale
            m.noiseSlope.copyInto(metadata, offset + 6)
            m.noiseIntercept.copyInto(metadata, offset + 10)
        }
        val shortest = frames.minOf { it.metadata.exposureScale }
        require(frames.maxOf { it.metadata.exposureScale } / shortest <= 32)
        val output = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val stats = LongArray(10)
        val elapsed = measureNanoTime {
            check(
                nativeFuse(
                    width,
                    height,
                    frames.map { it.pixels }.toTypedArray(),
                    metadata,
                    output,
                    stats,
                    activeArea
                )
            ) { "RAW HDR rejected invalid input" }
        }
        return RawHdrFusion(
            output,
            stats[0].toInt(),
            stats[1].toInt(),
            IntArray(6) { stats[4 + it].toInt() },
            stats[2],
            stats[3],
            (1.0 / frames[stats[0].toInt()].metadata.exposureScale).toFloat(),
            elapsed / 1_000_000
        )
    }

    private external fun nativeFuse(
        width: Int,
        height: Int,
        frames: Array<ByteBuffer>,
        metadata: DoubleArray,
        output: ByteBuffer,
        stats: LongArray,
        activeArea: IntArray
    ): Boolean
}
