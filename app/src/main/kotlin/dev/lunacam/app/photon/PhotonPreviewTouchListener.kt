package dev.lunacam.app.photon

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** A two-finger gesture must never turn into a focus tap when the last finger lifts. */
class PhotonPreviewTouchListener(
    context: Context,
    onScale: (Float) -> Unit,
    onHold: (Float, Float) -> Unit = { _, _ -> },
    onTap: (Float, Float) -> Unit
) : View.OnTouchListener {
    private var multiTouch = false
    private var held = false
    private var cancelled = false
    private val scale = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onScale(detector.scaleFactor)
                return true
            }
        }
    ).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }
    private val tap = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (multiTouch || held || cancelled) return false
                onTap(event.x, event.y)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (multiTouch || held || cancelled) return
                held = true
                onHold(event.x, event.y)
            }
        }
    )

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false
            held = false
            cancelled = false
        }
        if (event.pointerCount > 1) multiTouch = true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelled = true
        scale.onTouchEvent(event)
        val tapped = tap.onTouchEvent(event)
        if (tapped && event.actionMasked == MotionEvent.ACTION_UP &&
            !multiTouch && !held && !cancelled
        ) {
            view.performClick()
        }
        return true
    }
}
