package dev.lunacam.app.photon

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lunacam.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic SurfaceTexture only. No camera capture, screenshot or stored pixel artifact. */
@RunWith(AndroidJUnit4::class)
class TexturePreviewHistogramDeviceTest {
    @Test fun textureCopyIncludesCurrentZoomInsteadOfCountingOffscreenPixels() {
        val sampler = TextureHistogramSampler()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: TextureView
                scenario.onActivity { activity ->
                    view = TextureView(activity).apply { isOpaque = false }
                    activity.setContent {
                        AndroidView(factory = { view }, modifier = Modifier.size(160.dp))
                    }
                }
                await(scenario) { view.isAvailable && view.width > 0 && view.height > 0 }
                scenario.onActivity {
                    val canvas = requireNotNull(view.lockCanvas())
                    try {
                        canvas.drawColor(Color.BLACK)
                        canvas.drawRect(
                            canvas.width * .25f,
                            canvas.height * .25f,
                            canvas.width * .75f,
                            canvas.height * .75f,
                            Paint().apply { color = Color.WHITE }
                        )
                        canvas.drawRect(
                            canvas.width * .375f,
                            canvas.height * .375f,
                            canvas.width * .625f,
                            canvas.height * .625f,
                            Paint().apply { color = Color.RED }
                        )
                    } finally {
                        view.unlockCanvasAndPost(canvas)
                    }
                }
                await(scenario) {
                    val histogram = sampler.sample(view)
                    histogram != null && histogram.bins[0] > histogram.samples * .6 &&
                        histogram.bins[63] > histogram.samples * .15
                }
                scenario.onActivity {
                    view.setTransform(
                        Matrix().apply { setScale(2f, 2f, view.width / 2f, view.height / 2f) }
                    )
                }
                await(scenario) {
                    val histogram = sampler.sample(view)
                    histogram != null && histogram.bins[63] > histogram.samples * .65 &&
                        histogram.bins[63] < histogram.samples * .8 &&
                        histogram.bins[19] > histogram.samples * .2 &&
                        histogram.bins[19] < histogram.samples * .3
                }
                scenario.onActivity {
                    view.setTransform(
                        Matrix().apply { setScale(8f, 8f, view.width / 2f, view.height / 2f) }
                    )
                }
                await(scenario) {
                    val histogram = sampler.sample(view)
                    histogram != null && histogram.bins[19] > histogram.samples * .95
                }
                scenario.onActivity {
                    view.setTransform(Matrix())
                }
                await(scenario) {
                    val histogram = sampler.sample(view)
                    histogram != null && histogram.bins[0] > histogram.samples * .6
                }
            }
        } finally {
            sampler.close()
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { done = predicate() }
            if (!done) SystemClock.sleep(100)
        }
        assertTrue("Synthetic TextureView frame/transform did not settle", done)
    }
}
