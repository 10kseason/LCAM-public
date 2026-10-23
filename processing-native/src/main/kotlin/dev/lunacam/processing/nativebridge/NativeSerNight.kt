package dev.lunacam.processing.nativebridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

data class SerNightPlan(
    val baselineIndex: Int,
    val shifts: IntArray,
    val acceptedMask: Int,
    val tileSize: Int,
    val tileColumns: Int,
    val tileRows: Int,
    /** rhoEven, modelMseRatioEven, rhoOdd, modelMseRatioOdd; estimated, not calibrated. */
    val tileBounds: FloatArray,
    val activeArea: IntArray,
    val elapsedMs: Long,
    val reason: String
) {
    val acceptedFrames: Int get() = Integer.bitCount(acceptedMask)
}

data class SerNightFusion(
    /** Native-order uint32/pixel: low16 RAW DN; high16 EV/.35*65535. */
    val packedPixels: ByteBuffer,
    val changedPixels: Long,
    val liftedCells: Long,
    val elapsedMs: Long
)

object NativeSerNight {
    init {
        System.loadLibrary("lunacore")
    }

    fun prepare(
        width: Int,
        height: Int,
        frames: List<RawFusionFrame>,
        activeArea: IntArray = intArrayOf(0, 0, width, height)
    ): SerNightPlan {
        val metadata = metadata(width, height, frames)
        require(activeArea.size == 4)
        val columns = (width + 63) / 64
        val rows = (height + 63) / 64
        val bounds = FloatArray(columns * rows * 4)
        val stats = IntArray(8)
        val elapsed = measureNanoTime {
            check(
                nativePrepare(
                    width,
                    height,
                    buffers(frames),
                    metadata,
                    activeArea,
                    stats,
                    bounds
                )
            ) { "SER preparation rejected invalid input" }
        }
        return SerNightPlan(
            stats[0], stats.copyOfRange(2, 8), stats[1], 64,
            columns, rows, bounds, activeArea.copyOf(), elapsed / 1_000_000,
            if (frames.size == 1) {
                "single_frame"
            } else if (Integer.bitCount(stats[1]) >
                1
            ) {
                "estimated_shadow_evidence"
            } else {
                "reference_only"
            }
        )
    }

    fun fuse(
        width: Int,
        height: Int,
        frames: List<RawFusionFrame>,
        plan: SerNightPlan,
        enabled: Boolean = true
    ): SerNightFusion {
        val metadata = metadata(width, height, frames)
        require(plan.baselineIndex in 0..2 && plan.shifts.size == 6 && plan.activeArea.size == 4)
        require(plan.tileSize == 64 && plan.tileColumns == (width + 63) / 64)
        require(plan.tileRows == (height + 63) / 64)
        require(plan.tileBounds.size == plan.tileColumns * plan.tileRows * 4)
        val output = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val stats = LongArray(2)
        val planInts = intArrayOf(plan.baselineIndex, plan.acceptedMask, *plan.shifts)
        val elapsed = measureNanoTime {
            check(
                nativeFuse(
                    width, height, buffers(frames), metadata,
                    plan.activeArea, planInts, plan.tileBounds, output, stats, enabled
                )
            ) {
                "SER fusion rejected invalid input"
            }
        }
        return SerNightFusion(output, stats[0], stats[1], elapsed / 1_000_000)
    }

    internal fun metadata(width: Int, height: Int, frames: List<RawFusionFrame>): DoubleArray {
        require(width >= 64 && height >= 64 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 16_000_000 && frames.size in listOf(1, 3))
        return DoubleArray(42).also { packed ->
            repeat(3) { index ->
                val frame = frames[if (frames.size == 1) 0 else index]
                require(frame.pixels.isDirect && frame.pixels.order() == ByteOrder.nativeOrder())
                require(
                    frame.pixels.position() == 0 && frame.pixels.remaining() == width * height * 2
                )
                require(frame.pixels.capacity() == width * height * 2)
                val m = frame.metadata
                require(
                    m.blackLevels.size == 4 && m.noiseSlope.size == 4 && m.noiseIntercept.size == 4
                )
                val offset = index * 14
                m.blackLevels.copyInto(packed, offset)
                packed[offset + 4] = m.whiteLevel
                packed[offset + 5] = m.exposureScale
                m.noiseSlope.copyInto(packed, offset + 6)
                m.noiseIntercept.copyInto(packed, offset + 10)
            }
        }
    }

    private fun buffers(frames: List<RawFusionFrame>): Array<ByteBuffer> =
        Array(3) { frames[if (frames.size == 1) 0 else it].pixels }

    private external fun nativePrepare(
        width: Int,
        height: Int,
        frames: Array<ByteBuffer>,
        metadata: DoubleArray,
        activeArea: IntArray,
        stats: IntArray,
        bounds: FloatArray
    ): Boolean
    private external fun nativeFuse(
        width: Int,
        height: Int,
        frames: Array<ByteBuffer>,
        metadata: DoubleArray,
        activeArea: IntArray,
        plan: IntArray,
        bounds: FloatArray,
        output: ByteBuffer,
        stats: LongArray,
        enabled: Boolean
    ): Boolean
}
