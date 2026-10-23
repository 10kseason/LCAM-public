package dev.lunacam.app.photon

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.LunaCamApp
import dev.lunacam.app.MainActivity
import dev.lunacam.app.photon.raw.RawCameraViewModel
import dev.lunacam.feature.diagnostics.CameraPermissionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation and permission rendering only; never presses a shutter or captures the screen. */
@RunWith(AndroidJUnit4::class)
class PhotonStartupDeviceTest {
    @Test fun rawAutoStartupRecreationAndSharedModeNavigationWithoutDiagnostics() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitNatural(scenario) { it.canCapture || it.unsupported }
            var unsupported = false
            scenario.onActivity {
                val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                unsupported = natural.unsupported
            }
            val startup = if (unsupported) "현재 모드 AUTO" else "현재 모드 RAW AUTO"
            await { node(startup) != null && node("사진 촬영") != null }
            assertNoDiagnostics()
            scenario.recreate()
            await { node(startup) != null && node("사진 촬영") != null }
            // Exercise a real capability fallback without forcing an invalid RAW session.
            if (unsupported) return
            awaitNatural(scenario) { it.canCapture }
            assertRawPreviewClipped(scenario)
            click("RAW 모드")
            await { node("현재 모드 RAW") != null }
            awaitRaw(scenario) { it.canCapture }
            click("보정 설정")
            await { node("촬영 설정")?.isVisibleToUser == true }
            clickInSettings("초점·Pro")
            await { node("RAW 초점·Pro") != null }
            var initiallyManual = false
            scenario.onActivity {
                val raw = ViewModelProvider(it)[RawCameraViewModel::class.java]
                initiallyManual = raw.controls.manualExposure
            }
            clickInSettings("RAW 수동 노출")
            awaitRaw(scenario) { it.controls.manualExposure != initiallyManual && it.canCapture }
            clickInSettings("완료", forward = false)
            await { node("RAW 초점·Pro") == null }
            scenario.recreate()
            await { node("현재 모드 RAW") != null }
            awaitRaw(scenario) { it.controls.manualExposure != initiallyManual && it.canCapture }
            click("보정 설정")
            await { node("촬영 설정")?.isVisibleToUser == true }
            clickInSettings("초점·Pro")
            await { node("RAW 초점·Pro")?.isVisibleToUser == true }
            clickInSettings("RAW 수동 노출")
            awaitRaw(scenario) { it.controls.manualExposure == initiallyManual && it.canCapture }
            clickInSettings("완료", forward = false)
            await { node("RAW 초점·Pro") == null }
            click("AUTO 모드")
            await { node("현재 모드 AUTO") != null }
            click("RAW 모드")
            await { node("현재 모드 RAW") != null }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { node("현재 모드 AUTO") != null }
            click("RAW AUTO 모드")
            await { node("현재 모드 RAW AUTO") != null }
            awaitNatural(scenario) { it.canCapture && !it.controls.manualExposure }
            var initialHdr = true
            var initialStrength = .35f
            scenario.onActivity {
                val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                initialHdr = natural.hdrEnabled
                initialStrength = natural.shadowStrength
            }
            try {
                click("보정 설정")
                await { node("촬영 설정")?.isVisibleToUser == true }
                assertTrue(node("RAW 다중 프레임 합성") == null)
                assertTrue(node("초점·Pro") == null)
                clickInSettings("RAW AUTO 다중 노출 HDR")
                awaitNatural(scenario) { it.hdrEnabled != initialHdr }
                clickInSettings("더 밝게")
                clickInSettings("완료", forward = false)
                await { node("촬영 설정") == null }
                scenario.recreate()
                await { node("현재 모드 RAW AUTO") != null }
                awaitNatural(scenario) {
                    it.canCapture && it.shadowStrength == .6f && it.hdrEnabled != initialHdr
                }
                click("보정 설정")
                await { node("촬영 설정")?.isVisibleToUser == true }
                clickInSettings("RAW AUTO 다중 노출 HDR")
                awaitNatural(scenario) { it.hdrEnabled == initialHdr }
                scenario.onActivity {
                    val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                    natural.updateShadowStrength(initialStrength)
                }
                clickInSettings("완료", forward = false)
                await { node("촬영 설정") == null }
                click("나이트 모드")
                await { node("현재 모드 나이트") != null && node("사진 촬영") != null }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await { node("현재 모드 AUTO") != null }
                assertNoDiagnostics()
            } finally {
                // Test toggles are persisted; restore them even when a UI assertion fails.
                if (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                    scenario.onActivity {
                        val natural = ViewModelProvider(
                            it
                        )["rawAuto", RawCameraViewModel::class.java]
                        natural.setHdr(initialHdr)
                        natural.updateShadowStrength(initialStrength)
                    }
                }
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }

