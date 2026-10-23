package dev.lunacam.app.photon

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device checks. Never presses the shutter, reads photos, or exports data off-device. */
@RunWith(AndroidJUnit4::class)
class PhotonDeviceTest {
    @Test
    fun liveCapabilitiesHavePublicCamerasAndExplicitExtensionStates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA)
        )
        val report = PhotonCapabilityScanner(context).scan()
        assertEquals(1, report.getInt("schemaVersion"))
        val cameras = report.getJSONArray("cameras")
        assertTrue(cameras.length() > 0)
        for (index in 0 until cameras.length()) {
            val camera = cameras.getJSONObject(index)
            assertTrue(camera.has("id"))
            assertTrue(camera.getJSONObject("formats").has("RAW_SENSOR"))
            for (mode in listOf("HDR", "NIGHT")) {
                assertTrue(
                    camera.getJSONObject("extensions").getString(mode) in
                        setOf("supported", "unsupported", "unknown")
                )
            }
        }
        File(context.filesDir, "photon-capabilities-live.json").writeText(report.toString(2))
    }

    @Test
    fun exposedLensesStreamAndResumeWithoutCapturing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA)
        )
        val observations = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: PhotonCameraController
            lateinit var view: PreviewView
            var initialized = false
            try {
                scenario.onActivity { activity ->
                    controller = PhotonCameraController(activity.application)
                    initialized = true
                    view = PreviewView(activity).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    }
                    activity.setContentView(view)
                    controller.bind(activity, view)
                }
                awaitReady {
                    controller.ready &&
                        view.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
                var ids = emptyList<String>()
                scenario.onActivity { ids = controller.lenses.map { it.id } }
                for (id in ids) {
                    scenario.onActivity { activity ->
                        controller.select(id)
                        controller.bind(activity, view)
                    }
                    awaitReady {
                        controller.ready &&
                            view.previewStreamState.value == PreviewView.StreamState.STREAMING
                    }
                    scenario.onActivity {
                        assertEquals(id, controller.selectedId)
                        observations.put(JSONObject().put("id", id).put("streaming", true))
                    }
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.onActivity { assertTrue(!controller.canCapture) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitReady {
                    controller.canCapture &&
                        view.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
                File(context.filesDir, "photon-session-smoke.json").writeText(
                    JSONObject().put("lenses", observations).put("resumeStreaming", true)
                        .put("photosCaptured", 0).toString(2)
                )
            } finally {
                scenario.onActivity { if (initialized) controller.release() }
            }
        }
    }

    private fun awaitReady(check: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            Thread.sleep(100)
        }
        throw AssertionError("Camera did not become ready/streaming within 15 seconds")
    }
}
