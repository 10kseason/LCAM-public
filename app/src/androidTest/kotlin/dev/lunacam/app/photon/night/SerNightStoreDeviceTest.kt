package dev.lunacam.app.photon.night

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.photon.raw.RawCaptureJob
import dev.lunacam.app.photon.raw.RawPendingFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic JPEGs and isolated metadata only; no camera, gallery scan or user photo access. */
@RunWith(AndroidJUnit4::class)
class SerNightStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun completeJpegNeedsMetadataCompletionMarkerBeforeRecovery() {
        isolated { scoped ->
            val directory = scoped.filesDir
            val jpeg = File(directory, "result.jpg")
            val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
            try {
                jpeg.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it))
                }
            } finally {
                bitmap.recycle()
            }
            assertTrue(dev.lunacam.app.photon.isCompleteCapturedJpeg(jpeg))
            assertFalse(
                "Legacy or interrupted metadata must fall back to DNG",
                nightJpegReadyForRecovery(directory)
            )
            RawPendingFiles.markJpegReady(directory)
            assertTrue(nightJpegReadyForRecovery(directory))
            jpeg.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(
                "Marker cannot bless a truncated JPEG",
                nightJpegReadyForRecovery(directory)
            )
        }
    }

    @Test fun oneJobCannotPublishBothJpegAndReferenceDngOnRetry() {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "night-test-$id.jpg")
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(40, 50, 60))
        source.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it))
        }
        bitmap.recycle()
        val store = SerNightPhotoStore(context)
        var output: android.net.Uri? = null
        try {
            val first = store.publish(source, id, "image/jpeg")
            output = first.first
            // A later fallback choice must reuse the completed output before reading this file.
            val retry = store.publish(
                File(context.cacheDir, "nonexistent-$id.dng"),
                id,
                "image/x-adobe-dng"
            )
            assertEquals(first, retry)
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "is_pending"),
                "_display_name IN (?, ?) AND owner_package_name = ?",
                arrayOf("PHOTON_NIGHT_$id.jpg", "PHOTON_NIGHT_$id.dng", context.packageName),
                null
            )!!.use { rows ->
                assertEquals(1, rows.count)
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(1))
            }
            assertArrayEquals(
                source.readBytes(),
                resolver.openInputStream(first.first)!!.use {
                    it.readBytes()
                }
            )
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun queuedAndFinalSnapshotsPreserveFirstActualReadiness() {
        isolated { scoped ->
            val id = UUID.randomUUID().toString()
            val job = RawCaptureJob(id, 1000, 5000, 1800)
            val queued = JSONObject().put("jobId", id).put("shutterStartedElapsedMs", 1000)
                .put("status", "queued").put("outputCount", 0)
            SerNightJournal.write(scoped, queued, false)
            job.markShutterReady(2150)
            SerNightJournal.updateShutterReady(scoped, job)
            val saved = JSONObject(queued.toString()).put("status", "saved").put("outputCount", 1)
            SerNightJournal.write(scoped, saved, true)
            val receipt =
                JSONObject(File(scoped.filesDir, "photon-night-last-save.json").readText())
            assertEquals(1150L, receipt.getLong("shutterReadyMs"))
            assertTrue(receipt.getBoolean("within1200Ms"))
            job.markShutterReady(5000)
            SerNightJournal.updateShutterReady(scoped, job)
            assertEquals(2150L, job.shutterReadyElapsedMs)
        }
    }

    @Test fun malformedPrivateReferenceRemainsPrivateForRecovery() = runBlocking {
        isolated { scoped ->
            val id = UUID.randomUUID().toString()
            val directory = File(scoped.filesDir, "photon-night-pending/$id").apply { mkdirs() }
            val baseline = File(directory, "baseline.dng").apply {
                writeBytes(ByteArray(32) { 17 })
            }
            File(directory, "job.json").writeText(JSONObject().put("jobId", id).toString())
            val original = baseline.readBytes()
            val store = SerNightPhotoStore(scoped)
            assertEquals(1, store.pendingCount())
            val result = runCatching { runBlocking { store.recoverPending() } }
            assertTrue(result.isFailure)
            assertArrayEquals(original, baseline.readBytes())
            assertEquals(1, store.pendingCount())
        }
    }

    @Test fun cameraExitIsRecordedWithoutResettingActualReadiness() {
        isolated { scoped ->
            val id = UUID.randomUUID().toString()
            val job = RawCaptureJob(id, 1000, 5000, 1400)
            SerNightJournal.markCameraClosed(scoped, job, 1600)
            val final = JSONObject().put("jobId", id).put("shutterStartedElapsedMs", 1000)
                .put("status", "saved").put("outputCount", 1)
            SerNightJournal.write(scoped, final, true)
            job.markShutterReady(3150)
            SerNightJournal.updateShutterReady(scoped, job)
            SerNightJournal.markCameraClosed(scoped, job, 2500)
            val receipt =
                JSONObject(File(scoped.filesDir, "photon-night-last-save.json").readText())
            assertEquals(2150L, receipt.getLong("shutterReadyMs"))
            assertEquals(600L, receipt.getLong("cameraClosedBeforeReadyMs"))
            assertFalse(receipt.getBoolean("within1200Ms"))
        }
    }

    private fun isolated(block: (Context) -> Unit) {
        val directory = File(context.cacheDir, "night-isolated-${UUID.randomUUID()}").apply {
            mkdirs()
        }
        val scoped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        try {
            block(scoped)
        } finally {
            directory.deleteRecursively()
        }
    }
}
