package dev.lunacam.app.photon

import java.nio.ByteBuffer
import kotlin.math.ceil

/** Preview Y code values, not RAW exposure or a prediction of the processed JPEG. */
data class PhotonHistogram(val bins: List<Int>, val samples: Int)

fun previewHistogram(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    crop: PhotonCrop
): PhotonHistogram? {
    if (rowStride <= 0 || pixelStride <= 0 || crop.left < 0 || crop.top < 0 ||
        crop.width <= 0 || crop.height <= 0
    ) {
        return null
    }
    val lastX = crop.left.toLong() + crop.width - 1
    val lastY = crop.top.toLong() + crop.height - 1
    if (lastX * pixelStride >= rowStride) return null
    val offset = buffer.position().toLong()
    if (offset + lastY * rowStride + lastX * pixelStride >= buffer.limit()) return null
    // Bound CPU work even if a device ignores the requested small analysis resolution.
    val stepX = ceil(crop.width / 160.0).toInt().coerceAtLeast(1)
    val stepY = ceil(crop.height / 120.0).toInt().coerceAtLeast(1)
    val bins = IntArray(64)
    var samples = 0
    for (y in 0 until crop.height step stepY) {
        for (x in 0 until crop.width step stepX) {
            val index = offset + (crop.top.toLong() + y) * rowStride +
                (crop.left.toLong() + x) * pixelStride
            bins[(buffer.get(index.toInt()).toInt() and 255) / 4]++
            samples++
        }
    }
    return PhotonHistogram(bins.toList(), samples)
}
