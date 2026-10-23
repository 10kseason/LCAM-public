package dev.lunacam.app.photon.raw

import kotlin.math.abs
import kotlin.math.max

/** Short continuity window for the same scene; a later shot cannot renew the original window. */
internal object NightPreviewHoldPolicy {
    const val DURATION_MS = 2_000L

    data class Calibration(val focus: Float, val gains: FloatArray, val transform: FloatArray)

    fun active(startedMs: Long, nowMs: Long): Boolean =
        startedMs >= 0 && nowMs >= startedMs && nowMs - startedMs < DURATION_MS

    fun usable(
        startedMs: Long,
        nowMs: Long,
        previewAgeMs: Long,
        sameRequest: Boolean,
        newerSensorResult: Boolean
    ): Boolean = active(startedMs, nowMs) && previewAgeMs in 0L..250L &&
        sameRequest && newerSensorResult

    fun matches(expected: Calibration, actual: Calibration): Boolean {
        if (!expected.focus.isFinite() || !actual.focus.isFinite() ||
            expected.focus < 0 || actual.focus < 0 || abs(expected.focus - actual.focus) > 0.01f
        ) {
            return false
        }
        if (expected.gains.size != 4 || actual.gains.size != 4 ||
            expected.transform.size != 9 || actual.transform.size != 9
        ) {
            return false
        }
        if ((expected.gains + actual.gains).any { !it.isFinite() || it <= 0 }) return false
        return close(expected.gains, actual.gains) && close(expected.transform, actual.transform)
    }

    private fun close(expected: FloatArray, actual: FloatArray): Boolean =
        expected.indices.all { index ->
            val a = expected[index]
            val b = actual[index]
            a.isFinite() && b.isFinite() && abs(a - b) <= max(0.005f, abs(a) * 0.01f)
        }
}
