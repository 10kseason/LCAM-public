package dev.lunacam.domain.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRoleScorerTest {
    @Test
    fun `selects RAW logical normal-wide camera as the main back camera`() {
        val cameras =
            listOf(
                camera("camera-0", focalLengthMm = 1.8, sensorWidthMm = 5.6, hasRaw = false),
                camera(
                    "camera-1",
                    focalLengthMm = 6.3,
                    sensorWidthMm = 8.1,
                    hasRaw = true,
                    isLogical = true
                ),
                camera("camera-2", focalLengthMm = 18.6, sensorWidthMm = 5.6, hasRaw = true)
            )

        val assigned = CameraRoleScorer.assignRoles(cameras).associateBy { it.cameraAlias }

        assertEquals(CameraRole.BACK_ULTRAWIDE, assigned.getValue("camera-0").roleAssignment.role)
        assertEquals(CameraRole.BACK_WIDE, assigned.getValue("camera-1").roleAssignment.role)
        assertEquals(CameraRole.BACK_TELEPHOTO, assigned.getValue("camera-2").roleAssignment.role)
    }

    @Test
    fun `handles missing optical metadata without throwing`() {
        val assigned =
            CameraRoleScorer.assignRoles(
                listOf(
                    camera(
                        alias = "camera-0",
                        focalLengthMm = null,
                        sensorWidthMm = null,
                        hasRaw = false
                    )
                )
            )

        assertEquals(CameraRole.BACK_WIDE, assigned.single().roleAssignment.role)
        assertEquals(RoleConfidence.LOW, assigned.single().roleAssignment.confidence)
    }

    private fun camera(
        alias: String,
        focalLengthMm: Double?,
        sensorWidthMm: Double?,
        hasRaw: Boolean,
        isLogical: Boolean = false
    ): CameraCapabilityProfile = CameraCapabilityProfile(
        cameraAlias = alias,
        physicalCameraAliases = emptyList(),
        roleEvidence =
        CameraRoleEvidence(
            lensFacing = LensFacing.BACK,
            focalLengthsMm = listOfNotNull(focalLengthMm),
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorWidthMm,
            hasRawCapability = hasRaw,
            isLogicalMultiCamera = isLogical,
            hardwareLevel = "FULL"
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
        readErrors = emptyList()
    )
}
