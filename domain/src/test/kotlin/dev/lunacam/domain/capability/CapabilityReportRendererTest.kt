package dev.lunacam.domain.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportRendererTest {
    @Test
    fun `renders useful camera metadata without device identifiers`() {
        val report =
            CapabilityReport(
                schemaVersion = CAPABILITY_REPORT_SCHEMA_VERSION,
                createdAtUtc = "2026-09-02T00:00:00Z",
                cameras =
                CameraRoleScorer.assignRoles(
                    listOf(
                        CameraCapabilityProfile(
                            cameraAlias = "camera-0",
                            physicalCameraAliases = emptyList(),
                            roleEvidence =
                            CameraRoleEvidence(
                                lensFacing = LensFacing.BACK,
                                focalLengthsMm = emptyList(),
                                sensorWidthMm = null,
                                sensorHeightMm = null,
                                hasRawCapability = true,
                                isLogicalMultiCamera = false,
                                hardwareLevel = null
                            ),
                            capabilities = listOf("RAW"),
                            streams = emptyList(),
                            recommendedStreams = emptyList(),
                            mandatoryStreamCombinations = emptyList(),
                            availableCharacteristicKeys = emptyList(),
                            availableRequestKeys = emptyList(),
                            availableResultKeys = emptyList(),
                            availablePhysicalRequestKeys = emptyList(),
                            characteristics =
                            listOf(
                                CharacteristicEntry(
                                    name = "android.test.quoted",
                                    value = "line one\n\"line two\""
                                )
                            ),
                            missingKeys = listOf("android.sensor.info.physicalSize"),
                            readErrors = emptyList()
                        )
                    )
                )
            )

        val documents = CapabilityReportRenderer().render(report)

        assertTrue(documents.json.contains("\\n\\\"line two\\\""))
        assertTrue(documents.json.contains("\"sensorWidthMm\": null"))
        assertTrue(documents.markdown.contains("Privacy:"))
        for (field in listOf(
            "device",
            "model",
            "manufacturer",
            "sdkInt",
            "buildFingerprintHash",
            "cacheKeyHash"
        )) {
            assertFalse(documents.json.contains("\"$field\""))
        }
        assertFalse(documents.markdown.contains("Device:"))
        assertFalse(documents.markdown.contains("Android SDK:"))
        assertFalse(documents.markdown.contains("Build fingerprint hash:"))
        assertTrue(documents.json.contains("\"RAW\""))
        assertTrue(documents.json.contains("\"schemaVersion\": 2"))
        assertFalse(documents.json.contains("Android ID", ignoreCase = true))
    }
}
