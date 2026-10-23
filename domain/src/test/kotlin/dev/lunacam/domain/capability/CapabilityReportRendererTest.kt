package dev.lunacam.domain.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportRendererTest {
    @Test
    fun `renders nullable metadata without leaking the raw build fingerprint`() {
        val rawFingerprint = "samsung/device/private-build-fingerprint"
        val report =
            CapabilityReport(
                schemaVersion = CAPABILITY_REPORT_SCHEMA_VERSION,
                createdAtUtc = "2026-09-02T00:00:00Z",
                cacheKeyHash = CapabilityCacheKey.create(1, 36, rawFingerprint),
                device =
                CapabilityDeviceSummary(
                    manufacturer = "samsung",
                    model = "SM-S938N",
                    sdkInt = 36,
                    buildFingerprintHash = CapabilityCacheKey.fingerprintHash(rawFingerprint)
                ),
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
        assertFalse(documents.json.contains(rawFingerprint))
        assertFalse(documents.markdown.contains(rawFingerprint))
        assertFalse(documents.json.contains("Android ID", ignoreCase = true))
    }

    @Test
    fun `cache key changes when schema sdk or fingerprint changes`() {
        val baseline = CapabilityCacheKey.create(1, 36, "fingerprint-a")

        assertTrue(baseline != CapabilityCacheKey.create(2, 36, "fingerprint-a"))
        assertTrue(baseline != CapabilityCacheKey.create(1, 35, "fingerprint-a"))
        assertTrue(baseline != CapabilityCacheKey.create(1, 36, "fingerprint-b"))
    }
}
