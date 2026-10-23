package dev.lunacam.app.photon

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No shutter calls. Window screenshots are taken only after hiding the live PreviewView. */
@RunWith(AndroidJUnit4::class)
class PhotonUiDeviceTest {
    @Test fun optInFramingControlsStayVisibleWithPrivatePreviewHidden() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPhotonUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val observations = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: PhotonCameraController
            var oldRatio = PhotonAspectRatio.ORIGINAL
            var oldGrid = false
            var oldLevel = false
            scenario.onActivity { activity ->
                controller = PhotonCameraController(activity.application)
                oldRatio = controller.aspectRatio
                oldGrid = controller.gridEnabled
                oldLevel = controller.levelEnabled
                controller.setGrid(true)
                controller.setLevel(true)
            }
            try {
                for (landscape in listOf(false, true)) {
                    scenario.onActivity { activity ->
                        activity.requestedOrientation = if (landscape) {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
                    await {
                        var valid = false
                        scenario.onActivity { activity ->
                            val config = activity.resources.configuration
                            valid = (config.screenWidthDp > config.screenHeightDp) == landscape
                        }
                        valid
                    }
                    for (fontScale in listOf(1f, 1.35f)) {
                        scenario.onActivity { activity ->
                            activity.setContent {
                                CompositionLocalProvider(
                                    LocalInspectionMode provides true,
                                    LocalDensity provides Density(
                                        activity.resources.displayMetrics.density,
                                        fontScale
                                    )
                                ) {
                                    PhotonCameraScreen(onBack = {}, controller = controller)
                                }
                            }
                        }
                        await {
                            var valid = false
                            scenario.onActivity { activity ->
                                valid =
                                    findPreview(activity.window.decorView)?.width?.let { it > 0 }
                                        ?: false
                            }
                            valid && node("사진 촬영") != null
                        }
                        for (ratio in PhotonAspectRatio.entries) {
                            val description = "사진 비율 ${ratio.label}"
                            if (controller.aspectRatio != ratio) {
                                await {
                                    // Compose can replace a virtual node when camera readiness
                                    // changes. Reacquire it rather than acting on a stale snapshot.
                                    var chip = node(description)
                                    repeat(4) {
                                        if (chip?.isClickable == true) return@repeat
                                        chip = chip?.parent
                                    }
                                    chip?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                                }
                            }
                            await { controller.aspectRatio == ratio }
                            SystemClock.sleep(150)
                            scenario.onActivity { activity ->
                                val preview = requireNotNull(findPreview(activity.window.decorView))
                                val actual = preview.width.toFloat() / preview.height
                                assertTrue(
                                    "Viewfinder ratio=$actual expected=${ratio.previewRatio(
                                        landscape
                                    )}",
                                    kotlin.math.abs(actual - ratio.previewRatio(landscape)) < 0.025f
                                )
                            }
                            val shutter = requireNotNull(node("사진 촬영"))
                            val bounds = Rect().also(shutter::getBoundsInScreen)
                            val chipBounds = Rect().also(
                                requireNotNull(node(description))::getBoundsInScreen
                            )
                            scenario.onActivity { activity ->
                                val window = Rect()
                                activity.window.decorView.getWindowVisibleDisplayFrame(window)
                                assertTrue(
                                    "Shutter always in visible window",
                                    window.contains(bounds)
                                )
                                assertTrue(
                                    "Ratio always in visible window",
                                    window.contains(chipBounds)
                                )
                                assertTrue(
                                    "Large shutter",
                                    bounds.width() >= 56 * activity.resources.displayMetrics.density
                                )
                                observations.put(
                                    JSONObject().put("landscape", landscape)
                                        .put("fontScale", fontScale).put("ratio", ratio.id)
                                        .put(
                                            "previewWidth",
                                            findPreview(activity.window.decorView)!!.width
                                        )
                                        .put(
                                            "previewHeight",
                                            findPreview(activity.window.decorView)!!.height
                                        )
                                        .put("shutter", bounds.toShortString())
                                )
                            }
                        }
                        // INVISIBLE removes live sensor pixels from this app window before PixelCopy.
                        scenario.onActivity { activity ->
                            findPreview(activity.window.decorView)!!.visibility = View.INVISIBLE
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(200)
                        val latch = CountDownLatch(1)
                        var status = -1
                        lateinit var bitmap: Bitmap
                        scenario.onActivity { activity ->
                            assertEquals(
                                View.INVISIBLE,
                                findPreview(activity.window.decorView)!!.visibility
                            )
                            bitmap = Bitmap.createBitmap(
                                activity.window.decorView.width,
                                activity.window.decorView.height,
                                Bitmap.Config.ARGB_8888
                            )
                            PixelCopy.request(activity.window, bitmap, { result ->
                                status = result
                                latch.countDown()
                            }, Handler(Looper.getMainLooper()))
                        }
                        assertTrue(latch.await(5, TimeUnit.SECONDS))
                        assertEquals(PixelCopy.SUCCESS, status)
                        try {
                            val direction = if (landscape) "landscape" else "portrait"
                            val name = "photon-ui-$direction-$fontScale.png"
                            File(context.filesDir, name).outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            } finally {
                scenario.onActivity {
                    controller.selectAspectRatio(oldRatio)
                    controller.setGrid(oldGrid)
                    controller.setLevel(oldLevel)
                    controller.release()
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                File(context.filesDir, "photon-ui-verification.json").writeText(
                    JSONObject().put(
                        "photosCaptured",
                        0
                    ).put("livePreviewHiddenInScreenshots", true)
                        .put("recentThumbnailHiddenInScreenshots", true)
                        .put("layouts", observations).toString(2)
                )
            }
        }
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findPreview(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun node(description: String): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return null
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.contentDescription?.toString() == description) return current
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }
        return find(root)
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Camera UI did not settle")
    }
}
