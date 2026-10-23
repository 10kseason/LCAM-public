package dev.lunacam.app.photon

import kotlin.math.roundToInt

enum class PhotonFocusStatus { FOCUSING, FOCUSED, FAILED, LOCKED }

data class PhotonFocusFeedback(
    val xFraction: Float,
    val yFraction: Float,
    val status: PhotonFocusStatus
)

/** Clamp in EV before quantization, including finite inputs much larger than Int.MAX_VALUE. */
internal fun photonExposureIndex(ev: Float, step: Float, minimum: Int, maximum: Int): Int? {
    if (!ev.isFinite() || !step.isFinite() || step <= 0f || minimum > maximum) return null
    return (ev.toDouble() / step).coerceIn(minimum.toDouble(), maximum.toDouble()).roundToInt()
}
