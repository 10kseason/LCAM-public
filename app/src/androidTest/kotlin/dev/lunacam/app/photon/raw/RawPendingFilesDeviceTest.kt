package dev.lunacam.app.photon.raw

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic metadata only. No camera, gallery or user photograph access. */
@RunWith(AndroidJUnit4::class)
class RawPendingFilesDeviceTest {
    @Test fun partialBaselineNeverAcquiresTheRecoverableName() = isolated { directory ->
        val receipt = JSONObject().put("jobId", directory.name)
        RawPendingFiles.writeReceipt(directory, receipt)
        assertTrue(
            runCatching {
                RawPendingFiles.writeBaseline(directory) {
                    it.write(ByteArray(64) { 42 })
                    throw java.io.IOException("Interrupted sensor write")
                }
            }.isFailure
        )
        assertFalse(File(directory, "baseline.dng").exists())
        assertFalse(File(directory, "baseline.pending").exists())
        assertEquals(receipt.toString(), RawPendingFiles.readReceipt(directory)?.toString())
        val pixels = ByteArray(256) { it.toByte() }
        RawPendingFiles.writeBaseline(directory) { it.write(pixels) }
        assertArrayEquals(pixels, File(directory, "baseline.dng").readBytes())
        assertTrue(
            runCatching {
                RawPendingFiles.writeBaseline(directory) { it.write(0) }
            }.isFailure
        )
        assertArrayEquals(pixels, File(directory, "baseline.dng").readBytes())
    }

    @Test fun interruptedRewriteRetainsReceiptAndBaselineBytes() = isolated { directory ->
        val baseline = File(directory, "baseline.dng").apply { writeBytes(ByteArray(64) { 42 }) }
        val original = baseline.readBytes()
        val receipt = JSONObject().put("jobId", directory.name).put("aspectRatio", "wide")
        RawPendingFiles.writeReceipt(directory, receipt)
        val interrupted = AtomicFile(File(directory, "job.json")).startWrite()
        interrupted.write("{\"jobId\":".toByteArray())
        interrupted.fd.sync()
        interrupted.close() // Model a stopped process: neither finishWrite nor failWrite ran.
        assertEquals(receipt.toString(), RawPendingFiles.readReceipt(directory)?.toString())
        assertArrayEquals(original, baseline.readBytes())
        receipt.put("aspectRatio", "cinema")
        RawPendingFiles.writeReceipt(directory, receipt)
        assertEquals("cinema", RawPendingFiles.readReceipt(directory)?.getString("aspectRatio"))
    }

    @Test fun rejectedOrWrongJobMetadataCannotReplaceRecoveryReceipt() = isolated { directory ->
        val receipt = JSONObject().put("jobId", directory.name)
        RawPendingFiles.writeReceipt(directory, receipt)
        assertTrue(
            runCatching {
                RawPendingFiles.writeReceipt(directory, JSONObject().put("jobId", "another-job"))
            }.isFailure
        )
        assertTrue(
            runCatching {
                RawPendingFiles.writeReceipt(
                    directory,
                    JSONObject(receipt.toString()).put("large", "x".repeat(262144))
                )
            }.isFailure
        )
        assertEquals(receipt.toString(), RawPendingFiles.readReceipt(directory)?.toString())
        RawPendingFiles.deleteReceipt(directory)
        assertNull(RawPendingFiles.readReceipt(directory))
    }

    @Test fun emptyOrInvalidCompletionMarkerDoesNotClaimMetadataIsReady() = isolated { directory ->
        val marker = File(directory, "jpeg.ready")
        assertFalse(RawPendingFiles.isJpegReady(directory))
        marker.writeBytes(byteArrayOf())
        assertFalse(RawPendingFiles.isJpegReady(directory))
        marker.writeBytes(byteArrayOf(0))
        assertFalse(RawPendingFiles.isJpegReady(directory))
        RawPendingFiles.markJpegReady(directory)
        assertTrue(RawPendingFiles.isJpegReady(directory))
    }

    private fun isolated(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
