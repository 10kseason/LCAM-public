package dev.lunacam.domain.preview

/** Continuous 2D point used by every preview coordinate mapping. */
data class PointD(val x: Double, val y: Double)

/** Framework-free integer size. The domain never accepts `android.util.Size`. */
data class IntSize(val width: Int, val height: Int) {
    val isPositive: Boolean
        get() = width > 0 && height > 0

    val area: Long
        get() = width.toLong() * height.toLong()

    /** Long-edge over short-edge ratio, orientation independent. Zero when degenerate. */
    val normalizedAspectRatio: Double
        get() {
            if (!isPositive) return 0.0
            val longEdge = maxOf(width, height).toDouble()
            val shortEdge = minOf(width, height).toDouble()
            return longEdge / shortEdge
        }
}

/** Framework-free integer rectangle. `right` and `bottom` are exclusive. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    val isPositive: Boolean
        get() = width > 0 && height > 0

    val size: IntSize
        get() = IntSize(width, height)

    fun intersect(other: IntRect): IntRect? {
        val intersection =
            IntRect(
                left = maxOf(left, other.left),
                top = maxOf(top, other.top),
                right = minOf(right, other.right),
                bottom = minOf(bottom, other.bottom)
            )
        return if (intersection.isPositive) intersection else null
    }

    companion object {
        fun ofSize(size: IntSize): IntRect = IntRect(0, 0, size.width, size.height)
    }
}
