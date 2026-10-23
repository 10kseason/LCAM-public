package dev.lunacam.app.photon

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.CameraExitDialog
import dev.lunacam.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotonExitGuardDeviceTest {
    @Test fun pendingWorkCannotExitAndCompletionDoesNotHideFailure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val unfinished = mutableStateOf(true)
            var exits = 0
            var cancels = 0
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CameraExitDialog(unfinished.value, { cancels++ }, { exits++ }, "저장 결과 확인")
                    }
                }
            }
            await { node("작업 처리 중…") != null }
            clickable("작업 처리 중…")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            scenario.onActivity { assertEquals(0, exits) }
            scenario.onActivity { unfinished.value = false }
            await { node("결과 확인 · 닫기") != null }
            scenario.onActivity { assertEquals(0, exits) }
            clickable("결과 확인 · 닫기")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await {
                var done = false
                scenario.onActivity { done = exits == 1 }
                done
            }
            clickable("계속 촬영")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await {
                var done = false
                scenario.onActivity { done = cancels == 1 }
                done
            }
        }
    }

    private fun clickable(label: String): AccessibilityNodeInfo? {
        var node = node(label)
        repeat(4) { if (node?.isClickable != true) node = node?.parent }
        return node
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return null
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.text?.toString() == label) return current
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let {
                    find(it)?.let { match -> return match }
                }
            }
            return null
        }
        return find(root)
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Exit dialog did not settle")
    }
}
