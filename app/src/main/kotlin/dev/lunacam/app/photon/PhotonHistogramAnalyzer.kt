package dev.lunacam.app.photon

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Frames stay on the analysis thread and are always returned to CameraX, including skips. */
class PhotonHistogramAnalyzer(
    private val aspect: () -> PhotonAspectRatio,
    private val onHistogram: (PhotonAspectRatio, PhotonHistogram?) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : ImageAnalysis.Analyzer {
    private var lastUpdate: Long? = null

    override fun analyze(image: ImageProxy) {
        try {
            val now = clock()
            if (lastUpdate?.let { now - it < 200 } == true) return
            lastUpdate = now
            val selected = aspect()
            val result = try {
                val rect = image.cropRect
                val crop = selected.centerCrop(rect.width(), rect.height())
                val plane = image.planes.firstOrNull()
                if (plane == null) {
                    null
                } else {
                    previewHistogram(
                        plane.buffer,
                        plane.rowStride,
                        plane.pixelStride,
                        crop.copy(left = crop.left + rect.left, top = crop.top + rect.top)
                    )
                }
            } catch (_: Exception) {
                null
            }
            onHistogram(selected, result)
        } finally {
            image.close()
        }
    }
}
