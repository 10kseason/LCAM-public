package dev.lunacam.app.photon

import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lunacam.app.MainActivity
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No shutter, screenshots, photo access, or persistence of live histogram values. */
@RunWith(AndroidJUnit4::class)
class PhotonHistogramDeviceTest {
    @Test fun analyzerClosesProcessedSkippedAndMalformedFrames() {
        var now = 0L
        var closes = 0
        var updates = 0
        var latest: PhotonHistogram? = null
        val analyzer = PhotonHistogramAnalyzer({ PhotonAspectRatio.ORIGINAL }, { _, value ->
            updates++
            latest = value
        }, { now })
        analyzer.analyze(frame(ByteBuffer.allocate(16)) { closes++ })
        assertNotNull(latest)
        now = 100
        analyzer.analyze(frame(ByteBuffer.allocate(16)) { closes++ })
        assertEquals(1, updates)
        now = 200
        analyzer.analyze(frame(ByteBuffer.allocate(3)) { closes++ })
        assertNull(latest)
        assertEquals(2, updates)
        assertEquals(3, closes)
    }

    @Test fun analyzerClosesFrameEvenWhenConsumerThrows() {
        var closes = 0
        val analyzer = PhotonHistogramAnalyzer({ PhotonAspectRatio.ORIGINAL }, { _, _ ->
            error("consumer failure")
        })
        try {
            analyzer.analyze(frame(ByteBuffer.allocate(16)) { closes++ })
            throw AssertionError("Expected failure")
        } catch (_: IllegalStateException) {
            assertEquals(1, closes)
        }
    }

    @Test fun liveHistogramToggleCropPauseResumeAndRecreateWithoutCapture() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: PhotonCameraController
            var originalEnabled = false
            var originalRatio = PhotonAspectRatio.ORIGINAL
            scenario.onActivity {
                controller = ViewModelProvider(it)[PhotonCameraController::class.java]
                originalEnabled = controller.histogramEnabled
                originalRatio = controller.aspectRatio
                // The product starts in RAW AUTO. This test owns an explicit AUTO fixture.
                it.setContent { PhotonCameraScreen(onBack = {}, controller = controller) }
            }
            try {
                scenario.onActivity { controller.setHistogram(false) }
                await(scenario) { controller.canCapture && controller.histogram == null }
                scenario.onActivity { controller.setHistogram(true) }
                await(scenario) { controller.canCapture && controller.histogram != null }
                scenario.onActivity {
                    assertFalse(controller.histogramUnavailable)
                    assertTrue(requireNotNull(controller.histogram).samples <= 19_200)
                    controller.selectAspectRatio(PhotonAspectRatio.CINEMA)
                    assertNull(controller.histogram)
                }
                await(scenario) { controller.histogram != null }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity {
                    assertNull(controller.histogram)
                    assertFalse(controller.canCapture)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) { controller.canCapture && controller.histogram != null }
                // Recreate while stopped: the product's default RAW AUTO content must not
                // acquire the camera before this AUTO fixture replaces it.
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.recreate()
                scenario.onActivity {
                    // A recreated Activity restores its product content before this fixture.
                    controller = ViewModelProvider(it)[PhotonCameraController::class.java]
                    it.setContent { PhotonCameraScreen(onBack = {}, controller = controller) }
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) { controller.canCapture && controller.histogram != null }
                scenario.onActivity { controller.setHistogram(false) }
                await(scenario) { controller.canCapture && controller.histogram == null }
            } finally {
                scenario.onActivity {
                    controller.selectAspectRatio(originalRatio)
                    controller.setHistogram(originalEnabled)
                }
            }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            scenario.onActivity { done = predicate() }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Histogram/camera did not settle")
    }

    private fun frame(buffer: ByteBuffer, close: () -> Unit): ImageProxy {
        val plane = Proxy.newProxyInstance(
            ImageProxy.PlaneProxy::class.java.classLoader,
            arrayOf(ImageProxy.PlaneProxy::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBuffer" -> buffer
                "getRowStride" -> 4
                "getPixelStride" -> 1
                else -> null
            }
        } as ImageProxy.PlaneProxy
        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java)
        ) {
                _,
                method,
                _
            ->
            when (method.name) {
                "getCropRect" -> Rect(0, 0, 4, 4)
                "getPlanes" -> arrayOf(plane)
                "close" -> {
                    close()
                    null
                }
                else -> null
            }
        } as ImageProxy
    }
}
