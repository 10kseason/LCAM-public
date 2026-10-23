package dev.lunacam.domain.preview

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the forward and inverse preview matrices.
 *
 * FR-PRV-002 requires sensor orientation, display rotation, and front mirroring to be handled as
 * three separate inputs rather than one fused angle, so each is a distinct parameter here.
 *
 * A null result means "not ready yet" (zero-sized surface, pre-layout tap, degenerate mapping).
 * Per DESIGN.md section 9.1 that is never surfaced to the user as an error.
 */
object PreviewTransformFactory {
    fun create(
        activeArray: IntRect,
        bufferSize: IntSize,
        viewSize: IntSize,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        mirrored: Boolean
    ): PreviewTransform? {
        if (!activeArray.isPositive || !bufferSize.isPositive || !viewSize.isPositive) return null
        if (sensorOrientationDegrees % QUARTER_TURN_DEGREES != 0) return null
        if (displayRotationDegrees % QUARTER_TURN_DEGREES != 0) return null

        val sensorOrientation = normalizeDegrees(sensorOrientationDegrees)
        val displayRotation = normalizeDegrees(displayRotationDegrees)
        val relativeRotation =
            if (mirrored) {
                normalizeDegrees(sensorOrientation + displayRotation)
            } else {
                normalizeDegrees(sensorOrientation - displayRotation)
            }
        val quarterTurns = relativeRotation / QUARTER_TURN_DEGREES

        val bufferWidth = bufferSize.width.toDouble()
        val bufferHeight = bufferSize.height.toDouble()
        val sensorToBuffer =
            Matrix3x3.scale(bufferWidth / activeArray.width, bufferHeight / activeArray.height) *
                Matrix3x3.translate(-activeArray.left.toDouble(), -activeArray.top.toDouble())

        val bufferToUpright =
            Matrix3x3.rotateClockwiseQuarterTurns(quarterTurns, bufferWidth, bufferHeight)
        val uprightWidth = if (quarterTurns % 2 == 0) bufferWidth else bufferHeight
        val uprightHeight = if (quarterTurns % 2 == 0) bufferHeight else bufferWidth
        val mirror =
            if (mirrored) Matrix3x3.mirrorHorizontal(uprightWidth) else Matrix3x3.IDENTITY

        // Letterbox fit: one uniform scale for both axes, so the preview never stretches and the
        // whole sensor frame stays visible (FR-PRV-001 plus preview/capture framing parity).
        val viewWidth = viewSize.width.toDouble()
        val viewHeight = viewSize.height.toDouble()
        val scale = min(viewWidth / uprightWidth, viewHeight / uprightHeight)
        val offsetX = (viewWidth - uprightWidth * scale) / 2.0
        val offsetY = (viewHeight - uprightHeight * scale) / 2.0
        val uprightToView =
            Matrix3x3.translate(offsetX, offsetY) * Matrix3x3.scale(scale, scale)

        val sensorToView = uprightToView * mirror * bufferToUpright * sensorToBuffer
        val viewToSensor = sensorToView.invert() ?: return null
        val visibleSensorRect =
            visibleSensorRect(viewToSensor, viewSize, activeArray) ?: return null
        val displayedViewRect =
            IntRect(
                left = roundWithTolerance(offsetX),
                top = roundWithTolerance(offsetY),
                right = roundWithTolerance(offsetX + uprightWidth * scale),
                bottom = roundWithTolerance(offsetY + uprightHeight * scale)
            )
        if (!displayedViewRect.isPositive) return null

        return PreviewTransform(
            sensorToView = sensorToView,
            viewToSensor = viewToSensor,
            visibleSensorRect = visibleSensorRect,
            displayedViewRect = displayedViewRect,
            bufferSize = bufferSize,
            displayRotationDegrees = displayRotation,
            sensorOrientationDegrees = sensorOrientation,
            mirrored = mirrored
        )
    }

    /**
     * Rotations are whole quarter turns, so the mapped view rectangle stays axis aligned and two
     * opposite corners describe it completely.
     */
    private fun visibleSensorRect(
        viewToSensor: Matrix3x3,
        viewSize: IntSize,
        activeArray: IntRect
    ): IntRect? {
        val topLeft = viewToSensor.mapPoint(PointD(0.0, 0.0)) ?: return null
        val bottomRight =
            viewToSensor.mapPoint(PointD(viewSize.width.toDouble(), viewSize.height.toDouble()))
                ?: return null
        val mapped =
            IntRect(
                left = floorWithTolerance(minOf(topLeft.x, bottomRight.x)),
                top = floorWithTolerance(minOf(topLeft.y, bottomRight.y)),
                right = ceilWithTolerance(maxOf(topLeft.x, bottomRight.x)),
                bottom = ceilWithTolerance(maxOf(topLeft.y, bottomRight.y))
            )
        return mapped.intersect(activeArray)
    }

    /**
     * The matrix chain accumulates rounding noise, so a bound that is mathematically an exact
     * integer can arrive as 2700.0000000001. Snapping within a sub-pixel tolerance keeps the
     * visible rect from growing by one sensor pixel on every configuration.
     */
    private fun floorWithTolerance(value: Double): Int = floor(value + COORDINATE_TOLERANCE).toInt()

    private fun ceilWithTolerance(value: Double): Int = ceil(value - COORDINATE_TOLERANCE).toInt()

    private fun roundWithTolerance(value: Double): Int = if (value >= 0.0) {
        (value + COORDINATE_TOLERANCE).roundToInt()
    } else {
        (value - COORDINATE_TOLERANCE).roundToInt()
    }

    private fun normalizeDegrees(degrees: Int): Int {
        val wrapped = degrees % FULL_TURN_DEGREES
        return if (wrapped < 0) wrapped + FULL_TURN_DEGREES else wrapped
    }

    private const val QUARTER_TURN_DEGREES = 90
    private const val FULL_TURN_DEGREES = 360
    private const val COORDINATE_TOLERANCE = 1e-6
}
