package dev.lunacam.app.photon

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhotonCapabilityReportTest {
    @Test
    fun reportPreservesCameraCapabilitiesAndErrorsWithoutDeviceFields() {
        val cameras = JSONArray().put(JSONObject().put("id", "0").put("facing", "BACK"))
        val errors = JSONArray().put(
            JSONObject().put("field", "extensions").put("reason", "Timeout")
        )
        val report = photonCapabilityReport("2026-01-01T00:00:00Z", cameras, errors)
        assertEquals(
            setOf("schemaVersion", "createdAtUtc", "cameras", "probeErrors"),
            report.keys().asSequence().toSet()
        )
        assertEquals(2, report.getInt("schemaVersion"))
        assertEquals("BACK", report.getJSONArray("cameras").getJSONObject(0).getString("facing"))
        assertEquals(
            "Timeout",
            report.getJSONArray("probeErrors").getJSONObject(0).getString("reason")
        )
        assertFalse(report.has("device"))
    }

    @Test
    fun emptyProbeRemainsAValidReport() {
        val report = photonCapabilityReport("2026-01-01T00:00:00Z", JSONArray(), JSONArray())
        assertEquals(0, report.getJSONArray("cameras").length())
        assertEquals(0, report.getJSONArray("probeErrors").length())
    }
}
