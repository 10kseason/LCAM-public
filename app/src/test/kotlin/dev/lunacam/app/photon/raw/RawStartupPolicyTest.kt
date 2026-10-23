package dev.lunacam.app.photon.raw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStartupPolicyTest {
    @Test fun transientCameraFailuresDoNotDisableRawForTheSession() {
        listOf(
            SecurityException("permission revoked"),
            IllegalStateException("RAW camera error 1"),
            IllegalStateException("RAW camera disconnected"),
            RuntimeException("timed out waiting for surface"),
            IllegalArgumentException("unknown vendor error")
        ).forEach { assertFalse(rawLensIsIncompatible(it)) }
    }

    @Test fun knownUnsupportedConfigurationsCanTryAnotherLens() {
        listOf("RAW session failed", "No bounded preview size", "Physical preview metadata missing")
            .forEach { assertTrue(rawLensIsIncompatible(IllegalStateException(it))) }
    }
}
