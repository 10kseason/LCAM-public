package dev.lunacam.camera.camera2.preview

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import android.view.SurfaceHolder
import dev.lunacam.domain.capability.CameraCapabilityProfile
import dev.lunacam.domain.capability.CameraRoleAssignment
import dev.lunacam.domain.capability.CameraRoleEvidence
import dev.lunacam.domain.capability.CameraRoleScorer
import dev.lunacam.domain.capability.LensFacing
import dev.lunacam.domain.preview.ExposureCompensationSpec
import dev.lunacam.domain.preview.IntRect
import dev.lunacam.domain.preview.IntSize
import dev.lunacam.domain.preview.PreviewCameraCandidate

/** Everything the preview session needs from one camera's static characteristics. */
internal data class PreviewCameraHardware(
    val cameraId: String,
    val activeArray: IntRect,
    val sensorOrientationDegrees: Int,
    val mirrored: Boolean,
    val previewSizes: List<IntSize>,
    val exposureCompensation: ExposureCompensationSpec,
    val continuousAfMode: Int?,
    val maxAfRegions: Int,
    val maxAeRegions: Int
)

/**
 * Reads the preview-relevant characteristics.
 *
 * Every key is treated as nullable (FR-CAP-003). The [cameraKey] carried into the domain is the
 * real camera id so the preview can be opened; the redacted `camera-N` alias stays an
 * export-only identifier of the P1 capability report.
 */
internal class PreviewCameraHardwareReader(private val cameraManager: CameraManager) {
    fun candidates(): List<PreviewCameraCandidate> {
        val profiles =
            cameraIds().mapNotNull { cameraId ->
                val characteristics = characteristicsOrNull(cameraId) ?: return@mapNotNull null
                minimalProfile(cameraId, characteristics)
            }
        if (profiles.isEmpty()) return emptyList()

        return CameraRoleScorer.assignRoles(profiles).map { profile ->
            PreviewCameraCandidate(
                cameraKey = profile.cameraAlias,
                evidence = profile.roleEvidence,
                assignment = profile.roleAssignment
            )
        }
    }

    fun read(cameraId: String): PreviewCameraHardware? {
        val characteristics = characteristicsOrNull(cameraId) ?: return null
        val activeArray =
            characteristics.readOrNull(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )?.toIntRect() ?: return null
        val streamMap =
            characteristics.readOrNull(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return null
        val previewSizes =
            try {
                streamMap.getOutputSizes(SurfaceHolder::class.java).orEmpty().map(Size::toIntSize)
            } catch (error: RuntimeException) {
                return null
            }
        if (previewSizes.isEmpty()) return null

        val facing = characteristics.readOrNull(CameraCharacteristics.LENS_FACING)
        return PreviewCameraHardware(
            cameraId = cameraId,
            activeArray = activeArray,
            sensorOrientationDegrees =
            characteristics.readOrNull(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            mirrored = facing == CameraMetadata.LENS_FACING_FRONT,
            previewSizes = previewSizes,
            exposureCompensation = characteristics.readExposureCompensation(),
            continuousAfMode = characteristics.readContinuousAfMode(),
            maxAfRegions =
            characteristics.readOrNull(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions =
            characteristics.readOrNull(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        )
    }

    private fun cameraIds(): List<String> = try {
        cameraManager.cameraIdList.sorted()
    } catch (error: RuntimeException) {
        emptyList()
    }

    private fun characteristicsOrNull(cameraId: String): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(cameraId)
    } catch (error: RuntimeException) {
        null
    } catch (error: android.hardware.camera2.CameraAccessException) {
        null
    }

    /**
     * Only [CameraCapabilityProfile.roleEvidence] and the alias drive [CameraRoleScorer], so the
     * preview path reuses the P1 scorer instead of duplicating its rules.
     */
    private fun minimalProfile(
        cameraId: String,
        characteristics: CameraCharacteristics
    ): CameraCapabilityProfile {
        val sensorSize = characteristics.readOrNull(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val capabilities =
            characteristics.readOrNull(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()
        return CameraCapabilityProfile(
            cameraAlias = cameraId,
            physicalCameraAliases = emptyList(),
            roleEvidence =
            CameraRoleEvidence(
                lensFacing =
                when (characteristics.readOrNull(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
                    CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
                    CameraMetadata.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                    else -> LensFacing.UNKNOWN
                },
                focalLengthsMm =
                characteristics.readOrNull(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )?.map(Float::toDouble).orEmpty(),
                sensorWidthMm = sensorSize?.width?.toDouble(),
                sensorHeightMm = sensorSize?.height?.toDouble(),
                hasRawCapability =
                capabilities.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
                ),
                isLogicalMultiCamera =
                capabilities.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ),
                hardwareLevel =
                characteristics.readOrNull(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                )?.let(::hardwareLevelName)
            ),
            roleAssignment = CameraRoleAssignment.UNASSIGNED,
            capabilities = emptyList(),
            streams = emptyList(),
            recommendedStreams = emptyList(),
            mandatoryStreamCombinations = emptyList(),
            availableCharacteristicKeys = emptyList(),
            availableRequestKeys = emptyList(),
            availableResultKeys = emptyList(),
            availablePhysicalRequestKeys = emptyList(),
            characteristics = emptyList(),
            missingKeys = emptyList(),
            readErrors = emptyList()
        )
    }

    private fun hardwareLevelName(level: Int): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN_$level"
    }
}

private fun <T> CameraCharacteristics.readOrNull(key: CameraCharacteristics.Key<T>): T? = try {
    get(key)
} catch (error: RuntimeException) {
    null
}

private fun CameraCharacteristics.readExposureCompensation(): ExposureCompensationSpec {
    val range = readOrNull(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val step = readOrNull(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
    if (range == null || step == null) return ExposureCompensationSpec.UNSUPPORTED
    return ExposureCompensationSpec(
        minCompensationStep = range.lower,
        maxCompensationStep = range.upper,
        stepNumerator = step.numerator,
        stepDenominator = step.denominator
    )
}

private fun CameraCharacteristics.readContinuousAfMode(): Int? {
    val modes = readOrNull(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return null
    return when {
        modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE

        modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
            CameraMetadata.CONTROL_AF_MODE_AUTO

        else -> null
    }
}

private fun Rect.toIntRect(): IntRect = IntRect(left, top, right, bottom)

private fun Size.toIntSize(): IntSize = IntSize(width, height)
