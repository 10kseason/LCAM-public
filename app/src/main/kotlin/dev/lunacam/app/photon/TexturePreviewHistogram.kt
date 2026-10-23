package dev.lunacam.app.photon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Preview RGB brightness only; never a RAW/JPEG exposure prediction or a persisted image. */
@Composable
fun TexturePreviewHistogram(view: TextureView, enabled: Boolean, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val inspection = LocalInspectionMode.current
    var histogram by remember(view) { mutableStateOf<PhotonHistogram?>(null) }
    DisposableEffect(view, owner, enabled, inspection) {
        val handler = Handler(Looper.getMainLooper())
        var active = false
        var sampler: TextureHistogramSampler? = null
        val sample = object : Runnable {
            override fun run() {
                if (!active) return
                val sized = view.width > 0 && view.height > 0
                val available = view.isAvailable && view.isShown && sized
                histogram = if (available) {
                    try {
                        val current = sampler ?: TextureHistogramSampler().also { sampler = it }
                        current.sample(view)
                    } catch (_: RuntimeException) {
                        null
                    }
                } else {
                    null
                }
                if (active) handler.postDelayed(this, 250)
            }
        }
        fun stop() {
            active = false
            handler.removeCallbacks(sample)
            histogram = null
            sampler?.close()
            sampler = null
        }
        fun start() {
            if (active || !enabled || inspection) return
            active = true
            handler.post(sample)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            stop()
            owner.lifecycle.removeObserver(observer)
        }
    }
    if (enabled) PhotonHistogramOverlay(histogram, unavailable = false, modifier = modifier)
}

/** Owns only bounded, transient pixels. Call sample and close on the UI thread. */
internal class TextureHistogramSampler : AutoCloseable {
    private val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    private val displayed = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(displayed)
    private val transform = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(96 * 96)

    fun sample(view: TextureView): PhotonHistogram? {
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        // A failed copy leaves its destination unchanged: clear it before every attempt.
        source.eraseColor(Color.TRANSPARENT)
        view.getBitmap(source)
        displayed.eraseColor(Color.TRANSPARENT)
        // AOSP Readback::copyLayerInto calls DrawLayer with useLayerTransform=false.
        // getBitmap contains the SurfaceTexture orientation, but omits setTransform's zoom,
        // aspect fill and display rotation. Apply that matrix once in the 96px coordinate space.
        // https://android.googlesource.com/platform/frameworks/base/+/HEAD/libs/hwui/Readback.cpp
        view.getTransform(transform)
        transform.preScale(view.width / 96f, view.height / 96f)
        transform.postScale(96f / view.width, 96f / view.height)
        canvas.drawBitmap(source, transform, paint)
        displayed.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        return textureRgbHistogram(pixels)
    }

    override fun close() {
        source.recycle()
        displayed.recycle()
        pixels.fill(0)
    }
}

/** Fixed-point display luma, ignoring transparent pixels from failed/partial copies. */
internal fun textureRgbHistogram(pixels: IntArray): PhotonHistogram? {
    val bins = IntArray(64)
    var samples = 0
    val stride = ((pixels.size + 19_199L) / 19_200L).toInt().coerceAtLeast(1)
    for (index in pixels.indices step stride) {
        val color = pixels[index]
        if (color ushr 24 != 255) continue
        val red = color ushr 16 and 255
        val green = color ushr 8 and 255
        val blue = color and 255
        bins[((77 * red + 150 * green + 29 * blue + 128) shr 8) / 4]++
        samples++
    }
    return if (samples == 0) null else PhotonHistogram(bins.toList(), samples)
}
