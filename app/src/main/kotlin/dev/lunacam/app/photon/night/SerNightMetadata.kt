package dev.lunacam.app.photon.night

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import dev.lunacam.app.photon.raw.RawBurst
import dev.lunacam.app.photon.raw.RawFrame
import dev.lunacam.processing.nativebridge.RawFusionFrame
import dev.lunacam.processing.nativebridge.RawFusionMetadata
import dev.lunacam.processing.nativebridge.SerNightColor
import dev.lunacam.processing.nativebridge.SerNightShading
import java.nio.ByteOrder

internal data class NightRawInputs(
    val frames: List<RawFusionFrame>,
    val activeArea: IntArray,
    val evidenceAllowed: Boolean,
    val evidenceReason: String
)

/** Physical capture results only. Missing optional noise evidence cannot enable SER. */
internal fun nightRawInputs(
    burst: RawBurst,
    requireNightResolution: Boolean = true
): NightRawInputs {
    require(burst.frames.size == 1 || burst.frames.size == 3)
    val first = burst.frames.first()
    if (requireNightResolution) {
        require(first.width == 4080 && first.height == 3060) { "NightRequires1248Mp" }
    } else {
        require(
            first.width >= 64 && first.height >= 64 && first.width % 2 == 0 && first.height % 2 == 0
        )
        require(first.width.toLong() * first.height <= 16_000_000)
    }
    val info = burst.characteristics
    require(info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in 0..3)
    val staticWhite = requireNotNull(info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
    val staticBlack = requireNotNull(info.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN))
    val baseExposure = rawExposure(first)
    val physical = burst.frames.map { it.physicalId }
    require(
        physical.all {
            it != null
        } && physical.distinct().size == 1
    ) { "PhysicalSourceMismatch" }
    val timestamps = burst.frames.map { it.result.get(CaptureResult.SENSOR_TIMESTAMP) }
    var reason = when {
        burst.frames.size == 1 -> "single_frame"
        timestamps.any { it == null } || timestamps.distinct().size != burst.frames.size ->
            "timestamp_evidence_missing"
        else -> "ready"
    }
    val frames = burst.frames.map { frame ->
        require(frame.width == first.width && frame.height == first.height)
        require(frame.pixels.isDirect && frame.pixels.order() == ByteOrder.nativeOrder())
        require(
            frame.pixels.position() == 0 && frame.pixels.limit() == first.width * first.height * 2
        )
        val dynamicWhite = frame.result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        require(dynamicWhite == null || dynamicWhite == staticWhite) { "DynamicWhiteMismatch" }
        val black = frame.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        require(black == null || black.size == 4)
        val blackLevels = DoubleArray(4) { channel ->
            black?.get(channel)?.toDouble()
                ?: staticBlack.getOffsetForIndex(channel % 2, channel / 2).toDouble()
        }
        require(blackLevels.all { it.isFinite() && it >= 0 && staticWhite - it >= 32 })
        val profile = frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        val validNoise = profile?.size == 4 && profile.all {
            it.first.isFinite() && it.second.isFinite() && it.first in 0.0..0.1 &&
                it.second in 0.0..0.1 && (it.first > 0 || it.second > 0)
        }
        if (!validNoise && reason == "ready") reason = "noise_metadata_missing"
        val scale = rawExposure(frame) / baseExposure
        if ((!scale.isFinite() || scale !in 0.98..1.02) && reason == "ready") {
            reason = "exposure_evidence_mismatch"
        }
        RawFusionFrame(
            frame.pixels,
            RawFusionMetadata(
                blackLevels,
                staticWhite.toDouble(),
                scale,
                DoubleArray(4) { if (validNoise) profile[it].first else 0.0 },
                DoubleArray(4) { if (validNoise) profile[it].second else 0.0 }
            )
        )
    }
    val active = info.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        ?: info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val area = active?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        ?: intArrayOf(0, 0, first.width, first.height)
    require(area[0] >= 0 && area[1] >= 0 && area[2] <= first.width && area[3] <= first.height)
    require(area[2] - area[0] >= 64 && area[3] - area[1] >= 64)
    return NightRawInputs(frames, area, reason == "ready", reason)
}

/** AWB gains precede the sensor-RGB to linear-sRGB matrix, as specified by Camera2. */
internal fun nightColor(burst: RawBurst, index: Int, metadata: RawFusionMetadata): SerNightColor {
    val frame = burst.frames[index]
    val info = burst.characteristics
    val gains = requireNotNull(frame.result.get(CaptureResult.COLOR_CORRECTION_GAINS)) {
        "MissingColorGains"
    }
    val transform = requireNotNull(frame.result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)) {
        "MissingColorTransform"
    }
    val wb = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
    require(wb.all { it.isFinite() && it in 0.1f..16f }) { "UnsupportedColorGains" }
    val green = (wb[1] + wb[2]) * 0.5f
    for (channel in wb.indices) wb[channel] /= green
    val matrix = FloatArray(9) { transform.getElement(it % 3, it / 3).toFloat() }
    require(matrix.all { it.isFinite() && kotlin.math.abs(it) <= 16f }) {
        "UnsupportedColorTransform"
    }
    val determinant = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
        matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
        matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
    require(determinant.isFinite() && kotlin.math.abs(determinant) > 0.00001f)
    // The result map is the remaining RAW correction even when hardware has applied some
    // shading already. Skipping it or treating a missing map as identity can change corner color.
    val map = requireNotNull(
        frame.result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
    ) {
        "MissingLensShadingMap"
    }
    require(map.columnCount in 1..64 && map.rowCount in 1..64)
    val shadingGains = FloatArray(map.columnCount * map.rowCount * 4)
    map.copyGainFactors(shadingGains, 0)
    require(shadingGains.all { it.isFinite() && it in 1f..16f }) { "UnsupportedShadingGain" }
    val active = requireNotNull(
        info.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    ) { "MissingShadingActiveArea" }
    require(
        active.left >= 0 && active.top >= 0 && active.right <= frame.width &&
            active.bottom <= frame.height && active.width() > 0 && active.height() > 0
    )
    return SerNightColor(
        cfa = requireNotNull(
            burst.characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        ),
        blackLevels = FloatArray(4) { metadata.blackLevels[it].toFloat() },
        whiteLevel = metadata.whiteLevel.toFloat(),
        whiteBalance = wb,
        sensorToSrgb = matrix,
        shading = SerNightShading(
            map.columnCount,
            map.rowCount,
            shadingGains,
            intArrayOf(active.left, active.top, active.right, active.bottom)
        )
    )
}

private fun rawExposure(frame: RawFrame): Double {
    val time = requireNotNull(frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
    val iso = requireNotNull(frame.result.get(CaptureResult.SENSOR_SENSITIVITY))
    require(time > 0 && iso > 0)
    return time.toDouble() * iso
}
