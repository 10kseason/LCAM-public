package dev.lunacam.app.photon

data class PhotonCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Ratios describe the upright frame; crop coordinates remain in the encoded raster. */
enum class PhotonAspectRatio(
    val id: String,
    val label: String,
    private val longSide: Int,
    private val shortSide: Int
) {
    ORIGINAL("original", "4:3", 4, 3),
    WIDE("wide", "16:9", 16, 9),
    CINEMA("cinema", "21:9", 7, 3);

    fun previewRatio(landscape: Boolean): Float =
        if (landscape) longSide.toFloat() / shortSide else shortSide.toFloat() / longSide

    fun centerCrop(width: Int, height: Int): PhotonCrop {
        require(width > 0 && height > 0) { "InvalidCropDimensions" }
        if (this == ORIGINAL) return PhotonCrop(0, 0, width, height)
        val ratioWidth = if (width >= height) longSide else shortSide
        val ratioHeight = if (width >= height) shortSide else longSide
        val unit = minOf(width / ratioWidth, height / ratioHeight)
        require(unit > 0) { "ImageTooSmallForExactAspect" }
        val croppedWidth = unit * ratioWidth
        val croppedHeight = unit * ratioHeight
        return PhotonCrop(
            (width - croppedWidth) / 2,
            (height - croppedHeight) / 2,
            croppedWidth,
            croppedHeight
        )
    }

    companion object {
        fun fromPreference(value: String?): PhotonAspectRatio =
            entries.firstOrNull { it.id == value } ?: ORIGINAL
    }
}
