package dev.lunacam.app.photon.rawauto

import android.graphics.BitmapFactory
import android.hardware.camera2.DngCreator
import android.os.SystemClock
import android.util.Size
import android.view.TextureView
import androidx.activity.compose.setContent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import dev.lunacam.app.photon.PhotonCameraController
import dev.lunacam.app.photon.raw.RawBurstCamera
import dev.lunacam.app.photon.raw.RawCaptureJob
import dev.lunacam.app.photon.raw.RawControls
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: ONE shutter containing three real exposures and ONE public output.
 * Source DNGs remain private for local diagnosis, so a failed test never destroys a photograph. */
@RunWith(AndroidJUnit4::class)
class RawHdrCaptureDeviceTest {
    @Test fun oneAuthorizedHdrShutterAndZoomedSave() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realRawHdrCapture") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: TextureView
            scenario.onActivity { activity ->
                view = TextureView(activity)
                activity.setContent { AndroidView(factory = { view }) }
                ViewModelProvider(activity)[PhotonCameraController::class.java].release()
                provider.unbindAll()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[PhotonCameraController::class.java].release()
                provider.unbindAll()
            }
            delay(250)
            RawBurstCamera(context).use { camera ->
                val lens = camera.opticalLenses().minBy {
                    abs(requireNotNull(it.opticalRatio) - 1f)
                }
                camera.open(lens, view)
                camera.setControls(
                    RawControls(evIndex = rawAutoEvIndex(camera.controlCapabilities()))
                )
                scenario.onActivity { camera.setDigitalZoom(1.4f, view) }
                val started = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val bias = rawAutoCompensationBias(camera.controlCapabilities())
                val burst = camera.capture(3, 0, hdr = true).copy(compensationBiasEv = bias)
                val received = SystemClock.elapsedRealtime()
                val job = RawCaptureJob(UUID.randomUUID().toString(), started, wall, received)
                if (burst.previewRestored) job.markShutterReady(received)
                val evidence = File(context.filesDir, "raw-hdr-real-${job.id}").apply { mkdirs() }
                burst.frames.forEachIndexed { index, frame ->
                    DngCreator(burst.characteristics, frame.result).use { creator ->
                        creator.setOrientation(
                            when (burst.orientationDegrees) {
                                90 -> 6
                                180 -> 3
                                270 -> 8
                                else -> 1
                            }
                        )
                        FileOutputStream(File(evidence, "source-$index.dng")).use {
                            creator.writeByteBuffer(
                                it,
                                Size(frame.width, frame.height),
                                frame.pixels.duplicate(),
                                0
                            )
                            it.fd.sync()
                        }
                    }
                }
                val saved = RawAutoPhotoStore(context).save(burst, .35f, job)
                val receipt =
                    JSONObject(File(context.filesDir, "photon-raw-last-save.json").readText())
                File(evidence, "receipt.json").writeText(receipt.toString(2))
                File(context.filesDir, "raw-hdr-real-last.json").writeText(
                    JSONObject().put("folder", evidence.name).put("output", saved.uri.toString())
                        .put("jobId", job.id).toString(2)
                )
                assertEquals("Three actual RAW exposures", 3, burst.frames.size)
                assertTrue("Preview restored", burst.previewRestored)
                assertEquals("saved", receipt.getString("status"))
                assertEquals(1, receipt.getInt("outputCount"))
                assertEquals("image/jpeg", receipt.getString("mimeType"))
                assertTrue(receipt.getBoolean("hdrRequested"))
                // Scene-dependent rejection is valid. Record it; never fake a successful merge.
                assertTrue(receipt.has("hdrReason"))
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(saved.uri).use {
                    BitmapFactory.decodeStream(it, null, dimensions)
                }
                assertEquals(receipt.getInt("encodedWidth"), dimensions.outWidth)
                assertEquals(receipt.getInt("encodedHeight"), dimensions.outHeight)
                assertTrue(dimensions.outWidth < burst.frames.first().width)
            }
        }
    }
}
