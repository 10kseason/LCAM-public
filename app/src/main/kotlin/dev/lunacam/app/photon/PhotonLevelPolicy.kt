package dev.lunacam.app.photon

import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/** Display-relative horizon geometry, independent of Android sensors and camera output. */
internal object PhotonLevelPolicy {
    const val ALIGNED_DEGREES = 1.5f

    fun horizonDegrees(x: Float, y: Float, z: Float, displayDegrees: Int): Float? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        if (displayDegrees !in 0..270 || displayDegrees % 90 != 0) return null
        val projected = hypot(x.toDouble(), y.toDouble())
        val magnitude = hypot(projected, z.toDouble())
        // Accelerometer fallback includes motion. Reject large non-gravity acceleration and
        // near-flat poses where projected gravity cannot define a stable screen horizon.
        if (magnitude !in 7.0..12.5 || projected < 2.5) return null
        val degrees = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        return normalize(degrees - displayDegrees)
    }

    fun smooth(previous: Float?, next: Float, elapsedMs: Long): Float? {
        if (!next.isFinite()) return null
        if (previous == null || !previous.isFinite()) return normalize(next)
        val alpha = (1.0 - exp(-elapsedMs.coerceIn(0, 1_000) / 180.0)).toFloat()
        // Horizon lines repeat every 180 degrees. Interpolate across the nearest boundary.
        return normalize(previous + normalize(next - previous) * alpha)
    }

    private fun normalize(degrees: Float): Float = (((degrees + 90f) % 180f + 180f) % 180f) - 90f
}
