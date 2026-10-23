package dev.lunacam.app.photon

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.min

/** Visual aids only: place inside the exact preview bounds. Never intercepts preview gestures. */
@Composable
fun PhotonViewfinderAssist(
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var horizon by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context, owner, view, levelEnabled) {
        val manager = context.applicationContext.getSystemService(SensorManager::class.java)
        val sensor = if (levelEnabled) {
            manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            null
        }
        var registered = false
        var lastPublished = 0L
        var lastRotation = -1
        var filtered: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!registered || event.values.size < 3) return
                val display = view.display ?: run {
                    filtered = null
                    horizon = null
                    return
                }
                val rotation = display.rotation * 90
                if (rotation != lastRotation) {
                    filtered = null
                    lastPublished = 0L
                    lastRotation = rotation
                }
                val now = SystemClock.elapsedRealtime()
                if (lastPublished != 0L && now - lastPublished < 100L) return
                val measured = PhotonLevelPolicy.horizonDegrees(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    rotation
                )
                filtered = measured?.let {
                    PhotonLevelPolicy.smooth(filtered, it, now - lastPublished)
                }
                lastPublished = now
                horizon = filtered
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun stop() {
            if (registered) manager?.unregisterListener(listener)
            registered = false
            filtered = null
            lastPublished = 0L
            horizon = null
        }
        fun start() {
            if (registered || sensor == null || !levelEnabled) return
            registered = manager?.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_UI,
                Handler(Looper.getMainLooper())
            ) == true
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> stop()
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
    // No liveRegion or changing angle announcements: the level must not spam TalkBack.
    val accessibility = if (levelEnabled) {
        Modifier.semantics { contentDescription = "수평계" }
    } else {
        Modifier
    }
    Box(modifier.then(accessibility)) {
        Canvas(Modifier.fillMaxSize()) {
            if (gridEnabled) {
                val stroke = 1.dp.toPx()
                for (part in 1..2) {
                    val x = size.width * part / 3f
                    val y = size.height * part / 3f
                    drawLine(
                        Color.Black.copy(alpha = 0.35f),
                        Offset(x, 0f),
                        Offset(x, size.height),
                        strokeWidth = stroke * 2f
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.55f),
                        Offset(x, 0f),
                        Offset(x, size.height),
                        strokeWidth = stroke
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.35f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = stroke * 2f
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.55f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = stroke
                    )
                }
            }
            val angle = horizon
            if (levelEnabled && angle != null) {
                val half = min(48.dp.toPx(), size.width * 0.18f)
                val tick = 6.dp.toPx()
                val color = if (abs(angle) <= PhotonLevelPolicy.ALIGNED_DEGREES) {
                    Color(0xFF7CFFB0)
                } else {
                    Color.White
                }
                // Fixed ticks show the display horizontal; the moving line follows gravity.
                for (side in listOf(-1f, 1f)) {
                    val x = center.x + side * (half + 8.dp.toPx())
                    drawLine(
                        Color.Black.copy(alpha = 0.65f),
                        Offset(x, center.y - tick),
                        Offset(x, center.y + tick),
                        4.dp.toPx()
                    )
                    drawLine(
                        color,
                        Offset(x, center.y - tick),
                        Offset(x, center.y + tick),
                        2.dp.toPx()
                    )
                }
                // Canvas Y points down, so this angle is the screen horizon's clockwise angle.
                rotate(angle, center) {
                    val left = Offset(center.x - half, center.y)
                    val right = Offset(center.x + half, center.y)
                    drawLine(
                        Color.Black.copy(alpha = 0.65f),
                        left,
                        right,
                        5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(color, left, right, 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}
