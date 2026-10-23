package dev.lunacam.domain.preview

import kotlin.math.abs

/** Which fallback rung of DESIGN.md section 8.5 a preview size came from. */
enum class PreviewSizeTier {
    FULL_HD,
    HD,
    SMALLEST
}

data class PreviewSizeSelection(val size: IntSize, val tier: PreviewSizeTier)

/**
 * Chooses the PRIVATE preview size.
 *
 * Order follows DESIGN.md section 8.5: at most 1920x1080, then at most 1280x720, then the
 * smallest acceptable candidate. Every rung below the first is reachable only by descending after
 * a failed session configuration, which is why [nextTier] exists and [select] takes a start rung.
 *
 * Inside a rung the candidate whose long/short edge ratio is closest to [targetAspectRatio] wins.
 * That target is the **sensor active array** ratio, not the screen's: a viewfinder that framed the
 * screen instead of the sensor would show a different scene from the one RAW records. On this
 * device the screen ratio would have selected a 2.33:1 readout from a 4:3 sensor and thrown away
 * 43 percent of the vertical field of view. The letterboxing that a 4:3 preview needs on a tall
 * screen is handled by `PreviewTransformFactory`, which fits rather than crops.
 *
 * The two axes are never scaled independently, which is how FR-PRV-001 is satisfied.
 */
object PreviewSizeSelector {
    private val FULL_HD_BOUND = IntSize(1920, 1080)
    private val HD_BOUND = IntSize(1280, 720)

    /** Long-edge over short-edge ratio, the form [select] compares against. */
    fun normalizedAspectRatioOf(size: IntSize): Double = size.normalizedAspectRatio

    fun normalizedAspectRatioOf(rect: IntRect): Double = rect.size.normalizedAspectRatio

    fun select(
        candidates: List<IntSize>,
        targetAspectRatio: Double,
        startTier: PreviewSizeTier = PreviewSizeTier.FULL_HD
    ): PreviewSizeSelection? {
        val usable = candidates.filter { it.isPositive }
        if (usable.isEmpty()) return null

        var tier: PreviewSizeTier? = startTier
        while (tier != null) {
            val bound = boundFor(tier)
            if (bound == null) {
                val smallest = usable.minWith(compareBy({ it.area }, { it.width }, { it.height }))
                return PreviewSizeSelection(smallest, PreviewSizeTier.SMALLEST)
            }
            selectWithin(usable, targetAspectRatio, bound)?.let {
                return PreviewSizeSelection(it, tier)
            }
            tier = nextTier(tier)
        }
        return null
    }

    /** The next lower rung to retry with after `onConfigureFailed`, or null when exhausted. */
    fun nextTier(tier: PreviewSizeTier): PreviewSizeTier? = when (tier) {
        PreviewSizeTier.FULL_HD -> PreviewSizeTier.HD
        PreviewSizeTier.HD -> PreviewSizeTier.SMALLEST
        PreviewSizeTier.SMALLEST -> null
    }

    private fun boundFor(tier: PreviewSizeTier): IntSize? = when (tier) {
        PreviewSizeTier.FULL_HD -> FULL_HD_BOUND
        PreviewSizeTier.HD -> HD_BOUND
        PreviewSizeTier.SMALLEST -> null
    }

    private fun selectWithin(
        candidates: List<IntSize>,
        targetAspectRatio: Double,
        bound: IntSize
    ): IntSize? {
        val boundLongEdge = maxOf(bound.width, bound.height)
        val boundShortEdge = minOf(bound.width, bound.height)
        val fitting =
            candidates.filter {
                maxOf(it.width, it.height) <= boundLongEdge &&
                    minOf(it.width, it.height) <= boundShortEdge
            }
        if (fitting.isEmpty()) return null

        if (targetAspectRatio <= 0.0 || !targetAspectRatio.isFinite()) {
            return fitting.maxWith(compareBy({ it.area }, { it.width }, { it.height }))
        }
        return fitting.minWith(
            compareBy(
                { abs(it.normalizedAspectRatio - targetAspectRatio) },
                { -it.area },
                { it.width }
            )
        )
    }
}
