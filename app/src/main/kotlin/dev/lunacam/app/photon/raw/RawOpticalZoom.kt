package dev.lunacam.app.photon.raw

import kotlin.math.sqrt

/** Full-sensor optical steps: no digital crop is implied by the accumulated pinch position. */
internal object RawOpticalZoom {
    fun ratio(focal: Float, sensorWidth: Float, baseFocal: Float, baseWidth: Float): Float? {
        if (listOf(focal, sensorWidth, baseFocal, baseWidth).any { !it.isFinite() || it <= 0f }) {
            return null
        }
        return (focal / sensorWidth) / (baseFocal / baseWidth)
    }

    fun select(ratios: List<Float>, current: Int, requested: Float): Int {
        require(ratios.isNotEmpty() && current in ratios.indices)
        if (!requested.isFinite() || requested <= 0f) return current
        var selected = current
        // Geometric midpoint fits multiplicative pinch. A dead band prevents boundary flapping.
        while (selected < ratios.lastIndex &&
            requested > sqrt(ratios[selected] * ratios[selected + 1]) * 1.08f
        ) {
            selected++
        }
        while (selected > 0 &&
            requested < sqrt(ratios[selected - 1] * ratios[selected]) / 1.08f
        ) {
            selected--
        }
        return selected
    }
}
