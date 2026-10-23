package dev.lunacam.app.photon

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dev.lunacam.processing.nativebridge.GpuTextureGuard
import dev.lunacam.processing.nativebridge.GpuTextureResult
import dev.lunacam.processing.nativebridge.HdrFusionDiagnostics
import dev.lunacam.processing.nativebridge.NativeDetailGuard
import dev.lunacam.processing.nativebridge.NativeHdrFusion
import dev.lunacam.processing.nativebridge.NativeTextureGuard
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AutoSaveResult(val uri: Uri, val message: String, val receipt: JSONObject)

/** Header-only recovery filter; incomplete files remain private and cannot block later valid jobs. */
internal fun isCompleteCapturedJpeg(file: File): Boolean = runCatching {
    if (!file.isFile) return@runCatching false
    RandomAccessFile(file, "r").use { input ->
        if (input.length() < 4 || input.readUnsignedShort() != 0xffd8) return@runCatching false
        input.seek(input.length() - 2)
        if (input.readUnsignedShort() != 0xffd9) return@runCatching false
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0
}.getOrDefault(false)

/** New captures stay private until exactly one final JPEG is complete. */
class PhotonAutoPhotoStore(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    suspend fun save(
        input: CapturedJpegs,
        texture: Boolean,
        detail: Boolean = false,
        aspectRatio: PhotonAspectRatio = PhotonAspectRatio.ORIGINAL
    ): AutoSaveResult = withContext(Dispatchers.Default) {
        // Detail is subordinate to the existing post-processing switch, including recovery.
        val applyDetail = texture && detail
        val started = SystemClock.elapsedRealtime()
        val base = input.base
        // A recovered capture interrupted during file writing must never be published as JPEG.
        require(isCompleteCapturedJpeg(base)) { "IncompleteCapturedJpeg" }
        val output = File(base.parentFile, "result.jpg")
        var selected = base
        var bitmap: Bitmap? = null
        var fallback = false
        var changed = false
        var metadata: ExifInterface? = null
        val processingStages = JSONObject()
        fun <T> measured(name: String, action: () -> T): T {
            val stageStarted = SystemClock.elapsedRealtime()
            try {
                return action()
            } finally {
                processingStages.put(name, SystemClock.elapsedRealtime() - stageStarted)
            }
        }
        val report = JSONObject().put("captureMs", input.captureMs)
            .put("processingStagesMs", processingStages)
            .put(
                "appVersion",
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            )
            .put("hdrFramesCaptured", if (input.short != null) 2 else 1)
            .put("captureStagesMs", JSONObject(input.stages))
            .put("hdrRequested", input.hdrRequested).put("hdrCaptureReason", input.hdrReason)
            .put("textureRequested", texture).put("hdrApplied", false)
            .put("textureChangedPixels", 0)
            .put("detailRequested", applyDetail).put("detailApplied", false)
            .put("detailChangedPixels", 0)
            .put("aspectRatio", aspectRatio.id).put("cropApplied", false)
            .put("jpegEncodeCount", 0)
        try {
            if (texture || input.short != null) {
                try {
                    val exif = measured("baseMetadata") { ExifInterface(base) }
                    metadata = exif
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                    require(orientation in 1..8) { "UnknownOrientation" }
                    var decoded = measured("baseDecode") { decode(base) }
                    bitmap = decoded
                    report.put("width", decoded.width).put("height", decoded.height)
                    require(!decoded.hasGainmap()) { "GainMapUnsupported" }
                    input.short?.let { shortFile ->
                        coroutineContext.ensureActive()
                        val shortExif = measured("shortMetadata") { ExifInterface(shortFile) }
                        require(
                            shortExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) ==
                                orientation
                        )
                        val ratio = exposure(exif) / exposure(shortExif)
                        report.put("exposureRatio", ratio)
                        val shortBitmap = measured("shortDecode") { decode(shortFile) }
                        try {
                            require(!shortBitmap.hasGainmap()) { "GainMapUnsupported" }
                            val hdr = measured("hdr") {
                                NativeHdrFusion.merge(decoded, shortBitmap, ratio)
                            }
                            report.put("hdrApplied", hdr.accepted && hdr.changedPixels > 0)
                                .put("hdrReason", hdr.reason).put("hdrMs", hdr.elapsedMs)
                                .put("hdrChangedPixels", hdr.changedPixels)
                                .put("hdrShiftX", hdr.shiftX).put("hdrShiftY", hdr.shiftY)
                            report.put("hdrDiagnostics", hdrDiagnostics(hdr.diagnostics))
                            changed = hdr.changedPixels > 0
                        } finally {
                            shortBitmap.recycle()
                        }
                    }
                    if (texture) {
                        coroutineContext.ensureActive()
                        val filterStart = SystemClock.elapsedRealtime()
                        val count = measured("texture") {
                            // Small images cannot amortize GPU setup and transfer. A failed
                            // GPU call leaves decoded untouched, including prior HDR changes.
                            val gpu = if (decoded.width.toLong() * decoded.height >=
                                8_000_000
                            ) {
                                GpuTextureGuard.process(decoded, applyDetail)
                            } else {
                                GpuTextureResult.Unavailable("small_image", 0)
                            }
                            when (gpu) {
                                is GpuTextureResult.Success -> {
                                    val previous = decoded
                                    decoded = gpu.bitmap
                                    bitmap = decoded
                                    previous.recycle()
                                    report.put("textureBackend", "gpu_gles31")
                                        .put("textureGpuStagesNs", JSONObject(gpu.stagesNs))
                                        .put("detailChangedPixels", gpu.detailChangedPixels)
                                    gpu.changedPixels
                                }
                                is GpuTextureResult.Unavailable -> {
                                    report.put("textureBackend", "cpu")
                                        .put("textureGpuFallbackReason", gpu.reason)
                                    val legacyCount = NativeTextureGuard.process(decoded)
                                    if (applyDetail) {
                                        val detailCount = measured("detailCpu") {
                                            NativeDetailGuard.process(decoded)
                                        }
                                        report.put("detailChangedPixels", detailCount)
                                    }
                                    legacyCount
                                }
                            }
                        }
                        report.put("textureMs", SystemClock.elapsedRealtime() - filterStart)
                            .put("textureChangedPixels", count)
                        val detailCount = report.getLong("detailChangedPixels")
                        report.put("detailApplied", applyDetail && detailCount > 0)
                        changed = changed || count > 0 || detailCount > 0
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fallback = true
                    report.put("hdrApplied", false).put("hdrChangedPixels", 0)
                        .put("textureChangedPixels", 0)
                        .put("detailChangedPixels", 0).put("detailApplied", false)
                    report.put("processingFailure", error.javaClass.simpleName)
                        .put("processingReason", error.message?.take(160))
                    selected = base
                } catch (_: OutOfMemoryError) {
                    fallback = true
                    report.put("hdrApplied", false).put("hdrChangedPixels", 0)
                        .put("textureChangedPixels", 0)
                        .put("detailChangedPixels", 0).put("detailApplied", false)
                    report.put("processingFailure", "OutOfMemoryError")
                    selected = base
                }
                if (fallback) {
                    // A partially processed bitmap cannot be the source of the fallback crop.
                    bitmap?.recycle()
                    bitmap = null
                    changed = false
                }
            }
            coroutineContext.ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(base.path, bounds)
            val crop = aspectRatio.centerCrop(bounds.outWidth, bounds.outHeight)
            val cropApplied = crop.left != 0 || crop.top != 0 ||
                crop.width != bounds.outWidth || crop.height != bounds.outHeight
            report.put("cropApplied", cropApplied)
                .put("outputWidth", crop.width).put("outputHeight", crop.height)
                .put(
                    "cropBounds",
                    JSONObject().put("left", crop.left).put("top", crop.top)
                        .put("width", crop.width).put("height", crop.height)
                )
            // Export is deliberately outside processing fallback: an unsuccessful crop must
            // retain the private capture for retry, never publish the wrong selected ratio.
            if (changed || cropApplied) {
                val exif = metadata ?: measured("baseMetadata") { ExifInterface(base) }
                require(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) in 1..8) {
                    "UnknownOrientation"
                }
                var decoded = bitmap ?: measured("baseDecode") { decode(base) }.also { bitmap = it }
                require(!decoded.hasGainmap()) { "GainMapUnsupported" }
                if (cropApplied) {
                    val cropped = measured("crop") {
                        Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
                    }
                    if (cropped !== decoded) {
                        bitmap = cropped
                        decoded.recycle()
                        decoded = cropped
                    }
                }
                coroutineContext.ensureActive()
                measured("jpegEncode") {
                    output.outputStream().use {
                        check(decoded.compress(Bitmap.CompressFormat.JPEG, 97, it))
                    }
                }
                report.put("jpegEncodeCount", 1)
                measured("outputMetadata") {
                    ExifInterface(output).apply {
                        PhotonPhotoProcessor.METADATA.forEach { tag ->
                            exif.getAttribute(tag)?.let { setAttribute(tag, it) }
                        }
                        // Dimensions describe the stored raster; orientation remains unchanged.
                        setAttribute(ExifInterface.TAG_IMAGE_WIDTH, crop.width.toString())
                        setAttribute(ExifInterface.TAG_IMAGE_LENGTH, crop.height.toString())
                        setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, crop.width.toString())
                        setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, crop.height.toString())
                        setAttribute(
                            ExifInterface.TAG_IMAGE_DESCRIPTION,
                            "PHOTON AUTO; HDR=${report.getBoolean(
                                "hdrApplied"
                            )}; texture=$texture; detail=$applyDetail; aspect=${aspectRatio.id}"
                        )
                        saveAttributes()
                    }
                }
                selected = output
            }
            bitmap?.recycle()
            bitmap = null
            coroutineContext.ensureActive()
            val publishStarted = SystemClock.elapsedRealtime()
            val published = publish(selected, "PHOTON_${base.parentFile!!.name}.jpg")
            processingStages.put("publish", SystemClock.elapsedRealtime() - publishStarted)
            report.put("output", published.toString()).put("outputCount", 1)
                .put("fallback", fallback).put(
                    "processSaveMs",
                    SystemClock.elapsedRealtime() - started
                )
            // Only files from this unique capture are removed, after successful public commit.
            listOf(input.base, File(base.parentFile, "short.jpg"), output).distinct().forEach {
                it.delete()
            }
            File(base.parentFile, "job.json").delete()
            base.parentFile?.delete()
            val message = when {
                fallback -> "처리하지 못해 기본 사진 1장을 저장했습니다."
                report.getBoolean("hdrApplied") -> "HDR 사진 1장 저장 완료"
                input.hdrRequested -> hdrSkippedMessage(report)
                applyDetail -> "디테일 강화 사진 1장 저장 완료"
                texture -> "질감 보호 사진 1장 저장 완료"
                else -> "사진 1장 저장 완료"
            }
            AutoSaveResult(published, message, report)
        } finally {
            bitmap?.recycle()
            // On storage/cancellation failure, private base remains available for retry.
            if (selected != output) output.delete()
        }
    }

    private fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0)
        require(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        return requireNotNull(BitmapFactory.decodeFile(file.path, options))
    }

    private fun exposure(exif: ExifInterface): Double {
        val seconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        require(seconds.isFinite() && seconds > 0 && iso > 0) { "MissingExposureMetadata" }
        return seconds * iso
    }

    private fun hdrSkippedMessage(report: JSONObject): String {
        val reason = report.optString("hdrReason", report.optString("hdrCaptureReason"))
        val detail = when {
            reason == "no_highlight_evidence" -> "밝은 영역을 안전하게 합성할 근거가 부족합니다."
            reason == "exposure_mismatch" || reason.startsWith("calibration_") ->
                "두 노출의 밝기 관계를 확인하지 못했습니다."
            reason == "ambiguous_alignment" || reason == "inconsistent_alignment" ->
                "두 노출의 위치를 확실히 맞추지 못해 HDR을 생략했습니다."
            reason.contains("budget") -> "HDR 촬영 시간이 부족했습니다."
            reason.contains("unsettled") || reason.contains("moving") ->
                "초점·노출이 안정된 뒤 HDR을 다시 촬영하세요."
            else -> "이 촬영에서는 HDR을 적용하지 못했습니다."
        }
        return "사진 1장 저장 완료 · $detail"
    }

    private fun finiteJson(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL

    private fun hdrDiagnostics(value: HdrFusionDiagnostics): JSONObject = JSONObject()
        .put("alignmentAnchors", value.alignmentAnchors)
        .put("alignmentCost", finiteJson(value.alignmentCost))
        .put("alignmentMargin", finiteJson(value.alignmentMargin))
        .put("calibrationSamples", value.calibrationSamples)
        .put("calibrationKnots", value.calibrationKnots)
        .put("calibrationInlierFraction", finiteJson(value.calibrationInlierFraction))
        .put("calibrationRmse", finiteJson(value.calibrationRmse))
        .put("calibrationMinLuma", finiteJson(value.calibrationMinLuma))
        .put("calibrationMaxLuma", finiteJson(value.calibrationMaxLuma))

    private suspend fun publish(file: File, name: String): Uri {
        // Reusing this capture's stable name closes the commit-before-private-cleanup crash window.
        // Only our own exact UUID filename is considered; no previously unrelated photo is touched.
        var existing: Uri? = null
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?",
            arrayOf(name, context.packageName),
            null
        )?.use { row ->
            if (row.moveToFirst()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    row.getLong(0)
                )
                if (row.getInt(1) == 0) return uri
                existing = uri
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LUNA PHOTON")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val candidate = existing ?: requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        )
        var committed = false
        try {
            requireNotNull(resolver.openOutputStream(candidate, "w")).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            coroutineContext.ensureActive()
            val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(resolver.update(candidate, complete, null, null) == 1)
            committed = true
            return candidate
        } finally {
            if (!committed) runCatching { resolver.delete(candidate, null, null) }
        }
    }
}
