package dev.lunacam.app.photon.rawauto

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.processing.nativebridge.NativeSerRender
import dev.lunacam.processing.nativebridge.SerNightColor
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.floor
import kotlin.math.pow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated buffers/files only. No shutter, photograph read or live preview pixel access. */
@RunWith(AndroidJUnit4::class)
class RawAutoDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun naturalJniMatchesIndependentToneReference() {
        val color = SerNightColor(
            0,
            FloatArray(4) { 64f },
            4095f,
            FloatArray(4) { 1f },
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        var previous = -1
        for (level in listOf(0, 8, 32, 100, 400, 1000, 2000, 3500)) {
            val packed = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder())
            repeat(256) { packed.putInt(it * 4, 64 + level) }
            val bitmap = NativeSerRender.renderNatural(16, 16, packed, color, 1.5f, .35f, .01f)
            try {
                val value = Color.red(bitmap.getPixel(8, 8))
                val t = level / 4031.0 * 1.5
                val lifted = t + .35 * .18 * (t / (t + .01)) * (t / (t + .10))
                val mapped = if (lifted <=
                    .65
                ) {
                    lifted
                } else {
                    .65 + .35 * (lifted - .65) / (lifted - .30)
                }
                val encoded = if (mapped <=
                    .0031308
                ) {
                    12.92 * mapped
                } else {
                    1.055 * mapped.pow(1.0 / 2.4) - .055
                }
                assertEquals(floor(encoded * 255 + .5).toDouble(), value.toDouble(), 1.0)
                assertTrue(value >= previous)
                assertEquals(value, Color.green(bitmap.getPixel(8, 8)))
                assertEquals(value, Color.blue(bitmap.getPixel(8, 8)))
                previous = value
            } finally {
                bitmap.recycle()
            }
        }
        File(context.filesDir, "photon-raw-auto-synthetic.json").writeText(
            JSONObject().put("syntheticOnly", true).put("levels", 8).put("maxCodeError", 1)
                .put("photosCaptured", 0).toString(2)
        )
    }

    @Test fun fullResolutionNaturalRenderIsBounded() {
        val width = 4080
        val height = 3060
        val packed = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        repeat(width * height) { packed.putInt(it * 4, 128) }
        val color = SerNightColor(
            0,
            FloatArray(4) { 64f },
            1023f,
            FloatArray(4) { 1f },
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        val started = android.os.SystemClock.elapsedRealtime()
        val bitmap = NativeSerRender.renderNatural(width, height, packed, color, 1.5f, .35f, .01f)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(width / 2, height / 2)) in 1..254)
            File(context.filesDir, "photon-raw-auto-timing.json").writeText(
                JSONObject().put("syntheticOnly", true).put("renderMs", elapsed)
                    .put("width", width).put("height", height).toString(2)
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun syntheticPublicationIsIdempotentAndFailedCopyLeavesNoRow() {
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "raw-auto-test-$id.jpg")
        val store = RawAutoPhotoStore(context)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(runCatching { store.publish(file, id, "image/jpeg") }.isFailure)
            assertEquals(0, ownRows(id))
            bitmap.eraseColor(Color.GRAY)
            FileOutputStream(file).use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it))
            }
            val first = store.publish(file, id, "image/jpeg")
            try {
                val repeated = store.publish(file, id, "image/x-adobe-dng")
                assertEquals(first, repeated)
                assertEquals("image/jpeg", repeated.second)
                assertEquals(1, ownRows(id))
            } finally {
                context.contentResolver.delete(first.first, null, null)
            }
            assertEquals(0, ownRows(id))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private fun ownRows(id: String): Int = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf("_id"),
        "_display_name IN (?, ?) AND owner_package_name = ?",
        arrayOf("PHOTON_RAW_AUTO_$id.jpg", "PHOTON_RAW_AUTO_$id.dng", context.packageName),
        null
    )!!.use { it.count }
}
