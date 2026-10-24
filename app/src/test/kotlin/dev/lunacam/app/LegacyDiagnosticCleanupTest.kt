package dev.lunacam.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyDiagnosticCleanupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun removesLegacyReportsAndTemporaryCopiesButPreservesOtherFiles() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val legacy = File(files, "capability-explorer").apply { mkdirs() }
        val shares = File(cache, "photon-reports").apply { mkdirs() }
        val owned = listOf("cache-key.txt", "report.json", "report.md").flatMap {
            listOf(File(legacy, it), File(legacy, "$it.tmp"))
        } + File(shares, "capabilities-v1-example.json")
        owned.forEach { it.writeText("legacy private metadata") }
        val preserved = listOf(
            File(files, "photo.dng"),
            File(files, "user-export.json"),
            File(legacy, "unrelated.txt"),
            File(shares, "capabilities-v2-example.json"),
            File(shares, "unrelated.json")
        )
        preserved.forEach { it.writeText("keep") }

        assertEquals(0, LegacyDiagnosticCleanup.clear(files, cache))
        owned.forEach { assertFalse(it.exists()) }
        preserved.forEach { assertEquals("keep", it.readText()) }
        assertEquals(0, LegacyDiagnosticCleanup.clear(files, cache))
    }

    @Test
    fun doesNotRecursivelyDeleteUnexpectedDirectories() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val unexpected = File(files, "capability-explorer/report.json").apply { mkdirs() }
        val child = File(unexpected, "keep.txt").apply { writeText("keep") }
        assertEquals(1, LegacyDiagnosticCleanup.clear(files, cache))
        assertTrue(child.isFile)
    }

    @Test
    fun removesOldOrMalformedProbesButPreservesCurrentReports() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        for (name in listOf("photon-resolution-metadata.json", "photon-capabilities-live.json")) {
            val report = File(files, name)
            for (content in listOf(
                """{"schemaVersion":1,"device":{"model":"synthetic-model","sdk":1}}""",
                """{"schemaVersion":2,"device":{}}""",
                "incomplete JSON"
            )) {
                report.writeText(content)
                assertEquals(0, LegacyDiagnosticCleanup.clear(files, cache))
                assertFalse(report.exists())
            }
            val current = """{"schemaVersion":2,"cameras":[]}"""
            report.writeText(current)
            assertEquals(0, LegacyDiagnosticCleanup.clear(files, cache))
            assertEquals(current, report.readText())
        }
    }
}
