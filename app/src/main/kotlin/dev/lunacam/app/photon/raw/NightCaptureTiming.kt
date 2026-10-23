package dev.lunacam.app.photon.raw

import org.json.JSONObject

data class NightCaptureTiming(
    val startedElapsedMs: Long,
    val requestedFrames: Int,
    val submittedFrames: Int = 0,
    val submitElapsedMs: Long? = null,
    val rawReceivedElapsedMs: Long? = null,
    val previewRestoredElapsedMs: Long? = null,
    val exposureCapNs: Long = 50_000_000L,
    val previewAgeMs: Long? = null,
    val referenceExposureNs: Long? = null,
    val requestedExposureNs: Long? = null,
    val referenceIso: Int? = null,
    val requestedIso: Int? = null,
    val requestedFrameDurationNs: Long? = null,
    val exposureLimited: Boolean = false,
    val focusWhiteBalanceHoldStartedMs: Long? = null,
    val fallbackReason: String? = null,
    val failure: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("startedElapsedMs", startedElapsedMs).put("requestedFrames", requestedFrames)
        .put("submittedFrames", submittedFrames)
        .put("submitElapsedMs", submitElapsedMs ?: JSONObject.NULL)
        .put("rawReceivedElapsedMs", rawReceivedElapsedMs ?: JSONObject.NULL)
        .put("previewRestoredElapsedMs", previewRestoredElapsedMs ?: JSONObject.NULL)
        .put("exposureCapNs", exposureCapNs).put("previewAgeMs", previewAgeMs ?: JSONObject.NULL)
        .put("referenceExposureNs", referenceExposureNs ?: JSONObject.NULL)
        .put("requestedExposureNs", requestedExposureNs ?: JSONObject.NULL)
        .put("referenceIso", referenceIso ?: JSONObject.NULL)
        .put("requestedIso", requestedIso ?: JSONObject.NULL)
        .put("requestedFrameDurationNs", requestedFrameDurationNs ?: JSONObject.NULL)
        .put("exposureLimited", exposureLimited)
        .put("focusWhiteBalanceHoldStartedMs", focusWhiteBalanceHoldStartedMs ?: JSONObject.NULL)
        .put("fallbackReason", fallbackReason ?: JSONObject.NULL)
        .put("failure", failure ?: JSONObject.NULL)
}

data class NightReadiness(
    val ready: Boolean,
    val reason: String,
    val previewAgeMs: Long? = null,
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val hasColorGains: Boolean = false,
    val hasColorTransform: Boolean = false,
    val physicalId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val heldFocusWhiteBalance: Boolean = false
)

internal object NightAcquisitionPolicy {
    const val MAX_EXPOSURE_NS = 50_000_000L
    const val MAX_PREVIEW_AGE_MS = 250L

    data class Exposure(val exposureNs: Long, val iso: Int, val limited: Boolean)

    fun exposure(
        referenceNs: Long,
        referenceIso: Int,
        minExposureNs: Long,
        maxExposureNs: Long,
        minIso: Int,
        maxIso: Int
    ): Exposure {
        require(referenceNs > 0 && referenceIso > 0)
        require(minExposureNs > 0 && maxExposureNs >= minExposureNs)
        require(minIso > 0 && maxIso >= minIso)
        val cap = minOf(MAX_EXPOSURE_NS, maxExposureNs)
        require(minExposureNs <= cap)
        val exposure = referenceNs.coerceIn(minExposureNs, cap)
        // Double avoids Long multiplication overflow; ISO is gain, never extra photon evidence.
        val desiredIso = referenceIso.toDouble() * (referenceNs.toDouble() / exposure)
        val iso = kotlin.math.round(desiredIso).coerceIn(minIso.toDouble(), maxIso.toDouble())
            .toInt()
        return Exposure(exposure, iso, desiredIso > maxIso)
    }

    fun frameCount(requested: Int, previewAgeMs: Long, frameNs: Long, elapsedMs: Long): Int {
        require(requested == 1 || requested == 3)
        if (requested == 1) return 1
        if (previewAgeMs !in 0..100 || frameNs <= 0 || elapsedMs !in 0..1_000) return 1
        // This is a submission guard, not a measured latency claim. Once submitted, never truncate.
        val predictedMs = elapsedMs + 3 * (frameNs / 1_000_000L + 90L) + 300L
        return if (predictedMs <= 1_000) 3 else 1
    }
}
