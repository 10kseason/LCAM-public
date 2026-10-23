package dev.lunacam.app.photon.night

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: exact own receipts and JPEG headers only. Never reads photo pixels. */
@RunWith(AndroidJUnit4::class)
class SerNightSavedTest {
    @Test fun latestUserNightCapturesKeepFullResolutionAndRealReadiness() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifySerNightPhotos") == "true")
        val count = (arguments.getString("nightJobs")?.toIntOrNull() ?: 2).coerceIn(1, 10)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jobs = File(context.filesDir, "photon-night-receipts").listFiles()
            ?.filter { it.extension == "json" }
            ?.map { JSONObject(it.readText()) }
            ?.filter { it.optString("status") == "saved" && !it.optBoolean("recoveredReference") }
            ?.sortedByDescending { it.getLong("shutterStartedWallTimeMs") }
            ?.take(count).orEmpty()
        assertEquals("User-triggered new Night captures required", count, jobs.size)
        val observations = JSONArray()
        for (job in jobs) {
            assertTrue(job.getString("appVersion").startsWith("0.11.0-ser-night"))
            assertEquals(
                "New color/readiness revision capture required",
                4,
                job.getInt("pipelineRevision")
            )
            assertTrue(
                "SER ON captures required for the Night pair",
                job.getBoolean("serRequested")
            )
            assertEquals(1, job.getInt("outputCount"))
            assertEquals("image/jpeg", job.getString("mimeType"))
            assertEquals("gpu_gles31", job.getString("backend"))
            assertTrue(
                "Camera was closed before readiness; repeat pair before opening the gallery",
                !job.has("cameraClosedBeforeReadyElapsedMs")
            )
            val uri = Uri.parse(job.getString("output"))
            require(uri.scheme == "content" && uri.authority == "media")
            context.contentResolver.query(
                uri,
                arrayOf("_size", "is_pending", "owner_package_name"),
                null,
                null,
                null
            )!!.use { row ->
                assertTrue(row.moveToFirst())
                assertTrue(row.getLong(0) > 0)
                assertEquals(0, row.getInt(1))
                assertEquals(context.packageName, row.getString(2))
                job.put("bytes", row.getLong(0))
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            assertEquals(4080, options.outWidth)
            assertEquals(3060, options.outHeight)
            context.contentResolver.openFileDescriptor(uri, "r")!!.use {
                val exif = ExifInterface(it.fileDescriptor)
                val orientation = exif.getAttributeInt("Orientation", 0)
                assertTrue(orientation in 1..8)
                assertEquals(1, exif.getAttributeInt("ColorSpace", 0))
                job.put("orientation", orientation)
            }
            val readiness = job.getLong("shutterReadyMs")
            assertTrue("Actual next-shutter latency $readiness exceeds 1200ms", readiness <= 1200)
            observations.put(job)
        }
        File(context.filesDir, "photon-night-verification.json").writeText(
            JSONObject().put("jobs", observations).put("pixelsDecoded", false)
                .put("photosCaptured", 0).toString(2)
        )
    }
}