    @Test fun rawAutoDailyToolsPersistAndSampleTheActualPreview() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitNatural(scenario) { it.canCapture || it.unsupported }
            var unsupported = false
            var originalGrid = false
            var originalLevel = false
            var originalHistogram = false
            scenario.onActivity {
                val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                unsupported = natural.unsupported
                originalGrid = natural.gridEnabled
                originalLevel = natural.levelEnabled
                originalHistogram = natural.histogramEnabled
            }
            if (unsupported) return
            try {
                // Start from a known state, then exercise the actual user-facing switches.
                scenario.onActivity {
                    val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                    natural.setGrid(false)
                    natural.setLevel(false)
                    natural.setHistogram(false)
                }
                click("보정 설정")
                await { node("촬영 설정")?.isVisibleToUser == true }
                clickInSettings("격자")
                awaitNatural(scenario) { it.gridEnabled }
                clickInSettings("수평계")
                awaitNatural(scenario) { it.levelEnabled }
                clickInSettings("히스토그램")
                awaitNatural(scenario) { it.histogramEnabled }
                clickInSettings("완료", forward = false)
                await { node("촬영 설정") == null }
                await { node("프리뷰 밝기 히스토그램")?.isVisibleToUser == true }
                scenario.recreate()
                awaitNatural(scenario) {
                    it.canCapture && it.gridEnabled && it.levelEnabled && it.histogramEnabled
                }
                // This semantic exists only after TextureView sampling produces nonempty bins.
                await { node("프리뷰 밝기 히스토그램")?.isVisibleToUser == true }
                click("보정 설정")
                await { node("촬영 설정")?.isVisibleToUser == true }
                clickInSettings("히스토그램")
                awaitNatural(scenario) { !it.histogramEnabled }
                clickInSettings("완료", forward = false)
                await {
                    node("촬영 설정") == null && node("프리뷰 밝기 히스토그램") == null &&
                        node("히스토그램 준비 중") == null
                }
            } finally {
                scenario.onActivity {
                    val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                    natural.setGrid(originalGrid)
                    natural.setLevel(originalLevel)
                    natural.setHistogram(originalHistogram)
                }
            }
        }
    }

    @Test fun magnifiedRawPreviewStaysInsideNativeClipInPortraitAndLandscape() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitNatural(scenario) { it.canCapture || it.unsupported }
            var unsupported = false
            var initialOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            var initialAspect = PhotonAspectRatio.ORIGINAL
            var initialZoom = 1f
            var minimumZoom = 1f
            scenario.onActivity {
                val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                unsupported = natural.unsupported
                initialOrientation = it.requestedOrientation
                initialAspect = natural.aspectRatio
                initialZoom = natural.zoomRatio
                minimumZoom = natural.minimumZoomRatio
            }
            if (unsupported) return
            try {
                for (orientation in listOf(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                )) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                        Configuration.ORIENTATION_PORTRAIT
                    } else {
                        Configuration.ORIENTATION_LANDSCAPE
                    }
                    await {
                        var matches = false
                        scenario.onActivity {
                            matches = it.resources.configuration.orientation == expected
                        }
                        matches && node("현재 모드 RAW AUTO") != null
                    }
                    awaitNatural(scenario) { it.canCapture }
                    // Cover each framing and lens transition without a full cross-product.
                    val cases = if (expected == Configuration.ORIENTATION_PORTRAIT) {
                        listOf(
                            PhotonAspectRatio.WIDE to minimumZoom,
                            PhotonAspectRatio.CINEMA to 1.4f,
                            PhotonAspectRatio.ORIGINAL to 8f
                        )
                    } else {
                        listOf(
                            PhotonAspectRatio.WIDE to 8f,
                            PhotonAspectRatio.CINEMA to 8f,
                            PhotonAspectRatio.ORIGINAL to 8f
                        )
                    }
                    for ((aspect, zoom) in cases) {
                        scenario.onActivity {
                            val natural = ViewModelProvider(
                                it
                            )["rawAuto", RawCameraViewModel::class.java]
                            natural.setAspectRatio(aspect)
                            natural.setZoom(zoom, requireNotNull(texture(it.window.decorView)))
                        }
                        awaitNatural(scenario) {
                            it.canCapture && !it.switching && it.aspectRatio == aspect &&
                                kotlin.math.abs(it.zoomRatio - zoom) < .01f
                        }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        assertRawPreviewClipped(scenario)
                    }
                }
            } finally {
                scenario.onActivity {
                    val natural = ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java]
                    natural.setAspectRatio(initialAspect)
                    texture(it.window.decorView)?.let { view -> natural.setZoom(initialZoom, view) }
                    it.requestedOrientation = initialOrientation
                }
            }
        }
    }

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                texture(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun assertRawPreviewClipped(scenario: ActivityScenario<MainActivity>) {
        val header = Rect().also(requireNotNull(node("현재 모드 RAW AUTO"))::getBoundsInScreen)
        val shutter = Rect().also(requireNotNull(node("사진 촬영"))::getBoundsInScreen)
        scenario.onActivity { activity ->
            val preview = requireNotNull(texture(activity.window.decorView))
            val host = preview.parent as? FrameLayout
                ?: error("RAW preview needs a native clipping host")
            val original = preview.getTransform(Matrix())
            try {
                // Model the regression without sampling pixels or submitting any camera request.
                preview.setTransform(
                    Matrix().apply {
                        setScale(8f, 8f, preview.width / 2f, preview.height / 2f)
                    }
                )
                assertTrue("Native host must clip zoomed children", host.clipChildren)
                assertTrue("Native host must clip padding", host.clipToPadding)
                assertEquals(Rect(0, 0, host.width, host.height), host.clipBounds)
                assertEquals(Rect(0, 0, preview.width, preview.height), preview.clipBounds)
                assertTrue(host.width > 0 && host.height > 0)
                val position = IntArray(2).also(host::getLocationOnScreen)
                val bounds = Rect(
                    position[0],
                    position[1],
                    position[0] + host.width,
                    position[1] + host.height
                )
                assertTrue("Preview must remain below the header", bounds.top >= header.bottom)
                assertFalse("Preview overlaps shutter", Rect.intersects(bounds, shutter))
                val portrait = activity.resources.configuration.orientation ==
                    Configuration.ORIENTATION_PORTRAIT
                if (portrait) {
                    assertTrue("Portrait preview ends above shutter", bounds.bottom <= shutter.top)
                }
            } finally {
                preview.setTransform(original)
            }
        }
    }

    @Test fun missingPermissionShowsOnlyPermissionActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (state in CameraPermissionUiState.entries.filter {
                it !=
                    CameraPermissionUiState.GRANTED
            }) {
                var requested = false
                var settings = false
                scenario.onActivity { activity ->
                    activity.setContent {
                        Box(
                            Modifier.semantics {
                                contentDescription =
                                    "permission-state-${state.name}"
                            }
                        ) {
                            LunaCamApp(state, { requested = true }, { settings = true }, {})
                        }
                    }
                }
                // The first two states share a label; wait for the new composition.
                await { node("permission-state-${state.name}") != null }
                val label = if (state == CameraPermissionUiState.SETTINGS_REQUIRED) {
                    "설정에서 카메라 권한 허용"
                } else {
                    "카메라 권한 허용"
                }
                click(label)
                await {
                    var handled = false
                    scenario.onActivity {
                        handled =
                            if (state ==
                                CameraPermissionUiState.SETTINGS_REQUIRED
                            ) {
                                settings
                            } else {
                                requested
                            }
                    }
                    handled
                }
                assertTrue(node("사진 촬영") == null)
                assertNoDiagnostics()
            }
        }
    }

    private fun awaitRaw(
        scenario: ActivityScenario<MainActivity>,
        check: (RawCameraViewModel) -> Boolean
    ) = await {
        var matched = false
        scenario.onActivity {
            matched = check(ViewModelProvider(it)[RawCameraViewModel::class.java])
        }
        matched
    }

    private fun awaitNatural(
        scenario: ActivityScenario<MainActivity>,
        check: (RawCameraViewModel) -> Boolean
    ) = await {
        var matched = false
        scenario.onActivity {
            matched = check(ViewModelProvider(it)["rawAuto", RawCameraViewModel::class.java])
        }
        matched
    }

    private fun assertNoDiagnostics() {
        assertFalse(node("AUTO 촬영") != null)
        assertFalse(node("PHOTON 진단") != null)
        assertFalse(node("프리뷰 열기") != null)
    }

    private fun click(label: String) {
        await {
            var candidate = node(label)
            repeat(4) { if (candidate?.isClickable != true) candidate = candidate?.parent }
            val clicked = candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            clicked
        }
    }

    private fun clickInSettings(label: String, forward: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(7) { attempt ->
            // The sheet window first exposes only its dismiss scrim during entry animation.
            // Wait for real content before interpreting a missing target as needing a scroll.
            await { settingsContentReady(label) }
            var candidate = node(label)
            // Compose may expose a text child separately from the clickable FilterChip ancestor.
            repeat(8) { if (candidate?.isClickable != true) candidate = candidate?.parent }
            if (candidate?.isClickable == true && candidate?.isVisibleToUser == true &&
                candidate?.isEnabled == true &&
                candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            ) {
                instrumentation.uiAutomation.clearCache()
                return
            }
            instrumentation.uiAutomation.clearCache()
            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            check(root.packageName?.toString() == instrumentation.targetContext.packageName) {
                "Refusing to scroll outside the application under test"
            }
            val scrollNodes = mutableListOf<AccessibilityNodeInfo>()
            val visibleLabels = mutableListOf<String>()
            fun collectScrollers(current: AccessibilityNodeInfo) {
                if (current.isScrollable && scrollNodes.size < 16) scrollNodes += current
                if (current.isVisibleToUser && visibleLabels.size < 32) {
                    (current.text ?: current.contentDescription)?.toString()?.take(100)?.let {
                        visibleLabels += it
                    }
                }
                for (index in 0 until current.childCount) {
                    current.getChild(index)?.let(::collectScrollers)
                }
            }
            collectScrollers(root)
            fun describe(current: AccessibilityNodeInfo?): String {
                if (current == null) return "missing"
                val bounds = Rect().also(current::getBoundsInScreen)
                return "${current.className}: visible=${current.isVisibleToUser}, " +
                    "enabled=${current.isEnabled}, clickable=${current.isClickable}, " +
                    "bounds=$bounds, actions=${current.actionList.map { it.id }}"
            }
            fun diagnostic(): String = "label=$label, attempt=$attempt, forward=$forward, " +
                "target=${describe(node(label))}, clickable=${describe(candidate)}, " +
                "scrollers=${scrollNodes.joinToString("; ") { describe(it) }}, " +
                "visibleLabels=$visibleLabels"
            check(attempt < 6) {
                "RAW settings control not visible after six scrolls: ${diagnostic()}"
            }
            fun isVertical(current: AccessibilityNodeInfo): Boolean {
                val className = current.className?.toString().orEmpty()
                if (className == "android.widget.HorizontalScrollView") return false
                val hasVerticalAction = current.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                }
                val hasHorizontalAction = current.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id ||
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                }
                return current.isVisibleToUser &&
                    (
                        className == "android.widget.ScrollView" ||
                            (hasVerticalAction && !hasHorizontalAction)
                        )
            }
            // Prefer the actual settings ScrollView over descendants. Some Compose horizontal
            // rows also advertise directional scroll actions, so action presence alone is unsafe.
            val scroller = scrollNodes.filter(::isVertical).minByOrNull {
                if (it.className?.toString() == "android.widget.ScrollView") 0 else 1
            }
            requireNotNull(scroller) { "RAW settings scroller missing: ${diagnostic()}" }
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (!scroller.performAction(action)) {
                // Compose can advertise a scroll action yet reject it while its nested scroll
                // state is settling. A bounded real gesture exercises the same viewport instead.
                scrollSettingsGesture(scroller, forward)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
            instrumentation.uiAutomation.clearCache()
        }
    }

    private fun settingsContentReady(label: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != instrumentation.targetContext.packageName) return false
        fun ready(current: AccessibilityNodeInfo): Boolean {
            val bounds = Rect().also(current::getBoundsInScreen)
            if (current.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
                if (current.text?.toString() == label ||
                    current.contentDescription?.toString() == label
                ) {
                    return true
                }
                if (current.isScrollable && bounds.height() > 100 &&
                    current.className?.toString() != "android.widget.HorizontalScrollView"
                ) {
                    return true
                }
            }
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { if (ready(it)) return true }
            }
            return false
        }
        return ready(root)
    }

    private fun scrollSettingsGesture(scroller: AccessibilityNodeInfo, forward: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = Rect().also(scroller::getBoundsInScreen)
        check(bounds.width() > 20 && bounds.height() > 100) { "Settings viewport is too small" }
        val x = bounds.exactCenterX()
        val upper = bounds.top + bounds.height() * .2f
        val lower = bounds.top + bounds.height() * .8f
        val start = if (forward) lower else upper
        val end = if (forward) upper else lower
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(automation.injectInputEvent(event, true)) { "Settings gesture was rejected" }
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start)
        var ended = false
        try {
            // Twelve moves over at least 600 ms avoid accidental flings or horizontal chip taps.
            for (step in 1..12) {
                SystemClock.sleep(50)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 12f)
            }
            send(MotionEvent.ACTION_UP, end)
            ended = true
        } finally {
            if (!ended) send(MotionEvent.ACTION_CANCEL, end)
            automation.clearCache()
        }
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Scroll semantics can stay cached even after Compose has visibly moved the controls.
        // Always inspect the current tree, including the generic navigation helper's lookups.
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        fun find(current: AccessibilityNodeInfo, descriptionOnly: Boolean): AccessibilityNodeInfo? {
            val matches = if (descriptionOnly) {
                current.contentDescription?.toString() == label
            } else {
                current.text?.toString() == label
            }
            if (matches) return current
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let {
                    find(it, descriptionOnly)?.let { match -> return match }
                }
            }
            return null
        }
        // A switch can share its spoken label with a preceding non-clickable Text sibling.
        // Prefer its explicit content description before falling back to visible text.
        return find(root, true) ?: find(root, false)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Navigation did not settle")
    }
}
