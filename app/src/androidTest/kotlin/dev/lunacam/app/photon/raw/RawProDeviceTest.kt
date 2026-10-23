package dev.lunacam.app.photon.raw

import android.hardware.camera2.CaptureResult
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
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live preview and preview-only capture preparation. Never submits a RAW target or reads pixels. */
@RunWith(AndroidJUnit4::class)
class RawProDeviceTest {
    @Test fun supportedControlsReachPhysicalPreviewAndCapturePreparation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val observations = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: TextureView
            scenario.onActivity { activity ->
                view = TextureView(activity)
                activity.setContent { AndroidView(factory = { view }) }
                ViewModelProvider(activity)[PhotonCameraController::class.java].release()
                provider.unbindAll()
            }
            // Replace the composition itself, not only its View: ComponentActivity keeps the
            // old composition alive until destruction when a plain setContentView detaches it.
            // Drain disposal before the standalone Camera2 fixture takes ownership.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[PhotonCameraController::class.java].release()
                provider.unbindAll()
            }
            delay(250)
            val lenses = RawBurstCamera(context).use { it.opticalLenses() }
            assertTrue("Physical RAW lenses available", lenses.isNotEmpty())
            for (lens in lenses) {
                RawBurstCamera(context).use { camera ->
                    suspend fun <T> step(label: String, action: suspend () -> T): T = try {
                        action()
                    } catch (failure: Exception) {
                        val actual = runCatching { camera.controlObservation() }.getOrNull()
                        throw AssertionError("RAW ${lens.id} $label; actual=$actual", failure)
                    }
                    camera.open(lens, view)
                    val support = camera.controlCapabilities()
                    val row = JSONObject().put("lens", lens.id)
                        .put("manualExposure", support.manualExposure)
                        .put("manualFocus", support.manualFocus)
                        .put("tapAf", support.tapAf).put("tapAe", support.tapAe)
                        .put("whiteBalances", JSONArray(support.whiteBalances.map { it.label }))
                    observations.put(row)
                    step("initial auto") { camera.setControls(RawControls()) }
                    assertTrue(
                        "Expected manual exposure on test phone rear sensor",
                        support.manualExposure
                    )
                    val wb =
                        support.whiteBalances.firstOrNull { it != RawWhiteBalance.AUTO }
                            ?: RawWhiteBalance.AUTO
                    val manual = RawControls(
                        manualExposure = true,
                        exposureNs = 10_000_000,
                        iso = 400,
                        focusDiopters = if (support.manualFocus) 0f else null,
                        whiteBalance = wb
                    ).bounded(support)
                    step("manual controls") { camera.setControls(manual) }
                    var actual = camera.controlObservation()
                    withTimeout(4_000) {
                        while (actual.aeMode != CaptureResult.CONTROL_AE_MODE_OFF ||
                            actual.awbMode != wb.mode || actual.iso == null ||
                            abs(actual.iso - manual.iso) > manual.iso * .1 ||
                            abs((actual.exposureNs ?: 0) - manual.exposureNs) >
                            manual.exposureNs * .1
                        ) {
                            delay(50)
                            actual = camera.controlObservation()
                        }
                    }
                    if (support.manualFocus) {
                        assertEquals(CaptureResult.CONTROL_AF_MODE_OFF, actual.afMode)
                        assertEquals(0f, requireNotNull(actual.focusDiopters), .1f)
                    }
                    row.put("reportedIso", actual.iso).put("reportedExposureNs", actual.exposureNs)
                        .put("reportedAwbMode", actual.awbMode)
                    // The same locked policy feeds the actual burst; only PREVIEW is requested here.
                    val prepared = step("capture preparation") { camera.prepareCapture() }
                    assertEquals(
                        CaptureResult.CONTROL_AE_MODE_OFF,
                        prepared.get(CaptureResult.CONTROL_AE_MODE)
                    )
                    assertEquals(wb.mode, prepared.get(CaptureResult.CONTROL_AWB_MODE))
                    if (support.manualFocus) {
                        assertEquals(
                            CaptureResult.CONTROL_AF_MODE_OFF,
                            prepared.get(CaptureResult.CONTROL_AF_MODE)
                        )
                    }
                    row.put("capturePreparation", true)
                    val ev = if (support.ev) {
                        minOf(
                            support.maxEvIndex,
                            maxOf(1, (1f / support.evStep).toInt())
                        )
                    } else {
                        0
                    }
                    step("auto EV") { camera.setControls(RawControls(evIndex = ev)) }
                    withTimeout(4_000) {
                        while (camera.controlObservation().evIndex != ev) delay(50)
                    }
                    if (support.tapAf || support.tapAe) {
                        val focus = step("tap focus") {
                            camera.tapFocus(
                                view.width / 2f,
                                view.height / 2f,
                                view.width,
                                view.height,
                                view.display.rotation
                            )
                        }
                        row.put("tapResult", focus.afState ?: JSONObject.NULL)
                        assertEquals(CaptureResult.CONTROL_AE_MODE_ON, focus.aeMode)
                    }
                    step("reset auto") { camera.setControls(RawControls()) }
                    assertEquals(
                        CaptureResult.CONTROL_AE_MODE_ON,
                        camera.controlObservation().aeMode
                    )
                }
                delay(250)
            }
        }
        File(context.filesDir, "photon-raw-pro-verification.json").writeText(
            JSONObject().put("photosCaptured", 0).put("lenses", observations).toString(2)
        )
    }
}
