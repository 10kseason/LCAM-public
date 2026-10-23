package dev.lunacam.app.photon.raw

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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opens RAW-capable sessions and live preview. Never calls capture or reads any photograph. */
@RunWith(AndroidJUnit4::class)
class RawSessionDeviceTest {
    @Test
    fun opticalAutoPinchAndManualTransitionsWithoutCapturing() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("probeRawOptical") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val observations = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val view = preparePreview(scenario, provider)
                lateinit var model: RawCameraViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[RawCameraViewModel::class.java]
                    model.open(view)
                }
                suspend fun record(label: String) {
                    withTimeout(60_000) {
                        while (!model.ready || model.switching) delay(50)
                    }
                    observations.put(
                        JSONObject().put("step", label).put("selectedId", model.selectedId)
                            .put("auto", model.autoLens).put("canCapture", model.canCapture)
                            .put("opticalLabel", model.opticalLabel)
                            .put("zoomRatio", model.zoomRatio)
                            .put("digitalZoomRatio", model.digitalZoomRatio)
                    )
                    assertTrue("Preview became ready without a shutter", model.canCapture)
                    assertTrue("No background photo processing", model.processingCount == 0)
                    assertTrue(
                        "Digital zoom never invents a wider field",
                        model.digitalZoomRatio >= 1f
                    )
                    if (model.autoLens) {
                        val lens = model.opticalLenses.first { it.id == model.selectedId }
                        assertEquals(
                            "Physical sensor and digital crop preserve the requested field of view",
                            model.zoomRatio,
                            requireNotNull(lens.opticalRatio) * model.digitalZoomRatio,
                            .001f
                        )
                    }
                }
                try {
                    record("auto-default")
                    assertTrue("At least two optical RAW sources", model.opticalLenses.size >= 2)
                    withContext(Dispatchers.Main) { model.pinch(0.1f, view) }
                    record("auto-wide")
                    assertTrue(model.selectedId == model.opticalLenses.first().id)
                    assertEquals(model.minimumZoomRatio, model.zoomRatio, .001f)
                    for (lens in model.opticalLenses.drop(1)) {
                        // Cross the upward hysteresis boundary; an exact optical ratio may still
                        // intentionally use the wider sensor with a small digital crop.
                        val target = (requireNotNull(lens.opticalRatio) * 1.05f).coerceAtMost(8f)
                        withContext(Dispatchers.Main) { model.setZoom(target, view) }
                        record("auto-${lens.id}")
                        assertTrue(model.selectedId == lens.id)
                        assertEquals(target, model.zoomRatio, .001f)
                    }
                    assertTrue(model.selectedId == model.opticalLenses.last().id)
                    withContext(Dispatchers.Main) { model.setZoom(8f, view) }
                    record("auto-8x")
                    assertEquals(8f, model.zoomRatio, .001f)
                    withContext(Dispatchers.Main) { model.open(view) }
                    record("auto-8x-reopen")
                    assertEquals("Reopening preserves zoom", 8f, model.zoomRatio, .001f)
                    withContext(Dispatchers.Main) { model.setZoom(1f, view) }
                    record("auto-return-1x")
                    assertEquals(1f, model.zoomRatio, .001f)
                    withContext(Dispatchers.Main) {
                        model.select(model.lenses.last().id, view)
                    }
                    record("manual-public")
                    assertTrue(!model.autoLens)
                    withContext(Dispatchers.Main) { model.selectAuto(view) }
                    record("auto-reopen")
                    assertTrue(model.autoLens)
                } finally {
                    withContext(Dispatchers.Main) { model.closeCamera() }
                }
            }
        } finally {
            File(context.filesDir, "photon-raw-optical-transitions.json").writeText(
                JSONObject().put("sessions", observations).put("photosCaptured", 0).toString(2)
            )
        }
    }

    @Test
    fun rawSessionsOpenCloseAndReopenWithoutCapturing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val observations = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val view = preparePreview(scenario, provider)
            val lenses = RawBurstCamera(context).use { it.lenses() }
            assertTrue("At least one bounded RAW camera is required", lenses.isNotEmpty())
            for (lens in lenses + lenses.first()) {
                RawBurstCamera(context).use { camera ->
                    camera.open(lens.id, view)
                    observations.put(
                        JSONObject()
                            .put("cameraId", lens.id)
                            .put("rawWidth", lens.rawSize.width)
                            .put("rawHeight", lens.rawSize.height)
                            .put("previewResultReceived", true)
                    )
                    delay(150)
                }
                // Give the physical device its asynchronous close callback before the next lens.
                delay(250)
            }
        }
        File(context.filesDir, "photon-raw-session-verification.json").writeText(
            JSONObject().put("sessions", observations).put("photosCaptured", 0).toString(2)
        )
    }

    private suspend fun preparePreview(
        scenario: ActivityScenario<MainActivity>,
        provider: ProcessCameraProvider
    ): TextureView {
        lateinit var view: TextureView
        scenario.onActivity { activity ->
            view = TextureView(activity)
            activity.setContent { AndroidView(factory = { view }) }
            ViewModelProvider(activity)[PhotonCameraController::class.java].release()
            provider.unbindAll()
        }
        // Replacing the composition disposes the app camera; detaching only the old View does not.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[PhotonCameraController::class.java].release()
            provider.unbindAll()
        }
        delay(250)
        return view
    }
}
