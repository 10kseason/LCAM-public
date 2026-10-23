package dev.lunacam.processing.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LunaCoreVersionProviderTest {
    @Test
    fun `formats a non-blank native version`() {
        assertEquals("LunaCore 0.2.0-p1", formatLunaCoreVersion("0.2.0-p1"))
    }

    @Test
    fun `rejects a blank native version`() {
        assertThrows(IllegalArgumentException::class.java) {
            formatLunaCoreVersion("   ")
        }
    }
}
