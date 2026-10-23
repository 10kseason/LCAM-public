package dev.lunacam.app.photon

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Preview metadata and synthetic touches only. Never invokes the shutter or reads photo pixels. */
@RunWith(AndroidJUnit4::class)
class PhotonFocusExposureDeviceTest {
    @Test fun exposureReachesSensorAndFocusRequestsCannotSurviveReplacementOrRebind() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: PhotonCameraController
            lateinit var preview: PreviewView
            var initialized = false
            var originalRatio = PhotonAspectRatio.ORIGINAL
            val report = JSONObject().put("photosCaptured", 0)
            try {
                scenario.onActivity { activity ->
                    controller = PhotonCameraController(activity.application)
                    initialized = true
                    originalRatio = controller.aspectRatio
                    preview = PreviewView(activity).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    activity.setContentView(preview)
                    controller.bind(activity, preview)
                }
                await {
                    controller.ready &&
                        preview.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
                var requested = 0
                scenario.onActivity {
                    assertTrue(
                        "Primary camera must expose EV compensation for this device check",
                        controller.supportsExposure
                    )
                    val target = if (controller.maxExposureEv > 0f) {
                        minOf(1f, controller.maxExposureEv)
                    } else {
                        maxOf(-1f, controller.minExposureEv)
                    }
                    controller.adjustExposureEv(target)
                    requested = controller.snapshotControls().getInt("exposureRequestedIndex")
                    assertTrue(requested != 0)
                }
                await {
                    val controls = controller.snapshotControls()
                    controls.optInt("exposureAppliedIndex", Int.MIN_VALUE) == requested &&
                        controls.optInt("exposurePreviewIndex", Int.MIN_VALUE) == requested
                }
                scenario.onActivity {
                    report.put("evApplied", controller.snapshotControls())
                    controller.adjustExposureEv(controller.minExposureEv)
                    controller.adjustExposureEv(controller.maxExposureEv)
                    controller.adjustExposureEv(0f)
                    controller.adjustExposureEv(Float.NaN)
                }
                await {
                    val controls = controller.snapshotControls()
                    controls.optInt("exposureAppliedIndex", Int.MIN_VALUE) == 0 &&
                        controls.optInt("exposurePreviewIndex", Int.MIN_VALUE) == 0
                }
                scenario.onActivity {
                    controller.focus(
                        preview,
                        preview.width * .3f,
                        preview.height * .4f,
                        hold = true
                    )
                    assertNotNull(
                        "Primary camera must support AF for this device check",
                        controller.focusFeedback
                    )
                    assertTrue(controller.focusLocked)
                    assertEquals(PhotonFocusStatus.FOCUSING, controller.focusFeedback!!.status)
                    controller.focus(preview, preview.width * .7f, preview.height * .6f)
                    assertFalse(controller.focusLocked)
                    assertEquals(.7f, controller.focusFeedback!!.xFraction, .001f)
                    assertEquals(.6f, controller.focusFeedback!!.yFraction, .001f)
                }
                await { controller.focusFeedback?.status != PhotonFocusStatus.FOCUSING }
                scenario.onActivity {
                    // Scene-dependent focus failure is valid feedback, not proof of sharpness.
                    assertTrue(
                        controller.focusFeedback!!.status in
                            listOf(PhotonFocusStatus.FOCUSED, PhotonFocusStatus.FAILED)
                    )
                    assertEquals(.7f, controller.focusFeedback!!.xFraction, .001f)
                    assertFalse(controller.focusLocked)
                    report.put("tapStatus", controller.focusFeedback!!.status.name)
                    controller.focus(
                        preview,
                        preview.width * .5f,
                        preview.height * .5f,
                        hold = true
                    )
                }
                await { controller.focusFeedback?.status != PhotonFocusStatus.FOCUSING }
                scenario.onActivity {
                    val status = controller.focusFeedback!!.status
                    assertTrue(
                        status == PhotonFocusStatus.LOCKED || status == PhotonFocusStatus.FAILED
                    )
                    assertEquals(status == PhotonFocusStatus.LOCKED, controller.focusLocked)
                    report.put("holdStatus", status.name)
                    if (controller.canZoom) {
                        controller.zoom(.2f)
                        assertNull(controller.focusFeedback)
                        assertFalse(controller.focusLocked)
                    }
                    controller.focus(
                        preview,
                        preview.width * .5f,
                        preview.height * .5f,
                        hold = true
                    )
                    controller.selectAspectRatio(
                        if (originalRatio == PhotonAspectRatio.WIDE) {
                            PhotonAspectRatio.CINEMA
                        } else {
                            PhotonAspectRatio.WIDE
                        }
                    )
                    assertNull(controller.focusFeedback)
                    assertFalse(controller.focusLocked)
                }
                scenario.onActivity { activity ->
                    controller.focus(
                        preview,
                        preview.width * .5f,
                        preview.height * .5f,
                        hold = true
                    )
                    controller.adjustExposureEv(controller.maxExposureEv)
                    controller.bind(activity, preview)
                    assertNull(controller.focusFeedback)
                    assertFalse(controller.focusLocked)
                    assertEquals(0f, controller.exposureEv, 0f)
                }
                await {
                    val controls = controller.snapshotControls()
                    controller.ready &&
                        controls.optInt("exposureAppliedIndex", Int.MIN_VALUE) == 0 &&
                        controls.optInt("exposurePreviewIndex", Int.MIN_VALUE) == 0
                }
                scenario.onActivity {
                    assertNull(controller.focusFeedback)
                    assertFalse(controller.focusLocked)
                    report.put("rebound", controller.snapshotControls())
                    controller.focus(
                        preview,
                        preview.width * .5f,
                        preview.height * .5f,
                        hold = true
                    )
                    controller.release()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertNull(controller.focusFeedback)
                    assertFalse(controller.focusLocked)
                    assertFalse(controller.supportsExposure)
                    File(
                        instrumentation.targetContext.filesDir,
                        "photon-focus-exposure-verification.json"
                    ).writeText(report.toString(2))
                }
            } finally {
                scenario.onActivity {
                    if (initialized) {
                        controller.selectAspectRatio(originalRatio)
                        controller.release()
                    }
                }
            }
        }
    }

    @Test fun longHoldIsSingleAndMultiPointerOrCancelledGesturesNeverTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: View
        lateinit var listener: PhotonPreviewTouchListener
        var holds = 0
        var taps = 0
        var down = 0L
        instrumentation.runOnMainSync {
            view = View(instrumentation.targetContext)
            listener = PhotonPreviewTouchListener(
                instrumentation.targetContext,
                {},
                { _, _ -> holds++ }
            ) { _, _ -> taps++ }
            down = SystemClock.uptimeMillis()
            dispatch(listener, view, down, MotionEvent.ACTION_DOWN)
        }
        // Only this instrumentation thread waits; production gesture/camera code never sleeps.
        Thread.sleep(
            (
                ViewConfiguration.getLongPressTimeout() +
                    ViewConfiguration.getTapTimeout() + 100
                ).toLong()
        )
        instrumentation.runOnMainSync {
            assertEquals(1, holds)
            dispatch(listener, view, down, MotionEvent.ACTION_UP)
            assertEquals(0, taps)
            down = SystemClock.uptimeMillis()
            dispatch(listener, view, down, MotionEvent.ACTION_DOWN)
            dispatch(
                listener,
                view,
                down,
                MotionEvent.ACTION_POINTER_DOWN or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2
            )
            dispatch(listener, view, down, MotionEvent.ACTION_MOVE, 2)
            dispatch(
                listener,
                view,
                down,
                MotionEvent.ACTION_POINTER_UP or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2
            )
            dispatch(listener, view, down, MotionEvent.ACTION_UP)
            assertEquals(1, holds)
            assertEquals(0, taps)
            down = SystemClock.uptimeMillis()
            dispatch(listener, view, down, MotionEvent.ACTION_DOWN)
            dispatch(listener, view, down, MotionEvent.ACTION_CANCEL)
        }
        Thread.sleep((ViewConfiguration.getLongPressTimeout() + 100).toLong())
        instrumentation.runOnMainSync {
            assertEquals(1, holds)
            assertEquals(0, taps)
            down = SystemClock.uptimeMillis()
            dispatch(listener, view, down, MotionEvent.ACTION_DOWN)
            dispatch(listener, view, down, MotionEvent.ACTION_UP)
            assertEquals(1, taps)
            assertEquals(1, holds)
        }
    }

    private fun await(check: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("Camera control did not reach the expected state")
    }

    private fun dispatch(
        listener: PhotonPreviewTouchListener,
        view: View,
        down: Long,
        action: Int,
        count: Int = 1
    ) {
        val properties = Array(count) { id ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { id ->
            MotionEvent.PointerCoords().apply {
                x = 100f + id * 100
                y = 100f
                pressure = 1f
                size = 1f
            }
        }
        MotionEvent.obtain(
            down, SystemClock.uptimeMillis(), action, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        ).also {
            listener.onTouch(view, it)
            it.recycle()
        }
    }
}
