package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated pixels only; no camera is opened and every public test output is removed. */
@RunWith(AndroidJUnit4::class)
class PhotonAspectDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allRatiosOrientationsAndProcessingModesPreserveFraming() = runBlocking {
        for (orientation in listOf(1, 3, 6, 8)) {
            for (aspect in PhotonAspectRatio.entries) {
                for (processing in listOf(false, true)) {
                    verifyOutput(orientation, aspect, processing, invalidHdr = false)
                }
            }
        }
    }

    @Test fun processingFallbackStillHonorsEveryRatioAndOrientation() = runBlocking {
        for (orientation in listOf(1, 3, 6, 8)) {
            for (aspect in PhotonAspectRatio.entries) {
                verifyOutput(orientation, aspect, processing = true, invalidHdr = true)
            }
        }
    }

    @Test fun impossibleCropRemainsPrivateAndPublishesNothing() = runBlocking {
        val directory = folder()
        val base = jpeg(File(directory, "base.jpg"), 1, 8, 8)
        val original = base.readBytes()
        try {
            val attempt = runCatching {
                PhotonAutoPhotoStore(context).save(
                    CapturedJpegs(base, null, 0, false, "synthetic"),
                    false,
                    aspectRatio = PhotonAspectRatio.WIDE
                )
            }
            assertTrue(attempt.exceptionOrNull() is IllegalArgumentException)
            assertArrayEquals(original, base.readBytes())
            assertEquals(0, count(directory.name))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun verifyOutput(
        orientation: Int,
        aspect: PhotonAspectRatio,
        processing: Boolean,
        invalidHdr: Boolean
    ) {
        val directory = folder()
        val base = jpeg(File(directory, "base.jpg"), orientation)
        val sourceBytes = base.readBytes()
        val source = requireNotNull(BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size))
        // Equal exposure is invalid for merge, forcing the existing processing fallback.
        val short = if (invalidHdr) base.copyTo(File(directory, "short.jpg")) else null
        var result: AutoSaveResult? = null
        try {
            val saved = PhotonAutoPhotoStore(context).save(
                CapturedJpegs(base, short, 0, invalidHdr, "synthetic"),
                processing,
                detail = processing,
                aspectRatio = aspect
            )
            result = saved
            val bytes = context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() }
            val crop = aspect.centerCrop(source.width, source.height)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(crop.width, decoded.width)
                assertEquals(crop.height, decoded.height)
                // Smooth coordinate ramps reveal an offset crop or unintended rotation, while
                // tolerating the one intended JPEG encode and bounded processing adjustments.
                for (fy in 1..4) {
                    for (fx in 1..4) {
                        val x = crop.width * fx / 5
                        val y = crop.height * fy / 5
                        val expected = source.getPixel(crop.left + x, crop.top + y)
                        val actual = decoded.getPixel(x, y)
                        assertTrue(abs(Color.red(expected) - Color.red(actual)) <= 7)
                        assertTrue(abs(Color.green(expected) - Color.green(actual)) <= 7)
                        assertTrue(abs(Color.blue(expected) - Color.blue(actual)) <= 7)
                    }
                }
            } finally {
                decoded.recycle()
            }
            val exif = bytes.inputStream().use { ExifInterface(it) }
            assertEquals(orientation, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals(crop.width, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
            assertEquals(crop.height, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
            assertEquals(
                0.01,
                exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0),
                0.000001
            )
            assertEquals(aspect.id, saved.receipt.getString("aspectRatio"))
            assertEquals(
                aspect != PhotonAspectRatio.ORIGINAL,
                saved.receipt.getBoolean("cropApplied")
            )
            assertEquals(crop.width, saved.receipt.getInt("outputWidth"))
            assertEquals(crop.height, saved.receipt.getInt("outputHeight"))
            val recordedCrop = saved.receipt.getJSONObject("cropBounds")
            assertEquals(crop.left, recordedCrop.getInt("left"))
            assertEquals(crop.top, recordedCrop.getInt("top"))
            assertEquals(invalidHdr, saved.receipt.getBoolean("fallback"))
            assertEquals(1, saved.receipt.getInt("outputCount"))
            assertEquals(1, count(directory.name))
            assertFalse(directory.exists())
            if ((!processing || invalidHdr) && aspect == PhotonAspectRatio.ORIGINAL) {
                assertArrayEquals(sourceBytes, bytes)
                assertEquals(0, saved.receipt.getInt("jpegEncodeCount"))
                assertFalse(saved.receipt.getJSONObject("processingStagesMs").has("jpegEncode"))
            } else if (aspect != PhotonAspectRatio.ORIGINAL) {
                assertEquals(1, saved.receipt.getInt("jpegEncodeCount"))
                assertTrue(saved.receipt.getJSONObject("processingStagesMs").has("jpegEncode"))
            }
        } finally {
            source.recycle()
            result?.let { context.contentResolver.delete(it.uri, null, null) }
            directory.deleteRecursively()
        }
    }

    private fun folder(): File = File(context.cacheDir, "aspect-test-${UUID.randomUUID()}").apply {
        check(mkdirs())
    }

    private fun jpeg(file: File, orientation: Int, width: Int = 512, height: Int = 384): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in row.indices) {
                    row[x] = Color.rgb(32 + x * 180 / width, 32 + y * 180 / height, 90)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.01")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "100")
            setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            saveAttributes()
        }
        return file
    }

    private fun count(id: String): Int = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
        arrayOf("PHOTON_$id.jpg"),
        null
    )!!.use { it.count }
}
