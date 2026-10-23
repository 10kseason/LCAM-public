package dev.lunacam.app.photon

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dev.lunacam.processing.nativebridge.NativeTextureGuard
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ProcessedPhoto(
    val uri: Uri?,
    val stats: TextureGuardStats,
    val elapsedMs: Long,
    val decodeMs: Long = 0,
    val filterMs: Long = 0,
    val saveMs: Long = 0
)

/** Preserves the source and publishes only complete output. */
class PhotonPhotoProcessor(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun process(original: Uri, outputName: String): ProcessedPhoto =
        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(original)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            require(bounds.outMimeType == "image/jpeg")
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            require(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) {
                "ImageTooLarge"
            }
            val metadata = resolver.openInputStream(original)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                check(orientation in 1..8) { "UnknownOrientation" }
                METADATA.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
            } ?: throw IOException("SourceUnavailable")
            coroutineContext.ensureActive()
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val bitmap = resolver.openInputStream(original)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
                ?: throw IOException("DecodeFailed")
            var candidate: Uri? = null
            var committed = false
            try {
                check(!bitmap.hasGainmap()) { "GainMapUnsupported" }
                coroutineContext.ensureActive()
                val decodedAt = SystemClock.elapsedRealtime()
                // Same filter math in optimized native code; no resizing or lower JPEG quality.
                val changed = NativeTextureGuard.process(bitmap)
                val filteredAt = SystemClock.elapsedRealtime()
                coroutineContext.ensureActive()
                val stats = TextureGuardStats(
                    (bitmap.width - 2).coerceAtLeast(0).toLong() *
                        (bitmap.height - 2).coerceAtLeast(0),
                    changed
                )
                if (stats.changedPixels == 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    return@withContext ProcessedPhoto(
                        null,
                        stats,
                        elapsed,
                        decodedAt - started,
                        filteredAt - decodedAt,
                        0
                    )
                }
                coroutineContext.ensureActive()
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, outputName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LUNA PHOTON")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                candidate = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("InsertFailed")
                resolver.openOutputStream(candidate, "w")?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, output)) {
                        "EncodeFailed"
                    }
                } ?: throw IOException("OutputUnavailable")
                resolver.openFileDescriptor(candidate, "rw")?.use { descriptor ->
                    val exif = ExifInterface(descriptor.fileDescriptor)
                    metadata.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                    exif.setAttribute(
                        ExifInterface.TAG_IMAGE_DESCRIPTION,
                        "PHOTON ${TextureGuard.VERSION}; original preserved"
                    )
                    exif.saveAttributes()
                } ?: throw IOException("MetadataWriteFailed")
                coroutineContext.ensureActive()
                check(
                    resolver.update(
                        candidate,
                        ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        },
                        null,
                        null
                    ) == 1
                ) { "CommitFailed" }
                committed = true
                val completedAt = SystemClock.elapsedRealtime()
                ProcessedPhoto(
                    candidate,
                    stats,
                    completedAt - started,
                    decodedAt - started,
                    filteredAt - decodedAt,
                    completedAt - filteredAt
                )
            } finally {
                bitmap.recycle()
                if (!committed && candidate != null) {
                    // Delete only this call's candidate, never the source.
                    try {
                        resolver.delete(candidate, null, null)
                    } catch (_: Exception) {
                        android.util.Log.w("PhotonProcessor", "Pending output cleanup failed")
                    }
                }
            }
        }

    internal companion object {
        // Preserve orientation/exposure; never copy GPS, MakerNote or serial numbers.
        val METADATA = listOf(
            "Orientation",
            "DateTime",
            "DateTimeOriginal",
            "DateTimeDigitized",
            "ExposureTime",
            "ExposureBiasValue",
            "FNumber",
            "PhotographicSensitivity",
            "FocalLength",
            "WhiteBalance",
            "Make",
            "Model"
        )
    }
}
