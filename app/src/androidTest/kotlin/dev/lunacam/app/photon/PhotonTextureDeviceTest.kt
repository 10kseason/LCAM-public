package dev.lunacam.app.photon

import android.app.Application
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic pixels and injected touches; no shutter or user-photo access. */
@RunWith(AndroidJUnit4::class)
class PhotonTextureDeviceTest {
    @Test fun pinchChangesActualZoomAndNeverFiresFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: PhotonCameraController
            lateinit var preview: PreviewView
            var taps = 0
            var initialized = false
            try {
                scenario.onActivity { activity ->
                    controller = PhotonCameraController(activity.application)
                    initialized = true
                    preview = PreviewView(activity).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    preview.setOnTouchListener(
                        PhotonPreviewTouchListener(activity, controller::pinchZoom) {
                                _,
                                _
                            ->
                            taps++
                        }
                    )
                    activity.setContentView(preview)
                    controller.bind(activity, preview)
                }
                await { controller.ready && controller.canZoom }
                var start = 1f
                scenario.onActivity {
                    start = controller.zoomRatio
                    injectPinch(preview, 160f, 600f)
                }
                await { controller.zoomRatio > start * 1.1f }
                var expanded = 1f
                scenario.onActivity {
                    expanded = controller.zoomRatio
                    assertEquals(0, taps)
                    injectPinch(preview, 600f, 160f)
                }
                await { controller.zoomRatio < expanded * 0.9f }
                scenario.onActivity {
                    assertEquals(0, taps)
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0).also {
                        preview.dispatchTouchEvent(it)
                        it.recycle()
                    }
                    MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, 100f, 100f, 0).also {
                        preview.dispatchTouchEvent(it)
                        it.recycle()
                    }
                    assertEquals(1, taps)
                    File(
                        instrumentation.targetContext.filesDir,
                        "photon-pinch-verification.json"
                    ).writeText(
                        JSONObject().put("startZoom", start).put("expandedZoom", expanded)
                            .put("contractedZoom", controller.zoomRatio).put("focusAfterPinch", 0)
                            .put("focusAfterTap", taps).put("physicalFingerTest", false).toString(2)
                    )
                }
            } finally {
                scenario.onActivity { if (initialized) controller.release() }
            }
        }
    }

    @Test fun synthetic12MpPreservesSourceAndExif() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "texture-test-${UUID.randomUUID()}.jpg")
        var output: Uri? = null
        try {
            val bitmap = Bitmap.createBitmap(4080, 3060, Bitmap.Config.ARGB_8888)
            try {
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    for (x in row.indices) {
                        val hash = (x / 2 * 73856093) xor (y / 2 * 19349663)
                        val co = (hash and Int.MAX_VALUE) % 9 - 4
                        val cg = ((hash ushr 8) and 255) % 5 - 2
                        row[x] =
                            (255 shl 24) or ((128 + co - cg) shl 16) or ((128 + cg) shl 8) or
                            (128 - co - cg)
                    }
                    bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                }
                source.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                saveAttributes()
            }
            val originalHash = hash(source)
            val outputName = "PHOTON_SYNTHETIC_TEST_${UUID.randomUUID()}.jpg"
            val result = PhotonPhotoProcessor(context).process(Uri.fromFile(source), outputName)
            output = result.uri
            assertNotNull(output)
            assertTrue(result.stats.changedPixels > 0)
            assertEquals(originalHash, hash(source))
            context.contentResolver.openInputStream(output!!)!!.use { stream ->
                val exif = ExifInterface(stream)
                assertEquals(6, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                assertFalse(exif.getLatLong(FloatArray(2)))
                val description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                assertTrue(description!!.contains(TextureGuard.VERSION))
            }
            context.contentResolver.query(
                output,
                arrayOf("is_pending", "_size"),
                null,
                null,
                null
            )!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertTrue(it.getLong(1) > 0)
            }
            File(context.filesDir, "photon-texture-benchmark.json").writeText(
                JSONObject().put("width", 4080).put("height", 3060).put("synthetic", true)
                    .put(
                        "elapsedMs",
                        result.elapsedMs
                    ).put("changedPixels", result.stats.changedPixels)
                    .put(
                        "examinedPixels",
                        result.stats.examinedPixels
                    ).put("sourceHashUnchanged", true)
                    .put("orientationPreserved", true).put("gpsPresent", false)
                    .put("decodeMs", result.decodeMs).put("filterMs", result.filterMs)
                    .put("saveMs", result.saveMs).toString(2)
            )
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun invalidInputLeavesSourceIntactAndNoMediaRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "texture-invalid-${UUID.randomUUID()}.bin")
        val outputName = "PHOTON_INVALID_TEST_${UUID.randomUUID()}.jpg"
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val before = hash(file)
            var failed = false
            try {
                PhotonPhotoProcessor(context).process(Uri.fromFile(file), outputName)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(before, hash(file))
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id"),
                "_display_name = ?",
                arrayOf(outputName),
                null
            )!!.use { assertEquals(0, it.count) }
        } finally {
            file.delete()
        }
    }

    @Test fun processingPreferenceCanBeSwitchedAndRestored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val application = instrumentation.targetContext.applicationContext as Application
            val model = PhotonCameraController(application)
            val initial = model.postProcessingEnabled
            val initialDetail = model.detailEnabled
            try {
                model.setPostProcessing(false)
                assertFalse(PhotonCameraController(application).postProcessingEnabled)
                model.setPostProcessing(true)
                assertTrue(PhotonCameraController(application).postProcessingEnabled)
                model.setDetail(true)
                assertTrue(PhotonCameraController(application).detailEnabled)
                model.setDetail(false)
                assertFalse(PhotonCameraController(application).detailEnabled)
            } finally {
                model.setPostProcessing(initial)
                model.setDetail(initialDetail)
            }
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun await(check: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("Camera zoom did not reach the expected state")
    }

    private fun injectPinch(view: PreviewView, startSpan: Float, endSpan: Float) {
        val down = SystemClock.uptimeMillis()
        val cx = view.width / 2f
        val cy = view.height / 2f
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun event(action: Int, count: Int, span: Float, offset: Long) {
            val coords = Array(count) { index ->
                MotionEvent.PointerCoords().apply {
                    x = cx + if (index == 0) -span / 2 else span / 2
                    y = cy
                    pressure = 1f
                    size = 1f
                }
            }
            MotionEvent.obtain(
                down, down + offset, action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
            ).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 1, startSpan, 0)
        event(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            startSpan,
            10
        )
        for (step in 1..10) {
            event(
                MotionEvent.ACTION_MOVE,
                2,
                startSpan + (endSpan - startSpan) * step / 10f,
                10 + step * 20L
            )
        }
        event(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            endSpan,
            240
        )
        event(MotionEvent.ACTION_UP, 1, endSpan, 260)
    }
}
