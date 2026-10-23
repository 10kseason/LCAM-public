package dev.lunacam.app.photon.rawauto

import dev.lunacam.app.photon.raw.RawControlCapabilities
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Deliberate starting bias, not a measurement or guarantee of sky headroom. */
internal fun rawAutoEvIndex(support: RawControlCapabilities): Int = if (support.ev) {
    (-2f / 3f / support.evStep).roundToInt()
        .coerceIn(support.minEvIndex, support.maxEvIndex)
} else {
    0
}

internal fun rawAutoCompensationBias(support: RawControlCapabilities): Float =
    if (support.ev) rawAutoEvIndex(support) * support.evStep else 0f

internal fun rawAutoExposureGain(compensationBiasEv: Float): Float {
    require(compensationBiasEv.isFinite() && compensationBiasEv in -2f..0f)
    return 2.0.pow(-compensationBiasEv.toDouble()).toFloat()
}

internal data class RawAutoTone(val exposureGain: Float, val strength: Float, val noiseFloor: Float)

/** A conservative device-model estimate, never a measured or calibrated scene SNR. */
internal fun rawAutoTone(
    appliedEv: Float,
    strength: Float,
    noiseSlopes: DoubleArray?,
    noiseOffsets: DoubleArray?,
    colorGainBound: Float,
    compensationBiasEv: Float = 0f
): RawAutoTone {
    // Camera-supported user EV may be positive or below -2. It changes captured light and must
    // survive development; only the fixed protective startup bias is compensated here.
    require(appliedEv.isFinite())
    require(strength.isFinite() && strength in 0f..0.6f)
    require(colorGainBound.isFinite() && colorGainBound > 0f)
    val exposure = rawAutoExposureGain(compensationBiasEv)
    val valid = noiseSlopes?.size == 4 && noiseOffsets?.size == 4 &&
        noiseSlopes.indices.all {
            noiseSlopes[it].isFinite() && noiseOffsets[it].isFinite() &&
                noiseSlopes[it] in 0.0..0.1 && noiseOffsets[it] in 0.0..0.1 &&
                (noiseSlopes[it] > 0 || noiseOffsets[it] > 0)
        }
    val floor = if (valid) {
        sqrt((0..3).maxOf { noiseSlopes[it] * 0.02 + noiseOffsets[it] }).toFloat() *
            colorGainBound * exposure
    } else {
        0.01f
    }
    return RawAutoTone(
        exposure,
        if (valid) strength else minOf(strength, 0.35f),
        floor.coerceIn(0.001f, 0.1f)
    )
}
