package dev.lunacam.app.photon.raw

import android.view.TextureView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import dev.lunacam.app.photon.night.SerNightViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in preview and metadata only. No RAW target request, pixel read, or shutter invocation. */
@RunWith(AndroidJUnit4::class)
class NightSessionDeviceTest {
    @Test
    fun nightPrimaryPreviewReadinessAndReopenWithoutPhotographs() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeNightSession") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val observations = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: TextureView
                lateinit var model: SerNightViewModel
                scenario.onActivity { activity ->
                    view = TextureView(activity)
                    activity.setContentView(view)
                    provider.unbindAll()
                    model = ViewModelProvider(activity)[SerNightViewModel::class.java]
                }
                try {
                    repeat(2) { cycle ->
                        withContext(Dispatchers.Main) { model.open(view) }
                        withTimeout(20_000) {
                            while (model.previewReadiness.previewAgeMs == null) delay(50)
                        }
                        // A textureless/dark subject can legitimately prevent AF readiness.
                        // Record that reason rather than treating no shutter as a quality proof.
                        var samples = 0
                        while (!model.ready && samples++ < 100) delay(100)
                        withContext(Dispatchers.Main) {
                            val state = model.previewReadiness
                            observations.put(
                                JSONObject().put("cycle", cycle).put("ready", state.ready)
                                    .put("reason", state.reason)
                                    .put("previewAgeMs", state.previewAgeMs)
                                    .put("physicalId", state.physicalId)
                                    .put("width", state.width).put("height", state.height)
                                    .put("hasColorGains", state.hasColorGains)
                                    .put("hasColorTransform", state.hasColorTransform)
                                    .put("exposureNs", state.exposureNs).put("iso", state.iso)
                                    .put("canCapture", model.canCapture)
                            )
                            assertEquals(4080, state.width)
                            assertEquals(3060, state.height)
                            assertTrue(state.physicalId != null)
                            assertTrue((state.previewAgeMs ?: Long.MAX_VALUE) in 0L..250L)
                            assertEquals(state.ready, model.canCapture)
                            assertEquals(0, model.processingCount)
                            assertFalse(model.busy)
                            assertTrue(model.recentPhotoUri == null)
                            model.closeCamera()
                            assertFalse(model.canCapture)
                        }
                        delay(300)
                    }
                } finally {
                    withContext(Dispatchers.Main) { model.closeCamera() }
                }
            }
        } finally {
            File(context.filesDir, "photon-night-session-verification.json").writeText(
                JSONObject().put("sessions", observations).put("photosCaptured", 0)
                    .put("photoPixelsRead", 0).toString(2)
            )
        }
    }
}
