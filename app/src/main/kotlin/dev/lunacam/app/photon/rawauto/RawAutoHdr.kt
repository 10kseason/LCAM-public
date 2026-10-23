package dev.lunacam.app.photon.rawauto

import android.hardware.camera2.CaptureResult
import dev.lunacam.app.photon.raw.RawBurst
import kotlin.math.abs

/** Actual physical results gate HDR, not the requested bracket or logical-camera defaults. */
internal fun rawAutoHdrEvidence(burst: RawBurst): String? {
    if (!burst.hdrRequested || burst.frames.size != 3) return "single_frame"
    val reference = burst.frames.first().result
    val iso = reference.get(CaptureResult.SENSOR_SENSITIVITY) ?: return "missing_iso"
    val wb = reference.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return "missing_white_balance"
    val transform =
        reference.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return "missing_color"
    val focus = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)
    val timestamps = burst.frames.map { it.result.get(CaptureResult.SENSOR_TIMESTAMP) }
    if (timestamps.any { it == null } ||
        timestamps.distinct().size != 3
    ) {
        return "timestamp_mismatch"
    }
    val exposures = burst.frames.map { it.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0 }
    if (exposures.any { it <= 0 } || exposures[0].toDouble() / exposures[1] < 1.8 ||
        exposures[2].toDouble() / exposures[0] < 1.8
    ) {
        return "bracket_not_applied"
    }
    for (frame in burst.frames) {
        val result = frame.result
        val requestedTime =
            result.request.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                ?: return "missing_requested_exposure"
        val actualTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return "missing_exposure"
        if (abs(actualTime.toDouble() / requestedTime - 1) > 0.05) return "exposure_not_applied"
        if (result.get(CaptureResult.CONTROL_AE_MODE) != CaptureResult.CONTROL_AE_MODE_OFF ||
            result.get(CaptureResult.SENSOR_SENSITIVITY) != iso
        ) {
            return "exposure_not_locked"
        }
        val gains =
            result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return "missing_white_balance"
        for (channel in 0..3) {
            if (abs(gains.getComponent(channel) / wb.getComponent(channel) - 1f) > 0.02f) {
                return "white_balance_changed"
            }
        }
        val matrix = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return "missing_color"
        for (index in 0..8) {
            if (abs(
                    matrix.getElement(index % 3, index / 3).toFloat() -
                        transform.getElement(index % 3, index / 3).toFloat()
                ) >
                0.01f
            ) {
                return "color_changed"
            }
        }
        if (result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING ||
            (
                focus != null &&
                    abs(
                        (result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return "missing_focus") -
                            focus
                    ) >
                    0.05f
                )
        ) {
            return "focus_changed"
        }
        val profile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        if (profile?.size != 4 ||
            profile.any {
                !it.first.isFinite() || !it.second.isFinite() ||
                    it.first !in 0.0..0.1 || it.second !in 0.0..0.1 ||
                    (it.first == 0.0 && it.second == 0.0)
            }
        ) {
            return "noise_metadata_missing"
        }
    }
    return null
}
