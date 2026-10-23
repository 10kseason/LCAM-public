package dev.lunacam.app.photon

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads only headers/metadata on the phone, restricted to this app's own manually saved JPEGs. */
@RunWith(AndroidJUnit4::class)
class PhotonSavedJpegTest {
    @Test
    fun manuallySavedJpegsHaveValidDimensionsAndNoGps() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySavedPhotos") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val rows = JSONArray()
        var portrait = false
        var landscape = false
        resolver.query(
            collection,
            arrayOf("_id", "_size", "is_pending", "mime_type"),
            // RAW and JPEG now share the album; this JPEG-only check must not inspect DNG rows.
            "owner_package_name = ? AND relative_path = ? AND mime_type = ?",
            arrayOf(context.packageName, "Pictures/LUNA PHOTON/", "image/jpeg"),
            "date_added DESC"
        )!!.use { cursor ->
            while (cursor.moveToNext() && rows.length() < 10) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                assertTrue(cursor.getLong(1) > 0)
                assertEquals(0, cursor.getInt(2))
                assertEquals("image/jpeg", cursor.getString(3))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
                resolver.openInputStream(uri)!!.use { input ->
                    val exif = ExifInterface(input)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                    assertTrue(orientation in 1..8)
                    assertFalse(exif.getLatLong(FloatArray(2)))
                    val swap = orientation in 5..8
                    val displayWidth = if (swap) bounds.outHeight else bounds.outWidth
                    val displayHeight = if (swap) bounds.outWidth else bounds.outHeight
                    portrait = portrait || displayHeight > displayWidth
                    landscape = landscape || displayWidth > displayHeight
                    rows.put(
                        JSONObject().put("width", bounds.outWidth).put("height", bounds.outHeight)
                            .put("exifOrientation", orientation).put("bytes", cursor.getLong(1))
                            .put("pending", false).put("gpsPresent", false)
                            .put("displayWidth", displayWidth).put("displayHeight", displayHeight)
                    )
                }
            }
        }
        File(context.filesDir, "photon-jpeg-verification.json").writeText(
            JSONObject().put("images", rows).put("portraitPresent", portrait)
                .put("landscapePresent", landscape).put("pixelsDecoded", false).toString(2)
        )
        assertTrue(
            "Take at least one portrait and one landscape photo manually first",
            rows.length() >= 2 && portrait && landscape
        )
    }
}
