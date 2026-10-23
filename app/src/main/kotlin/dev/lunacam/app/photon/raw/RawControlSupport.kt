package dev.lunacam.app.photon.raw

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/** Physical controls are offered only when they can be explicitly addressed to that sensor. */
internal fun rawControlSupport(
    info: CameraCharacteristics,
    physical: Boolean,
    physicalKeys: List<CaptureRequest.Key<*>>
): RawControlCapabilities {
    fun supports(vararg keys: CaptureRequest.Key<*>): Boolean = keys.all {
        it in info.availableCaptureRequestKeys && (!physical || it in physicalKeys)
    }
    val exposure = info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val iso = info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val frameMax = info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0
    val maxExposure = minOf(exposure?.upper ?: 0, frameMax, 1_000_000_000L)
    val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in
        (info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()) &&
        supports(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.SENSOR_FRAME_DURATION
        ) && exposure != null && iso != null && maxExposure >= exposure.lower
    val afModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
    val manualFocus = CaptureRequest.CONTROL_AF_MODE_OFF in afModes && supports(
        CaptureRequest.CONTROL_AF_MODE,
        CaptureRequest.LENS_FOCUS_DISTANCE
    )
    val ev = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val evSupported = supports(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
    val wb = if (supports(CaptureRequest.CONTROL_AWB_MODE)) {
        val modes = info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        RawWhiteBalance.entries.filter { it.mode in modes }
    } else {
        listOf(RawWhiteBalance.AUTO)
    }
    return RawControlCapabilities(
        minExposureNs = if (manual) requireNotNull(exposure).lower else 0,
        maxExposureNs = if (manual) maxExposure else 0,
        minIso = if (manual) requireNotNull(iso).lower else 0,
        maxIso = if (manual) requireNotNull(iso).upper else 0,
        maxFocusDiopters = if (manualFocus) {
            info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?: 0f
        } else {
            0f
        },
        minEvIndex = if (evSupported) ev?.lower ?: 0 else 0,
        maxEvIndex = if (evSupported) ev?.upper ?: 0 else 0,
        evStep = if (evSupported) {
            info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
                ?: 0f
        } else {
            0f
        },
        whiteBalances = wb.ifEmpty { listOf(RawWhiteBalance.AUTO) },
        tapAf = CaptureRequest.CONTROL_AF_MODE_AUTO in afModes &&
            (info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 && supports(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_REGIONS
            ),
        tapAe =
        (info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0 &&
            supports(CaptureRequest.CONTROL_AE_REGIONS)
    )
}
