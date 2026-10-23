package dev.lunacam.app.photon

fun pinchZoomRatio(current: Float, factor: Float, minimum: Float, maximum: Float): Float {
    if (!current.isFinite() || !minimum.isFinite() || !maximum.isFinite() ||
        minimum <= 0f || maximum < minimum
    ) {
        return current
    }
    if (!factor.isFinite() || factor <= 0f) return current.coerceIn(minimum, maximum)
    return (current.toDouble() * factor).coerceIn(minimum.toDouble(), maximum.toDouble()).toFloat()
}

/** Unknown is deliberately different from a successful negative capability probe. */
fun extensionSupport(available: Boolean?): String = when (available) {
    true -> "supported"
    false -> "unsupported"
    null -> "unknown"
}

fun captureRotation(orientation: Int): Int? = when (orientation) {
    in 45..134 -> 3 // Surface.ROTATION_270
    in 135..224 -> 2
    in 225..314 -> 1
    in 0..44, in 315..359 -> 0
    else -> null
}

/** FIT excludes letterbox bars; FILL has active image throughout the view. Rotation is degrees. */
fun isPreviewTap(
    viewWidth: Int,
    viewHeight: Int,
    width: Int,
    height: Int,
    rotation: Int,
    x: Float,
    y: Float,
    fillCenter: Boolean = false
): Boolean {
    if (viewWidth <= 0 || viewHeight <= 0 || width <= 0 || height <= 0) return false
    if (!x.isFinite() || !y.isFinite()) return false
    if (fillCenter) return x >= 0 && x < viewWidth && y >= 0 && y < viewHeight
    val displayWidth = if (rotation % 180 == 0) width else height
    val displayHeight = if (rotation % 180 == 0) height else width
    val scale = minOf(viewWidth.toFloat() / displayWidth, viewHeight.toFloat() / displayHeight)
    val left = (viewWidth - displayWidth * scale) / 2f
    val top = (viewHeight - displayHeight * scale) / 2f
    return x >= left && x < viewWidth - left && y >= top && y < viewHeight - top
}
