package dev.lunacam.app.photon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotonControlPolicyTest {
    @Test fun exposureUsesSupportedIntegerStepsAndClampsBeforeConversion() {
        assertEquals(3, photonExposureIndex(1f, 1f / 3, -6, 6))
        assertEquals(-3, photonExposureIndex(-1f, 1f / 3, -6, 6))
        assertEquals(6, photonExposureIndex(Float.MAX_VALUE, 1f / 3, -6, 6))
        assertEquals(-6, photonExposureIndex(-Float.MAX_VALUE, 1f / 3, -6, 6))
        assertEquals(0, photonExposureIndex(0.1f, 1f / 3, -6, 6))
        assertEquals(0, photonExposureIndex(10f, 1f, 0, 0))
    }

    @Test fun invalidExposureCannotReachCameraControl() {
        assertNull(photonExposureIndex(Float.NaN, 1f, -3, 3))
        assertNull(photonExposureIndex(Float.POSITIVE_INFINITY, 1f, -3, 3))
        assertNull(photonExposureIndex(1f, 0f, -3, 3))
        assertNull(photonExposureIndex(1f, Float.NaN, -3, 3))
        assertNull(photonExposureIndex(1f, 1f, 3, -3))
    }
}
