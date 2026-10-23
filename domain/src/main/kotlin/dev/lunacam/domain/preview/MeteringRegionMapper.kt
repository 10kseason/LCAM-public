package dev.lunacam.domain.preview

import kotlin.math.roundToInt

/** A metering rectangle in sensor active array coordinates with its Camera2 weight. */
data class MeteringRegion(val rect: IntRect, val weight: Int)

/**
 * Converts a tap in view coordinates into an AF/AE metering rectangle (FR-PRV-004).
 *
 * The rectangle is clamped inside the active array and keeps a minimum usable size
 * (FR-PRV-005). Taps outside the view, taps before layout, and degenerate transforms return null
 * so the caller silently ignores them per DESIGN.md section 9.1.
 */
object MeteringRegionMapper {
    /** Matches `MeteringRectangle.METERING_WEIGHT_MAX`, kept as a plain Int in the domain. */
    const val MAX_WEIGHT = 1000

    /** Region side length as a fraction of the shorter visible sensor edge. */
    const val DEFAULT_REGION_FRACTION = 0.15

    /** Lower bound in sensor pixels so the region stays meaningful on small crops. */
    const val MIN_REGION_PIXELS = 64

    fun map(
        transform: PreviewTransform,
        viewSize: IntSize,
        tap: PointD,
        activeArray: IntRect,
        regionFraction: Double = DEFAULT_REGION_FRACTION,
        weight: Int = MAX_WEIGHT
    ): MeteringRegion? {
        if (!viewSize.isPositive || !activeArray.isPositive) return null
        if (!tap.x.isFinite() || !tap.y.isFinite()) return null
        if (tap.x < 0.0 || tap.y < 0.0) return null
        if (tap.x > viewSize.width.toDouble() || tap.y > viewSize.height.toDouble()) return null

        // The preview is letterboxed, so a tap in the bar beside the image is not a tap on the
        // scene. Clamping it into the active array would silently focus on an edge the user never
        // touched, so it is ignored instead.
        val displayed = transform.displayedViewRect
        if (!displayed.isPositive) return null
        if (tap.x < displayed.left.toDouble() || tap.x > displayed.right.toDouble()) return null
        if (tap.y < displayed.top.toDouble() || tap.y > displayed.bottom.toDouble()) return null

        val visible = transform.visibleSensorRect
        if (!visible.isPositive) return null
        val sensorPoint = transform.mapViewToSensor(tap) ?: return null

        val shorterVisibleEdge = minOf(visible.width, visible.height).toDouble()
        val maxSide = minOf(activeArray.width, activeArray.height).toDouble()
        val side =
            (shorterVisibleEdge * regionFraction)
                .coerceAtLeast(MIN_REGION_PIXELS.toDouble())
                .coerceAtMost(maxSide)
        val half = side / 2.0

        val rect =
            clampInside(
                left = (sensorPoint.x - half).roundToInt(),
                top = (sensorPoint.y - half).roundToInt(),
                width = side.roundToInt().coerceAtLeast(1),
                height = side.roundToInt().coerceAtLeast(1),
                bounds = activeArray
            )
        return if (rect.isPositive) MeteringRegion(rect, weight) else null
    }

    /** Shifts the rectangle inside [bounds] instead of shrinking it, so the size stays stable. */
    private fun clampInside(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        bounds: IntRect
    ): IntRect {
        val clampedWidth = width.coerceAtMost(bounds.width)
        val clampedHeight = height.coerceAtMost(bounds.height)
        val clampedLeft = left.coerceIn(bounds.left, bounds.right - clampedWidth)
        val clampedTop = top.coerceIn(bounds.top, bounds.bottom - clampedHeight)
        return IntRect(
            left = clampedLeft,
            top = clampedTop,
            right = clampedLeft + clampedWidth,
            bottom = clampedTop + clampedHeight
        )
    }
}
