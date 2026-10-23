package dev.lunacam.app.photon.raw

import dev.lunacam.app.photon.PhotonAspectRatio
import kotlin.math.max
import kotlin.math.min

/** One physical sensor plus a centered crop; digital zoom never widens a sensor's field of view. */
internal object RawZoomPolicy {
    const val MIN_RATIO = 0.6f
    const val MAX_RATIO = 8f

    fun select(ratios: List<Float>, current: Int, requested: Float): RawZoomSelection {
        require(ratios.isNotEmpty() && current in ratios.indices)
        require(ratios.all { it.isFinite() && it > 0f && it <= MAX_RATIO })
        require(ratios.zipWithNext().all { (a, b) -> a < b })
        val minimum = max(MIN_RATIO, ratios.first())
        val target = (if (requested.isFinite()) requested else ratios[current])
            .coerceIn(minimum, MAX_RATIO)
        var selected = current
        // On zoom-out there is no dead band: retaining a narrower lens would require fake zoom-out.
        while (selected > 0 && ratios[selected] > target) selected--
        // A small upward dead band prevents repeated sensor switching at an optical boundary.
        while (selected < ratios.lastIndex &&
            target >= min(MAX_RATIO, ratios[selected + 1] * 1.04f)
        ) {
            selected++
        }
        return RawZoomSelection(selected, target, (target / ratios[selected]).coerceAtLeast(1f))
    }

    /** Coordinates belong to the source RAW raster (or sensor active array for preview requests). */
    fun crop(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        digitalRatio: Float,
        aspect: PhotonAspectRatio = PhotonAspectRatio.ORIGINAL
    ): RawZoomCrop {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
        require(digitalRatio.isFinite() && digitalRatio >= 1f)
        // Keep absolute origins and dimensions even so a later crop cannot shift Bayer phase.
        val alignedLeft = left + (left and 1)
        val alignedTop = top + (top and 1)
        val alignedRight = right - (right and 1)
        val alignedBottom = bottom - (bottom and 1)
        val fullWidth = alignedRight - alignedLeft
        val fullHeight = alignedBottom - alignedTop
        require(fullWidth >= 2 && fullHeight >= 2)
        var width = ((fullWidth / digitalRatio).toInt() / 2 * 2).coerceAtLeast(2)
        var height = ((fullHeight / digitalRatio).toInt() / 2 * 2).coerceAtLeast(2)
        if (aspect != PhotonAspectRatio.ORIGINAL) {
            // Crop in half-resolution Bayer cells: the exact aspect, origin and dimensions all
            // remain CFA aligned. Apply aspect after zoom, matching the center-filled preview.
            val framed = aspect.centerCrop(width / 2, height / 2)
            width = framed.width * 2
            height = framed.height * 2
        }
        val x = alignedLeft + ((fullWidth - width) / 4) * 2
        val y = alignedTop + ((fullHeight - height) / 4) * 2
        return RawZoomCrop(x, y, x + width, y + height)
    }
}

internal data class RawZoomSelection(
    val index: Int,
    val requestedRatio: Float,
    val digitalRatio: Float
)

internal data class RawZoomCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
