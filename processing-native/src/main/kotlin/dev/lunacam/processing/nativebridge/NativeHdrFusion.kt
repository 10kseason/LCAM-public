package dev.lunacam.processing.nativebridge

import android.graphics.Bitmap
import android.graphics.ColorSpace
import kotlin.system.measureNanoTime

/** Aggregate fit diagnostics only; never contains image samples or coordinates. */
data class HdrFusionDiagnostics(
    val alignmentAnchors: Int = 0,
    val alignmentCost: Double = 1.0,
    val alignmentMargin: Double = 0.0,
    val calibrationSamples: Int = 0,
    val calibrationKnots: Int = 0,
    val calibrationInlierFraction: Double = 0.0,
    val calibrationRmse: Double = 0.0,
    val calibrationMinLuma: Double = 0.0,
    val calibrationMaxLuma: Double = 0.0
)

data class HdrFusionStats(
    val changedPixels: Long,
    val accepted: Boolean,
    val elapsedMs: Long,
    val shiftX: Int,
    val shiftY: Int,
    val reason: String,
    val diagnostics: HdrFusionDiagnostics = HdrFusionDiagnostics()
)

/** Two-exposure recovery. Inputs must share raster orientation and be decoded into encoded sRGB. */
object NativeHdrFusion {
    init {
        System.loadLibrary("lunacore")
    }

    /**
     * The caller owns both bitmaps. Only base is mutated; no files or bitmaps are created here.
     * exposureRatio is the measured base/short exposure ratio, including ISO gain.
     * A monotone JPEG response is fitted to matched, unclipped samples and validated on
     * independent samples. Highlight extrapolation is bounded; this is not RAW radiometry.
     * Incompatible or insufficient evidence falls back to base.
     */
    fun merge(base: Bitmap, short: Bitmap, exposureRatio: Double): HdrFusionStats {
        require(base !== short && !base.isRecycled && !short.isRecycled && base.isMutable)
        require(base.config == Bitmap.Config.ARGB_8888 && short.config == Bitmap.Config.ARGB_8888)
        require(base.width == short.width && base.height == short.height)
        require(base.width >= 64 && base.height >= 64)
        require(base.width.toLong() * base.height <= 16_000_000L)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        require(base.colorSpace == srgb && short.colorSpace == srgb) { "HDR requires sRGB input" }
        require(exposureRatio.isFinite() && exposureRatio in 1.5..8.0)
        val result = LongArray(5)
        val diagnostics = DoubleArray(9)
        val elapsed = measureNanoTime {
            nativeMerge(base, short, exposureRatio, result, diagnostics)
        } / 1_000_000
        val reason = when (result[4].toInt()) {
            0 -> "aligned"
            1 -> "no_highlight_evidence"
            2 -> "ambiguous_alignment"
            3 -> "inconsistent_alignment"
            4 -> "exposure_mismatch"
            5 -> "calibration_insufficient"
            6 -> "calibration_rejected"
            else -> "unknown_native_reason"
        }
        return HdrFusionStats(
            result[0],
            result[1] != 0L,
            elapsed,
            result[2].toInt(),
            result[3].toInt(),
            reason,
            HdrFusionDiagnostics(
                diagnostics[0].toInt(), diagnostics[1], diagnostics[2],
                diagnostics[3].toInt(), diagnostics[4].toInt(), diagnostics[5],
                diagnostics[6], diagnostics[7], diagnostics[8]
            )
        )
    }

    private external fun nativeMerge(
        base: Bitmap,
        short: Bitmap,
        exposureRatio: Double,
        result: LongArray,
        diagnostics: DoubleArray
    )
}
