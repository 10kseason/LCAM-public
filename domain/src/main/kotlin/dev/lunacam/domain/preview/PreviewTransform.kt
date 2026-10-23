package dev.lunacam.domain.preview

/**
 * Single source of truth for the four preview coordinate spaces listed in DESIGN.md section 9.1:
 * view, display-rotated preview buffer, sensor active array, and crop region.
 *
 * The preview is letterboxed, not cropped: the whole sensor frame is fitted inside the view so the
 * viewfinder shows exactly what a RAW capture will record. [displayedViewRect] is the part of the
 * view the image actually occupies, and the caller must lay the preview surface out at that rect,
 * otherwise the framing on screen and the tap mapping would disagree.
 *
 * P2 has no zoom, so the crop region equals the active array and [visibleSensorRect] is the whole
 * active array.
 */
data class PreviewTransform(
    val sensorToView: Matrix3x3,
    val viewToSensor: Matrix3x3,
    val visibleSensorRect: IntRect,
    val displayedViewRect: IntRect,
    val bufferSize: IntSize,
    val displayRotationDegrees: Int,
    val sensorOrientationDegrees: Int,
    val mirrored: Boolean
) {
    /** Width over height of [displayedViewRect], for laying the preview surface out. */
    val displayAspectRatio: Double
        get() =
            if (displayedViewRect.height <= 0) {
                0.0
            } else {
                displayedViewRect.width.toDouble() / displayedViewRect.height
            }

    fun mapSensorToView(point: PointD): PointD? = sensorToView.mapPoint(point)

    fun mapViewToSensor(point: PointD): PointD? = viewToSensor.mapPoint(point)
}
