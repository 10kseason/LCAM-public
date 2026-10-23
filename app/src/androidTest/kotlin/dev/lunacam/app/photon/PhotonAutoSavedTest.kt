package dev.lunacam.app.photon

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

/** Opt-in readback of this version's own capture receipts; never decodes photo pixels. */
@RunWith(AndroidJUnit4::class)
class PhotonAutoSavedTest {
    @Test fun completedAutoJobsHaveOneFinalAndValidMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySavedAuto") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        // Old receipt files outlive photos the user may remove in their gallery.
        val limit = InstrumentationRegistry.getArguments().getString("latestAutoJobs")
            ?.toInt()?.coerceIn(1, 20) ?: 1
        val receipts = File(context.filesDir, "photon-auto-receipts")
            .listFiles()?.filter { it.extension == "json" }
            ?.sortedByDescending { it.name }?.take(limit).orEmpty()
        assertTrue("A user-triggered AUTO capture is required", receipts.isNotEmpty())
        val observations = JSONArray()
        for (file in receipts) {
            val receipt = JSONObject(file.readText())
            val uri = Uri.parse(receipt.getString("output"))
            require(uri.scheme == "content" && uri.authority == "media")
            assertEquals(1, receipt.getInt("outputCount"))
            resolver.query(
                uri,
                arrayOf("_size", "is_pending", "owner_package_name", "_display_name"),
                null,
                null,
                null
            )!!.use { row ->
                assertTrue(row.moveToFirst())
                assertTrue(row.getLong(0) > 0)
                assertEquals(0, row.getInt(1))
                assertEquals(context.packageName, row.getString(2))
                assertEquals("PHOTON_${receipt.getString("jobId")}.jpg", row.getString(3))
                receipt.put("bytes", row.getLong(0)).put("pending", row.getInt(1))
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
            if (receipt.has("aspectRatio")) {
                assertEquals(receipt.getInt("outputWidth"), bounds.outWidth)
                assertEquals(receipt.getInt("outputHeight"), bounds.outHeight)
                val selected = PhotonAspectRatio.fromPreference(receipt.getString("aspectRatio"))
                if (selected != PhotonAspectRatio.ORIGINAL) {
                    val normalizedWidth = maxOf(bounds.outWidth, bounds.outHeight)
                    val normalizedHeight = minOf(bounds.outWidth, bounds.outHeight)
                    val expected = if (selected == PhotonAspectRatio.WIDE) 16.0 / 9 else 7.0 / 3
                    assertTrue(
                        kotlin.math.abs(normalizedWidth.toDouble() / normalizedHeight - expected) <
                            0.0001
                    )
                }
            }
            resolver.openFileDescriptor(uri, "r")!!.use {
                val exif = ExifInterface(it.fileDescriptor)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                assertTrue(orientation in 1..8)
                receipt.put("orientation", orientation)
            }
            receipt.put("width", bounds.outWidth).put("height", bounds.outHeight)
            observations.put(receipt)
        }
        File(context.filesDir, "photon-auto-verification.json").writeText(
            JSONObject().put("jobs", observations).put("pixelsDecoded", false)
                .put("photosCaptured", 0).toString(2)
        )
    }
}
