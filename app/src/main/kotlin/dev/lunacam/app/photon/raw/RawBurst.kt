package dev.lunacam.app.photon.raw

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import dev.lunacam.app.photon.PhotonAspectRatio
import java.nio.ByteBuffer

data class RawLens(
    val id: String,
    val label: String,
    val rawSize: Size,
    val cameraId: String = id,
    val physicalId: String? = null,
    val opticalRatio: Float? = null
)

/** Packed, native-endian unsigned 16-bit Bayer samples, without row padding. */
data class RawFrame(
    val pixels: ByteBuffer,
    val width: Int,
    val height: Int,
    val result: TotalCaptureResult,
    val physicalId: String? = null
)

data class RawBurst(
    val frames: List<RawFrame>,
    val characteristics: CameraCharacteristics,
    val orientationDegrees: Int,
    val elapsedMs: Long,
    val cameraId: String? = null,
    val opticalRatio: Float? = null,
    val nightTiming: NightCaptureTiming? = null,
    val requestedControls: RawControls? = null,
    val digitalZoom: Float = 1f,
    val hdrRequested: Boolean = false,
    val hdrFallbackReason: String? = null,
    val previewRestored: Boolean = true,
    val aspectRatio: PhotonAspectRatio = PhotonAspectRatio.ORIGINAL,
    // Restore only the intentional default capture bias, never the user's EV adjustment.
    val compensationBiasEv: Float = 0f
)
