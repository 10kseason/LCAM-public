package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.app.InstrumentationRegistry.getArguments
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic inputs only. Each test deletes only its own final MediaStore output. */
@RunWith(AndroidJUnit4::class)
class PhotonAutoStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun detailRequiresParentProcessingSwitch() = runBlocking {
        val directory = folder()
        val base = jpeg(File(directory, "base.jpg"))
        val original = sha(base.readBytes())
        val result = PhotonAutoPhotoStore(context).save(
            CapturedJpegs(base, null, 0, false, "synthetic"),
            texture = false,
            detail = true
        )
        try {
            assertFalse(result.receipt.getBoolean("detailRequested"))
            assertFalse(result.receipt.getBoolean("detailApplied"))
            val bytes = context.contentResolver.openInputStream(result.uri)!!.use { it.readBytes() }
            assertEquals(original, sha(bytes))
            assertEquals(1, count(directory.name))
        } finally {
            context.contentResolver.delete(result.uri, null, null)
        }
    }

    @Test fun synthetic12MpDetailPublishesOneFinalWithPreservedOrientation() = runBlocking {
        val source = File(context.cacheDir, "detail-store-source-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(4080, 3060, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                for (x in row.indices) {
                    val hash = (x * 73856093) xor (y * 19349663)
                    val noise = (hash and Int.MAX_VALUE) % 7 - 3
                    val gray = 128 + noise
                    row[x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            source.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        val results = JSONArray()
        try {
            ExifInterface(source).apply {
                setAttribute("Orientation", "6")
                setAttribute("ExposureTime", "0.01")
                setAttribute("ExposureBiasValue", "1/3")
                setAttribute("PhotographicSensitivity", "100")
                saveAttributes()
            }
            for (enabled in listOf(false, true)) {
                val directory = folder()
                val base = source.copyTo(File(directory, "base.jpg"))
                val result = PhotonAutoPhotoStore(context).save(
                    CapturedJpegs(base, null, 0, false, "synthetic"),
                    true,
                    enabled
                )
                try {
                    assertEquals(enabled, result.receipt.getBoolean("detailRequested"))
                    assertEquals(enabled, result.receipt.getBoolean("detailApplied"))
                    if (enabled) {
                        assertTrue(result.receipt.getLong("detailChangedPixels") > 0)
                    } else {
                        assertEquals(0L, result.receipt.getLong("detailChangedPixels"))
                    }
                    assertFalse(result.receipt.getBoolean("fallback"))
                    assertEquals(1, count(directory.name))
                    assertFalse(directory.exists())
                    if (getArguments().getString("requireTextureGpu") == "true") {
                        assertEquals("gpu_gles31", result.receipt.getString("textureBackend"))
                    }
                    context.contentResolver.openFileDescriptor(result.uri, "r")!!.use {
                        val exif = ExifInterface(it.fileDescriptor)
                        assertEquals(6, exif.getAttributeInt("Orientation", 0))
                        assertEquals(100, exif.getAttributeInt("PhotographicSensitivity", 0))
                        assertEquals(
                            1.0 / 3.0,
                            exif.getAttributeDouble("ExposureBiasValue", 0.0),
                            .001
                        )
                    }
                    results.put(result.receipt)
                } finally {
                    context.contentResolver.delete(result.uri, null, null)
                }
            }
            File(context.filesDir, "photon-detail-store-benchmark.json").writeText(
                JSONObject().put("syntheticOnly", true).put("runs", results).toString(2)
            )
        } finally {
            source.delete()
        }
    }

    @Test fun synthetic12MpHdrAndTexturePublishOneFinal() = runBlocking {
        val folder = folder()
        val width = 4080
        val height = 3060
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for ((name, divisor) in listOf("base.jpg" to 1.0, "short.jpg" to 4.0)) {
                val lut = IntArray(16385) { index ->
                    val linear = (index / 4096.0 / divisor).coerceIn(0.0, 1.0)
                    val encoded = if (linear <= 0.0031308) {
                        linear * 12.92
                    } else {
                        1.055 * linear.pow(1.0 / 2.4) - 0.055
                    }
                    (encoded * 255).roundToInt().coerceIn(0, 255)
                }
                val row = IntArray(width)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val bx = (x + 10000) / 5
                        val by = (y + 10000) / 5
                        val hash = (bx * 1664525) xor (by * 1013904223) xor (bx * by * 31)
                        val value = if (x % 256 in 96..159 && y % 256 in 96..159) {
                            1.2 + x % 8 * 0.16 + y % 7 * 0.02
                        } else {
                            0.12 + (hash.toUInt() % 450u).toInt() / 1000.0
                        }
                        val channel = lut[(value * 4096).roundToInt()]
                        row[x] = (255 shl 24) or (channel shl 16) or (channel shl 8) or channel
                    }
                    bitmap.setPixels(row, 0, width, 0, y, width, 1)
                }
                val file = File(folder, name)
                file.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                }
                ExifInterface(file).apply {
                    setAttribute("Orientation", "6")
                    setAttribute("ExposureTime", (0.01 / divisor).toString())
                    setAttribute("PhotographicSensitivity", "100")
                    saveAttributes()
                }
            }
        } finally {
            bitmap.recycle()
        }
        val result = PhotonAutoPhotoStore(context).save(
            CapturedJpegs(
                File(folder, "base.jpg"),
                File(folder, "short.jpg"),
                0,
                true,
                "synthetic"
            ),
            true
        )
        try {
            assertTrue(result.receipt.toString(), result.receipt.getBoolean("hdrApplied"))
            assertFalse(result.receipt.getBoolean("fallback"))
            if (getArguments().getString("requireTextureGpu") == "true") {
                assertEquals("gpu_gles31", result.receipt.getString("textureBackend"))
            }
            assertEquals(1, count(folder.name))
            assertFalse(folder.exists())
            File(context.filesDir, "photon-auto-store-benchmark.json").writeText(
                result.receipt.put("syntheticOnly", true).toString(2)
            )
        } finally {
            context.contentResolver.delete(result.uri, null, null)
        }
    }

    @Test fun texturePublishesOneFinalAndRemovesPrivateInputs() = runBlocking {
        val folder = folder()
        val base = jpeg(File(folder, "base.jpg"))
        val result = PhotonAutoPhotoStore(context).save(
            CapturedJpegs(base, null, 123, false, "disabled"),
            true
        )
        try {
            assertEquals(1, result.receipt.getInt("outputCount"))
            assertFalse(result.receipt.getBoolean("fallback"))
            assertTrue(result.receipt.getLong("textureChangedPixels") > 0)
            assertEquals("cpu", result.receipt.getString("textureBackend"))
            assertEquals("small_image", result.receipt.getString("textureGpuFallbackReason"))
            assertFalse(folder.exists())
            assertEquals(1, count(folder.name))
            context.contentResolver.openFileDescriptor(result.uri, "r")!!.use {
                assertEquals(6, ExifInterface(it.fileDescriptor).getAttributeInt("Orientation", 0))
            }
        } finally {
            context.contentResolver.delete(result.uri, null, null)
        }
    }

    @Test fun invalidHdrExposurePublishesOnlyUnchangedBaseline() = runBlocking {
        val folder = folder()
        val base = jpeg(File(folder, "base.jpg"))
        val short = jpeg(File(folder, "short.jpg")) // Same exposure: never pretend it is HDR.
        val hash = sha(base.readBytes())
        val result = PhotonAutoPhotoStore(context).save(
            CapturedJpegs(base, short, 123, true, "bracket_captured"),
            false
        )
        try {
            assertEquals(1, count(folder.name))
            assertTrue(result.receipt.getBoolean("fallback"))
            assertFalse(result.receipt.getBoolean("hdrApplied"))
            val bytes = context.contentResolver.openInputStream(result.uri)!!.use { it.readBytes() }
            assertEquals(hash, sha(bytes))
            assertFalse(folder.exists())
        } finally {
            context.contentResolver.delete(result.uri, null, null)
        }
    }

    @Test fun incompletePrivateCaptureIsNotPublished() = runBlocking {
        val folder = folder()
        val base = File(folder, "base.jpg").apply { writeBytes(byteArrayOf(-1, -40, 1, 2)) }
        try {
            var rejected = false
            try {
                PhotonAutoPhotoStore(
                    context
                ).save(CapturedJpegs(base, null, 0, false, "test"), false)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(base.exists())
            assertEquals(0, count(folder.name))
        } finally {
            base.delete()
            folder.delete()
        }
    }

    @Test fun retryAfterPublicCommitReusesSameFinal() = runBlocking {
        val folder = folder()
        val base = jpeg(File(folder, "base.jpg"))
        val original = base.readBytes()
        val input = CapturedJpegs(base, null, 123, false, "disabled")
        val first = PhotonAutoPhotoStore(context).save(input, false)
        try {
            // Simulate process death after public commit but before private capture cleanup.
            check(folder.mkdirs())
            base.writeBytes(original)
            val second = PhotonAutoPhotoStore(context).save(input, false)
            assertEquals(first.uri, second.uri)
            assertEquals(1, count(folder.name))
            assertFalse(folder.exists())
        } finally {
            context.contentResolver.delete(first.uri, null, null)
        }
    }

    private fun folder() = File(context.cacheDir, "auto-store-test-${UUID.randomUUID()}")
        .apply { check(mkdirs()) }

    private fun jpeg(file: File): File {
        val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(512 * 384) { index ->
            // Two-pixel chroma blocks survive JPEG 4:2:0 subsampling; isolated pixels do not.
            val hash = (index % 512 / 2 * 73856093) xor (index / 512 / 2 * 19349663)
            val co = (hash and Int.MAX_VALUE) % 9 - 4
            val cg = ((hash ushr 8) and 255) % 5 - 2
            (255 shl 24) or ((128 + co - cg) shl 16) or ((128 + cg) shl 8) or (128 - co - cg)
        }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 384)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute("Orientation", "6")
            setAttribute("ExposureTime", "0.01")
            setAttribute("PhotographicSensitivity", "100")
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

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
