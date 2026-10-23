package dev.lunacam.camera.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.MandatoryStreamCombination
import android.hardware.camera2.params.RecommendedStreamConfigurationMap
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import dev.lunacam.domain.capability.CameraCapabilityProfile
import dev.lunacam.domain.capability.CameraRoleEvidence
import dev.lunacam.domain.capability.CharacteristicEntry
import dev.lunacam.domain.capability.LensFacing
import dev.lunacam.domain.capability.MandatoryStreamCombinationProfile
import dev.lunacam.domain.capability.MandatoryStreamInformationProfile
import dev.lunacam.domain.capability.RecommendedStreamProfile
import dev.lunacam.domain.capability.StreamFormatProfile
import dev.lunacam.domain.capability.StreamSizeProfile

internal class CameraCharacteristicsCollector {
    fun collect(cameraManager: CameraManager): List<CameraCapabilityProfile> {
        val cameraIds = cameraManager.cameraIdList.sorted()
        val characteristicsById =
            cameraIds.associateWith { cameraId ->
                try {
                    CharacteristicRead.Success(cameraManager.getCameraCharacteristics(cameraId))
                } catch (error: RuntimeException) {
                    CharacteristicRead.Failure(error.javaClass.simpleName)
                } catch (error: android.hardware.camera2.CameraAccessException) {
                    CharacteristicRead.Failure(error.javaClass.simpleName)
                }
            }
        val primaryAliases =
            cameraIds.mapIndexed { index, cameraId -> cameraId to "camera-$index" }.toMap()
        val extraPhysicalIds =
            characteristicsById.values
                .filterIsInstance<CharacteristicRead.Success>()
                .flatMap { read -> safePhysicalCameraIds(read.characteristics) }
                .filterNot(primaryAliases::containsKey)
                .distinct()
                .sorted()
        val physicalAliases =
            extraPhysicalIds.mapIndexed { index, cameraId ->
                cameraId to "physical-$index"
            }.toMap()
        val aliases = primaryAliases + physicalAliases

        return cameraIds.map { cameraId ->
            when (val read = characteristicsById.getValue(cameraId)) {
                is CharacteristicRead.Success ->
                    collectCamera(
                        cameraAlias = primaryAliases.getValue(cameraId),
                        characteristics = read.characteristics,
                        aliases = aliases
                    )
                is CharacteristicRead.Failure ->
                    failedCamera(primaryAliases.getValue(cameraId), read.errorType)
            }
        }
    }

