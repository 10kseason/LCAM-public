package dev.lunacam.camera.camera2.preview

import android.graphics.Rect
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.view.Surface
import dev.lunacam.domain.preview.MeteringRegion

/** The immutable inputs a preview request is rebuilt from. */
internal data class PreviewRequestSpec(
    val exposureCompensationStep: Int,
    val meteringRegion: MeteringRegion?,
    val triggerAutoFocus: Boolean = false
)

/**
 * Builds preview requests from an immutable spec.
 *
 * DESIGN.md section 9.10 forbids reusing a capture builder as the preview builder, so nothing is
 * cached here: every request is created fresh from [PreviewRequestSpec] and the builder never
 * leaves this object.
 */
internal object PreviewRequestFactory {
    fun build(
        device: CameraDevice,
        surface: Surface,
        hardware: PreviewCameraHardware,
        spec: PreviewRequestSpec
    ): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        hardware.continuousAfMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }

        if (hardware.exposureCompensation.isSupported) {
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                spec.exposureCompensationStep
            )
        }

        spec.meteringRegion?.let { region ->
            val rectangles = arrayOf(region.toMeteringRectangle())
            if (hardware.maxAfRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, rectangles)
            }
            if (hardware.maxAeRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, rectangles)
            }
        }

        if (spec.triggerAutoFocus && hardware.continuousAfMode != null) {
            builder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
        }

        return builder.build()
    }
}

private fun MeteringRegion.toMeteringRectangle(): MeteringRectangle = MeteringRectangle(
    Rect(rect.left, rect.top, rect.right, rect.bottom),
    weight.coerceIn(0, MeteringRectangle.METERING_WEIGHT_MAX)
)
