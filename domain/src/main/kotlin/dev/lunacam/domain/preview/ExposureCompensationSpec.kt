package dev.lunacam.domain.preview

import kotlin.math.roundToInt

/**
 * Device advertised AE compensation range and step (FR-PRV-006).
 *
 * The step arrives from Camera2 as a rational, so it is kept as a numerator/denominator pair
 * instead of a lossy Double. Every user request is quantized onto that grid; the app never sends
 * an EV the device did not advertise.
 */
data class ExposureCompensationSpec(
    val minCompensationStep: Int,
    val maxCompensationStep: Int,
    val stepNumerator: Int,
    val stepDenominator: Int
) {
    val isSupported: Boolean
        get() =
            stepDenominator > 0 &&
                stepNumerator > 0 &&
                maxCompensationStep > minCompensationStep

    val stepEv: Double
        get() = if (stepDenominator <= 0) 0.0 else stepNumerator.toDouble() / stepDenominator

    val minEv: Double
        get() = if (isSupported) minCompensationStep * stepEv else 0.0

    val maxEv: Double
        get() = if (isSupported) maxCompensationStep * stepEv else 0.0

    /** Returns the device step index closest to [requestedEv], clamped to the advertised range. */
    fun quantize(requestedEv: Double): Int {
        if (!isSupported || !requestedEv.isFinite()) return 0
        val rawSteps = requestedEv / stepEv
        if (!rawSteps.isFinite()) return 0
        // roundToInt documents ties as rounding toward positive infinity and saturates on
        // overflow, so an absurd request cannot wrap around into the opposite EV.
        return rawSteps.roundToInt().coerceIn(minCompensationStep, maxCompensationStep)
    }

    fun evForStep(step: Int): Double {
        if (!isSupported) return 0.0
        return step.coerceIn(minCompensationStep, maxCompensationStep) * stepEv
    }

    companion object {
        /** Devices that advertise a zero-width range expose no EV control at all. */
        val UNSUPPORTED = ExposureCompensationSpec(0, 0, 0, 1)
    }
}
