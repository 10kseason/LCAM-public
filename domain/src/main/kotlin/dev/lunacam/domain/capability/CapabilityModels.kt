package dev.lunacam.domain.capability

const val CAPABILITY_REPORT_SCHEMA_VERSION = 1

enum class LensFacing {
    BACK,
    FRONT,
    EXTERNAL,
    UNKNOWN
}

enum class CameraRole {
    BACK_WIDE,
    BACK_ULTRAWIDE,
    BACK_TELEPHOTO,
    BACK_OTHER,
    FRONT,
    EXTERNAL,
    UNKNOWN
}

enum class RoleConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class CameraRoleEvidence(
    val lensFacing: LensFacing,
    val focalLengthsMm: List<Double>,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    val hasRawCapability: Boolean,
    val isLogicalMultiCamera: Boolean,
    val hardwareLevel: String?
)

data class CameraRoleAssignment(
    val role: CameraRole,
    val score: Int,
    val confidence: RoleConfidence,
    val reasons: List<String>
) {
    companion object {
        val UNASSIGNED =
            CameraRoleAssignment(
                role = CameraRole.UNKNOWN,
                score = 0,
                confidence = RoleConfidence.LOW,
                reasons = listOf("role scoring has not run")
            )
    }
}

data class CharacteristicEntry(val name: String, val value: String?)

data class StreamSizeProfile(
    val width: Int,
    val height: Int,
    val minFrameDurationNs: Long?,
    val stallDurationNs: Long?
)

data class StreamFormatProfile(
    val formatName: String,
    val formatValue: Int,
    val sizes: List<StreamSizeProfile>
)

data class RecommendedStreamProfile(
    val useCaseName: String,
    val useCaseValue: Int,
    val outputFormats: List<StreamFormatProfile>
)

data class MandatoryStreamInformationProfile(
    val formatName: String,
    val formatValue: Int,
    val availableSizes: List<String>,
    val isInput: Boolean,
    val isMaximumSize: Boolean,
    val isUltraHighResolution: Boolean,
    val isTenBitCapable: Boolean,
    val streamUseCase: Long
)

data class MandatoryStreamCombinationProfile(
    val category: String,
    val description: String,
    val isReprocessable: Boolean,
    val streams: List<MandatoryStreamInformationProfile>
)

data class CameraCapabilityProfile(
    val cameraAlias: String,
    val physicalCameraAliases: List<String>,
    val roleEvidence: CameraRoleEvidence,
    val roleAssignment: CameraRoleAssignment = CameraRoleAssignment.UNASSIGNED,
    val capabilities: List<String>,
    val streams: List<StreamFormatProfile>,
    val recommendedStreams: List<RecommendedStreamProfile>,
    val mandatoryStreamCombinations: List<MandatoryStreamCombinationProfile>,
    val availableCharacteristicKeys: List<String>,
    val availableRequestKeys: List<String>,
    val availableResultKeys: List<String>,
    val availablePhysicalRequestKeys: List<String>,
    val characteristics: List<CharacteristicEntry>,
    val missingKeys: List<String>,
    val readErrors: List<String>
)

data class CapabilityDeviceSummary(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val buildFingerprintHash: String
)

data class CapabilityReport(
    val schemaVersion: Int,
    val createdAtUtc: String,
    val cacheKeyHash: String,
    val device: CapabilityDeviceSummary,
    val cameras: List<CameraCapabilityProfile>
)

data class CapabilityDocuments(val json: String, val markdown: String)

enum class CapabilityReportSource {
    CACHE,
    LIVE
}

data class CapabilitySnapshot(
    val report: CapabilityReport?,
    val documents: CapabilityDocuments,
    val source: CapabilityReportSource
)

interface CapabilityRepository {
    fun loadCached(): CapabilitySnapshot?

    fun refresh(): CapabilitySnapshot
}
