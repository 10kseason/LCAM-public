package dev.lunacam.app.photon.raw

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong

enum class RawWhiteBalance(val mode: Int, val label: String) {
    AUTO(1, "자동"),
    INCANDESCENT(2, "백열등"),
    FLUORESCENT(3, "형광등"),
    WARM_FLUORESCENT(4, "따뜻한 형광등"),
    DAYLIGHT(5, "주광"),
    CLOUDY(6, "흐림"),
    TWILIGHT(7, "해질녘"),
    SHADE(8, "그늘")
}

data class RawControlCapabilities(
    val minExposureNs: Long = 0,
    val maxExposureNs: Long = 0,
    val minIso: Int = 0,
    val maxIso: Int = 0,
    val maxFocusDiopters: Float = 0f,
    val minEvIndex: Int = 0,
    val maxEvIndex: Int = 0,
    val evStep: Float = 0f,
    val whiteBalances: List<RawWhiteBalance> = listOf(RawWhiteBalance.AUTO),
    val tapAf: Boolean = false,
    val tapAe: Boolean = false
) {
    val manualExposure: Boolean get() = minExposureNs > 0 && maxExposureNs >= minExposureNs &&
        minIso > 0 &&
        maxIso >= minIso
    val manualFocus: Boolean get() = maxFocusDiopters.isFinite() && maxFocusDiopters > 0f
    val ev: Boolean get() = evStep.isFinite() && evStep > 0f && minEvIndex < maxEvIndex
}

data class RawControls(
    val manualExposure: Boolean = false,
    val exposureNs: Long = 8_333_333,
    val iso: Int = 100,
    val focusDiopters: Float? = null,
    val evIndex: Int = 0,
    val whiteBalance: RawWhiteBalance = RawWhiteBalance.AUTO
) {
    fun bounded(capabilities: RawControlCapabilities): RawControls = copy(
        manualExposure = manualExposure && capabilities.manualExposure,
        exposureNs = if (capabilities.manualExposure) {
            exposureNs.coerceIn(
                capabilities.minExposureNs,
                capabilities.maxExposureNs
            )
        } else {
            8_333_333
        },
        iso = if (capabilities.manualExposure) {
            iso.coerceIn(
                capabilities.minIso,
                capabilities.maxIso
            )
        } else {
            100
        },
        focusDiopters = focusDiopters?.takeIf {
            it.isFinite() && capabilities.manualFocus
        }?.coerceIn(0f, capabilities.maxFocusDiopters),
        evIndex = if (capabilities.ev) {
            evIndex.coerceIn(
                capabilities.minEvIndex,
                capabilities.maxEvIndex
            )
        } else {
            0
        },
        whiteBalance =
        whiteBalance.takeIf { it in capabilities.whiteBalances } ?: RawWhiteBalance.AUTO
    )
}

data class RawControlObservation(
    val exposureNs: Long?,
    val iso: Int?,
    val focusDiopters: Float?,
    val evIndex: Int?,
    val aeMode: Int?,
    val awbMode: Int?,
    val afMode: Int?,
    val afState: Int?
)

/** Public Camera2 mode values: AE OFF=0 / ON=1, AF OFF=0. Allow sensor quantization, not ignored settings. */
internal fun RawControlObservation.matches(
    requested: RawControls,
    support: RawControlCapabilities
): Boolean {
    if (aeMode != if (requested.manualExposure) 0 else 1) return false
    if (requested.manualExposure) {
        val exposure = exposureNs ?: return false
        val sensitivity = iso ?: return false
        if (abs(exposure - requested.exposureNs) >
            max(50_000.0, requested.exposureNs * .05)
        ) {
            return false
        }
        if (abs(sensitivity.toLong() - requested.iso) > max(1.0, requested.iso * .1)) return false
    } else if (support.ev && evIndex != requested.evIndex) {
        return false
    }
    if (support.whiteBalances.size > 1 && awbMode != requested.whiteBalance.mode) return false
    requested.focusDiopters?.let { focus ->
        val reported = focusDiopters ?: return false
        if (afMode != 0 || !reported.isFinite() || abs(reported - focus) > .1f) return false
    }
    return true
}

internal fun rawLogValue(fraction: Float, minimum: Long, maximum: Long): Long {
    require(minimum > 0 && maximum >= minimum)
    val f = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    return exp(
        ln(minimum.toDouble()) + f * ln(maximum.toDouble() / minimum)
    ).roundToLong().coerceIn(minimum, maximum)
}

internal fun rawLogFraction(value: Long, minimum: Long, maximum: Long): Float {
    require(minimum > 0 && maximum >= minimum)
    if (minimum == maximum) return 0f
    return (
        ln(value.coerceIn(minimum, maximum).toDouble() / minimum) /
            ln(maximum.toDouble() / minimum)
        ).toFloat().coerceIn(0f, 1f)
}

/** Inverse of sensor rotation, front mirroring and center-fill preview crop. */
internal fun rawMeteringPoint(
    x: Float,
    y: Float,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    sensorOrientation: Int,
    displayRotation: Int,
    front: Boolean
): Pair<Float, Float>? {
    if (!x.isFinite() || !y.isFinite() || viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 ||
        bufferHeight <= 0 ||
        x !in 0f..viewWidth.toFloat() || y !in 0f..viewHeight.toFloat()
    ) {
        return null
    }
    val rotation =
        (sensorOrientation + (if (front) displayRotation else -displayRotation) + 360) % 360
    require(rotation in setOf(0, 90, 180, 270))
    val width = if (rotation % 180 == 0) bufferWidth else bufferHeight
    val height = if (rotation % 180 == 0) bufferHeight else bufferWidth
    val scale = max(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
    var u = (x - (viewWidth - width * scale) / 2f) / (width * scale)
    val v = (y - (viewHeight - height * scale) / 2f) / (height * scale)
    if (front) u = 1f - u
    return when (rotation) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }
}