    private fun collectCamera(
        cameraAlias: String,
        characteristics: CameraCharacteristics,
        aliases: Map<String, String>
    ): CameraCapabilityProfile {
        val missingKeys = linkedSetOf<String>()
        val readErrors = linkedSetOf<String>()
        val reader = ExpectedMetadataReader(characteristics, missingKeys, readErrors)

        val lensFacingValue = reader.read(CameraCharacteristics.LENS_FACING)
        val hardwareLevelValue = reader.read(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val capabilities =
            reader.read(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val focalLengths =
            reader.read(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        reader.observe(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        reader.observe(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        reader.observe(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        val sensorPhysicalSize = reader.read(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        reader.observe(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        reader.observe(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        reader.observe(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        reader.observe(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED)
        reader.observe(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        reader.observe(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        reader.observe(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        reader.observe(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        reader.observe(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        reader.observe(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        reader.observe(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        reader.observe(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val streamMap = reader.read(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val physicalCameraAliases =
            try {
                characteristics.physicalCameraIds.mapNotNull(aliases::get).sorted()
            } catch (error: RuntimeException) {
                readErrors += "physicalCameraIds: ${error.javaClass.simpleName}"
                emptyList()
            }
        val capabilityNames = capabilities.map(::requestCapabilityName).sorted()
        val streams = streamMap?.let { collectStreamFormats(it, readErrors) }.orEmpty()

        return CameraCapabilityProfile(
            cameraAlias = cameraAlias,
            physicalCameraAliases = physicalCameraAliases,
            roleEvidence =
            CameraRoleEvidence(
                lensFacing = lensFacing(lensFacingValue),
                focalLengthsMm = focalLengths.map(Float::toDouble),
                sensorWidthMm = sensorPhysicalSize?.width?.toDouble(),
                sensorHeightMm = sensorPhysicalSize?.height?.toDouble(),
                hasRawCapability = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                ),
                isLogicalMultiCamera =
                capabilities.contains(
                    CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ),
                hardwareLevel = hardwareLevelName(hardwareLevelValue)
            ),
            capabilities = capabilityNames,
            streams = streams,
            recommendedStreams = collectRecommendedStreams(characteristics, readErrors),
            mandatoryStreamCombinations = collectMandatoryCombinations(reader, readErrors),
            availableCharacteristicKeys = characteristics.keys.map { it.name }.sorted(),
            availableRequestKeys = readKeyNames("availableRequestKeys", readErrors) {
                characteristics.availableCaptureRequestKeys.map { it.name }
            },
            availableResultKeys = readKeyNames("availableResultKeys", readErrors) {
                characteristics.availableCaptureResultKeys.map { it.name }
            },
            availablePhysicalRequestKeys =
            readKeyNames("availablePhysicalRequestKeys", readErrors) {
                characteristics.availablePhysicalCameraRequestKeys.map { it.name }
            },
            characteristics = collectAllCharacteristics(characteristics, readErrors),
            missingKeys = missingKeys.sorted(),
            readErrors = readErrors.sorted()
        )
    }

    private fun failedCamera(cameraAlias: String, errorType: String): CameraCapabilityProfile =
        CameraCapabilityProfile(
            cameraAlias = cameraAlias,
            physicalCameraAliases = emptyList(),
            roleEvidence =
            CameraRoleEvidence(
                lensFacing = LensFacing.UNKNOWN,
                focalLengthsMm = emptyList(),
                sensorWidthMm = null,
                sensorHeightMm = null,
                hasRawCapability = false,
                isLogicalMultiCamera = false,
                hardwareLevel = null
            ),
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
            readErrors = listOf("camera characteristics unavailable: $errorType")
        )

    private fun safePhysicalCameraIds(characteristics: CameraCharacteristics): Set<String> = try {
        characteristics.physicalCameraIds
    } catch (_: RuntimeException) {
        emptySet()
    }

    private fun collectAllCharacteristics(
        characteristics: CameraCharacteristics,
        readErrors: MutableSet<String>
    ): List<CharacteristicEntry> = characteristics.keys
        .sortedBy { it.name }
        .map { key ->
            try {
                CharacteristicEntry(
                    key.name,
                    MetadataValueFormatter.format(readUntyped(characteristics, key))
                )
            } catch (error: RuntimeException) {
                readErrors += "${key.name}: ${error.javaClass.simpleName}"
                CharacteristicEntry(key.name, null)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun readUntyped(
        characteristics: CameraCharacteristics,
        key: CameraCharacteristics.Key<*>
    ): Any? = characteristics.get(key as CameraCharacteristics.Key<Any?>)

    private fun collectStreamFormats(
        map: StreamConfigurationMap,
        readErrors: MutableSet<String>
    ): List<StreamFormatProfile> = map.outputFormats
        .sorted()
        .mapNotNull { format -> collectStreamFormat(map, format, readErrors, "stream") }

    private fun collectStreamFormat(
        map: StreamConfigurationMap,
        format: Int,
        readErrors: MutableSet<String>,
        errorPrefix: String
    ): StreamFormatProfile? = try {
        val sizes =
            map.getOutputSizes(format)?.sortedWith(
                compareByDescending<Size> { it.width * it.height }
            )
        if (sizes == null) {
            null
        } else {
            StreamFormatProfile(
                formatName = imageFormatName(format),
                formatValue = format,
                sizes =
                sizes.map { size ->
                    StreamSizeProfile(
                        width = size.width,
                        height = size.height,
                        minFrameDurationNs =
                        durationOrNull(errorPrefix, "min", readErrors) {
                            map.getOutputMinFrameDuration(format, size)
                        },
                        stallDurationNs = durationOrNull(errorPrefix, "stall", readErrors) {
                            map.getOutputStallDuration(format, size)
                        }
                    )
                }
            )
        }
    } catch (error: RuntimeException) {
        readErrors += "$errorPrefix.${imageFormatName(format)}: ${error.javaClass.simpleName}"
        null
    }

    private fun collectRecommendedStreams(
        characteristics: CameraCharacteristics,
        readErrors: MutableSet<String>
    ): List<RecommendedStreamProfile> = RECOMMENDED_USE_CASES.mapNotNull { (name, value) ->
        try {
            characteristics.getRecommendedStreamConfigurationMap(value)?.let { map ->
                RecommendedStreamProfile(
                    useCaseName = name,
                    useCaseValue = value,
                    outputFormats =
                    map.outputFormats
                        .sorted()
                        .mapNotNull { format ->
                            collectRecommendedFormat(map, format, readErrors, name)
                        }
                )
            }
        } catch (error: RuntimeException) {
            readErrors += "recommended.$name: ${error.javaClass.simpleName}"
            null
        }
    }

    private fun collectRecommendedFormat(
        map: RecommendedStreamConfigurationMap,
        format: Int,
        readErrors: MutableSet<String>,
        useCaseName: String
    ): StreamFormatProfile? = try {
        val sizes =
            map.getOutputSizes(format).orEmpty().sortedWith(
                compareByDescending<Size> { it.width * it.height }
            )
        StreamFormatProfile(
            formatName = imageFormatName(format),
            formatValue = format,
            sizes =
            sizes.map { size ->
                StreamSizeProfile(
                    width = size.width,
                    height = size.height,
                    minFrameDurationNs =
                    durationOrNull(
                        "recommended.$useCaseName",
                        "min",
                        readErrors
                    ) {
                        map.getOutputMinFrameDuration(format, size)
                    },
                    stallDurationNs = durationOrNull(
                        "recommended.$useCaseName",
                        "stall",
                        readErrors
                    ) {
                        map.getOutputStallDuration(format, size)
                    }
                )
            }
        )
    } catch (error: RuntimeException) {
        readErrors +=
            "recommended.$useCaseName.${imageFormatName(format)}: " +
            error.javaClass.simpleName
        null
    }

    private fun collectMandatoryCombinations(
        reader: ExpectedMetadataReader,
        readErrors: MutableSet<String>
    ): List<MandatoryStreamCombinationProfile> =
        MANDATORY_COMBINATION_KEYS.flatMap { (category, key) ->
            reader.read(key).orEmpty().mapIndexedNotNull { index, combination ->
                try {
                    combination.toProfile(category)
                } catch (error: RuntimeException) {
                    readErrors += "mandatory.$category[$index]: ${error.javaClass.simpleName}"
                    null
                }
            }
        }

    private fun MandatoryStreamCombination.toProfile(
        category: String
    ): MandatoryStreamCombinationProfile = MandatoryStreamCombinationProfile(
        category = category,
        description = description.toString(),
        isReprocessable = isReprocessable,
        streams =
        streamsInformation.map { stream ->
            MandatoryStreamInformationProfile(
                formatName = imageFormatName(stream.format),
                formatValue = stream.format,
                availableSizes = stream.availableSizes.map { "${it.width}x${it.height}" },
                isInput = stream.isInput,
                isMaximumSize = stream.isMaximumSize,
                isUltraHighResolution = stream.isUltraHighResolution,
                isTenBitCapable = stream.is10BitCapable,
                streamUseCase = stream.streamUseCase
            )
        }
    )

    private fun durationOrNull(
        prefix: String,
        kind: String,
        readErrors: MutableSet<String>,
        read: () -> Long
    ): Long? = try {
        read()
    } catch (error: RuntimeException) {
        readErrors += "$prefix.$kind-duration: ${error.javaClass.simpleName}"
        null
    }

    private fun readKeyNames(
        label: String,
        readErrors: MutableSet<String>,
        read: () -> List<String>
    ): List<String> = try {
        read().sorted()
    } catch (error: RuntimeException) {
        readErrors += "$label: ${error.javaClass.simpleName}"
        emptyList()
    }

    private fun lensFacing(value: Int?): LensFacing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> LensFacing.UNKNOWN
    }

    private fun hardwareLevelName(value: Int?): String? = when (value) {
        null -> null
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN_$value"
    }

    private fun requestCapabilityName(value: Int): String = when (value) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE ->
            "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING ->
            "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING ->
            "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS ->
            "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING ->
            "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO ->
            "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING ->
            "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA ->
            "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA ->
            "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING ->
            "OFFLINE_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR ->
            "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING ->
            "REMOSAIC_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT ->
            "DYNAMIC_RANGE_TEN_BIT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE ->
            "STREAM_USE_CASE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES ->
            "COLOR_SPACE_PROFILES"
        else -> "UNKNOWN_$value"
    }

    private fun imageFormatName(format: Int): String = when (format) {
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.JPEG_R -> "JPEG_R"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
        else -> "FORMAT_$format"
    }

    private class ExpectedMetadataReader(
        private val characteristics: CameraCharacteristics,
        private val missingKeys: MutableSet<String>,
        private val readErrors: MutableSet<String>
    ) {
        fun <T> read(key: CameraCharacteristics.Key<T>): T? = try {
            characteristics.get(key).also { value ->
                if (value == null) missingKeys += key.name
            }
        } catch (error: RuntimeException) {
            readErrors += "${key.name}: ${error.javaClass.simpleName}"
            null
        }

        fun <T> observe(key: CameraCharacteristics.Key<T>) {
            read(key)
        }
    }

    private sealed interface CharacteristicRead {
        data class Success(val characteristics: CameraCharacteristics) : CharacteristicRead

        data class Failure(val errorType: String) : CharacteristicRead
    }

    private companion object {
        val RECOMMENDED_USE_CASES =
            listOf(
                "PREVIEW" to RecommendedStreamConfigurationMap.USECASE_PREVIEW,
                "RECORD" to RecommendedStreamConfigurationMap.USECASE_RECORD,
                "VIDEO_SNAPSHOT" to RecommendedStreamConfigurationMap.USECASE_VIDEO_SNAPSHOT,
                "SNAPSHOT" to RecommendedStreamConfigurationMap.USECASE_SNAPSHOT,
                "ZSL" to RecommendedStreamConfigurationMap.USECASE_ZSL,
                "RAW" to RecommendedStreamConfigurationMap.USECASE_RAW,
                "LOW_LATENCY_SNAPSHOT" to
                    RecommendedStreamConfigurationMap.USECASE_LOW_LATENCY_SNAPSHOT,
                "TEN_BIT_OUTPUT" to RecommendedStreamConfigurationMap.USECASE_10BIT_OUTPUT
            )

        val MANDATORY_COMBINATION_KEYS =
            listOf(
                "DEFAULT" to CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS,
                "CONCURRENT" to
                    CameraCharacteristics.SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS,
                "MAXIMUM_RESOLUTION" to
                    CameraCharacteristics.SCALER_MANDATORY_MAXIMUM_RESOLUTION_STREAM_COMBINATIONS,
                "TEN_BIT" to
                    CameraCharacteristics.SCALER_MANDATORY_TEN_BIT_OUTPUT_STREAM_COMBINATIONS,
                "STREAM_USE_CASE" to
                    CameraCharacteristics.SCALER_MANDATORY_USE_CASE_STREAM_COMBINATIONS,
                "PREVIEW_STABILIZATION" to
                    CameraCharacteristics
                        .SCALER_MANDATORY_PREVIEW_STABILIZATION_OUTPUT_STREAM_COMBINATIONS
            )
    }
}
