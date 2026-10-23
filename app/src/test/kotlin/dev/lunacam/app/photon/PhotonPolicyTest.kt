package dev.lunacam.app.photon

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonPolicyTest {
    @Test fun pinchZoomIsMultiplicativeClampedAndRejectsInvalidFactors() {
        assertEquals(3f, pinchZoomRatio(2f, 1.5f, 0.5f, 10f))
        assertEquals(1f, pinchZoomRatio(2f, 0.5f, 0.5f, 10f))
        assertEquals(10f, pinchZoomRatio(9f, 2f, 0.5f, 10f))
        assertEquals(0.5f, pinchZoomRatio(1f, 0.1f, 0.5f, 10f))
        assertEquals(2f, pinchZoomRatio(2f, Float.NaN, 0.5f, 10f))
        assertEquals(2f, pinchZoomRatio(2f, -1f, 0.5f, 10f))
    }

    @Test fun ignoresPortraitAndLandscapeLetterboxAndInvalidGeometry() {
        assertFalse(isPreviewTap(100, 200, 400, 300, 90, 50f, 10f))
        assertTrue(isPreviewTap(100, 200, 400, 300, 90, 50f, 100f))
        assertFalse(isPreviewTap(200, 100, 400, 300, 0, 5f, 50f))
        assertTrue(isPreviewTap(200, 100, 400, 300, 0, 100f, 50f))
        assertFalse(isPreviewTap(0, 100, 400, 300, 0, 0f, 50f))
        assertFalse(isPreviewTap(100, 100, 0, 300, 0, 50f, 50f))
        assertFalse(isPreviewTap(100, 100, 400, 400, 0, 100f, 50f))
    }

    @Test fun unknownIsNotUnsupported() {
        assertEquals("unknown", extensionSupport(null))
        assertEquals("unsupported", extensionSupport(false))
        assertEquals("supported", extensionSupport(true))
    }

    @Test fun fillCenterFocusAcceptsTheWholeVisibleCropAndRejectsOutsideOrInvalidTouches() {
        for ((x, y) in listOf(0f to 0f, 99f to 199f, 50f to 10f)) {
            assertTrue(isPreviewTap(100, 200, 400, 300, 90, x, y, fillCenter = true))
        }
        for ((x, y) in listOf(-1f to 20f, 100f to 20f, 20f to 200f, Float.NaN to 30f)) {
            assertFalse(isPreviewTap(100, 200, 400, 300, 90, x, y, fillCenter = true))
        }
    }

    @Test fun orientationBoundariesAndUnknown() {
        assertNull(captureRotation(-1))
        for ((degrees, rotation) in mapOf(
            0 to 0, 44 to 0, 45 to 3, 134 to 3, 135 to 2, 224 to 2,
            225 to 1, 314 to 1, 315 to 0, 359 to 0
        )) {
            assertEquals("degrees=$degrees", rotation, captureRotation(degrees))
        }
    }

    @Test fun failurePreservesUnknownAndDoesNotLeakExceptionMessage() {
        val errors = JSONArray()
        val value: Boolean? =
            probe(errors, "0", "extensions.HDR") { throw SecurityException("private-path-secret") }
        assertNull(value)
        assertEquals("unknown", extensionSupport(value))
        assertEquals("SecurityException", errors.getJSONObject(0).getString("reason"))
        assertFalse(errors.toString().contains("private-path-secret"))
    }

    @Test fun unsupportedAndMissingKeyHaveDifferentEvidence() {
        val errors = JSONArray()
        assertEquals(false, probe(errors, "1", "extensions.HDR") { false })
        assertEquals(0, errors.length())
        assertNull(probe<String>(errors, "1", "manual.iso") { null })
        assertEquals("NotReported", errors.getJSONObject(0).getString("reason"))
    }
}
