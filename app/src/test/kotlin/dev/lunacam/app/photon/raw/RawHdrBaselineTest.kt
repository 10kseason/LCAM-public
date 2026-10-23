package dev.lunacam.app.photon.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawHdrBaselineTest {
    @Test fun outOfOrderArrivalStillFindsOnlyTheMeteredReference() {
        assertEquals(
            100L,
            rawHdrBaselineTimestamp(linkedMapOf(300L to 2, 100L to 0, 200L to 1), setOf(200L, 100L))
        )
        assertEquals(100L, rawHdrBaselineTimestamp(mapOf(100L to 0), setOf(100L)))
    }

    @Test fun missingOrMismatchedHalfNeverBecomesFallback() {
        assertNull(rawHdrBaselineTimestamp(emptyMap(), setOf(100L)))
        assertNull(rawHdrBaselineTimestamp(mapOf(100L to 0), emptySet()))
        assertNull(rawHdrBaselineTimestamp(mapOf(100L to 0), setOf(200L)))
        assertNull(rawHdrBaselineTimestamp(mapOf(100L to 1, 200L to 2), setOf(100L, 200L)))
    }

    @Test fun ambiguousOrInvalidIdentityCannotPublishTheWrongFrame() {
        assertNull(rawHdrBaselineTimestamp(mapOf(100L to 0, 200L to 0), setOf(100L, 200L)))
        assertNull(rawHdrBaselineTimestamp(mapOf(0L to 0), setOf(0L)))
    }
}
