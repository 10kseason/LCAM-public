package dev.lunacam.app.photon.raw

import kotlin.math.max

/**
 * TextureView has already applied the camera's sensor-orientation/facing transform. Correct only
 * its default nonuniform stretch and display rotation. RAW/DNG orientation is a separate path.
 * Platform contract: developer.android.com/media/camera/camera2/camera-preview#textureview
 */
internal object RawPreviewTransform {
    fun matrixValues(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int
    ): FloatArray {
        require(viewWidth > 0 && viewHeight > 0 && bufferWidth > 0 && bufferHeight > 0)
        require(sensorOrientationDegrees in setOf(0, 90, 180, 270))
        require(displayRotationDegrees in setOf(0, 90, 180, 270))
        // The input to setTransform is already upright in the display's natural orientation.
        val sensorSwapsAxes = sensorOrientationDegrees % 180 != 0
        val naturalWidth = if (sensorSwapsAxes) bufferHeight.toFloat() else bufferWidth.toFloat()
        val naturalHeight = if (sensorSwapsAxes) bufferWidth.toFloat() else bufferHeight.toFloat()
        val displaySwapsAxes = displayRotationDegrees % 180 != 0
        val rotatedWidth = if (displaySwapsAxes) naturalHeight else naturalWidth
        val rotatedHeight = if (displaySwapsAxes) naturalWidth else naturalHeight
        val uniformScale = max(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val scaleX = naturalWidth / viewWidth * uniformScale
        val scaleY = naturalHeight / viewHeight * uniformScale
        val cosine = when (displayRotationDegrees) {
            0 -> 1f
            180 -> -1f
            else -> 0f
        }
        val sine = when (displayRotationDegrees) {
            90 -> -1f
            270 -> 1f
            else -> 0f
        }
        val a = cosine * scaleX
        val b = -sine * scaleY
        val c = sine * scaleX
        val d = cosine * scaleY
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        return floatArrayOf(
            a, b, centerX - a * centerX - b * centerY,
            c, d, centerY - c * centerX - d * centerY,
            0f, 0f, 1f
        )
    }
}
