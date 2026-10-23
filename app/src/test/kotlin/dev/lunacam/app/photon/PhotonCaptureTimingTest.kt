package dev.lunacam.app.photon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonCaptureTimingTest {
    @Test fun backgroundCompletionPreservesOutputWithoutInventingReadiness() {
        val report = JSONObject().put("output", "content://media/external/images/media/17")
            .put("outputCount", 1)
        val snapshot = JSONObject(autoReadinessReceipt(report, 100, 650, null).toString())
        assertEquals("content://media/external/images/media/17", snapshot.getString("output"))
        assertEquals(1, snapshot.getInt("outputCount"))
        assertEquals(550L, snapshot.getLong("sensorReadyMs"))
        assertTrue(snapshot.isNull("shutterReadyMs"))
        assertTrue(snapshot.isNull("within1200Ms"))
        autoReadinessReceipt(report, 100, 650, 2_000)
        assertEquals(1_900L, report.getLong("shutterReadyMs"))
        assertFalse(report.getBoolean("within1200Ms"))
        assertTrue(snapshot.isNull("shutterReadyMs"))
    }

    @Test fun foregroundCompletionRetainsMeasuredTimingAndMissingSensorIsExplicit() {
        val report = autoReadinessReceipt(JSONObject(), 100, 500, 1_300)
        assertEquals(400L, report.getLong("sensorReadyMs"))
        assertEquals(1_200L, report.getLong("shutterReadyMs"))
        assertTrue(report.getBoolean("within1200Ms"))
        autoReadinessReceipt(report, 100, null, null)
        assertTrue(report.isNull("sensorReadyMs"))
        assertTrue(report.isNull("shutterReadyMs"))
        assertTrue(report.isNull("within1200Ms"))
    }
}
