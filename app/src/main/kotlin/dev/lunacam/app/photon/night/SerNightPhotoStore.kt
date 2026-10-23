package dev.lunacam.app.photon.night

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import dev.lunacam.app.photon.isCompleteCapturedJpeg
import dev.lunacam.app.photon.raw.RawBurst
import dev.lunacam.app.photon.raw.RawCaptureJob
import dev.lunacam.app.photon.raw.RawPendingFiles
import dev.lunacam.processing.nativebridge.GpuSerNight
import dev.lunacam.processing.nativebridge.GpuSerNightResult
import dev.lunacam.processing.nativebridge.NativeSerNight
import dev.lunacam.processing.nativebridge.NativeSerRender
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SerNightSaveResult(val uri: Uri, val message: String, val receipt: JSONObject)

/** Complete private reference first; publish exactly one output only after rendering is done. */
class SerNightPhotoStore(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val pendingRoot = File(this.context.filesDir, "photon-night-pending")

    suspend fun save(burst: RawBurst, ser: Boolean, job: RawCaptureJob): SerNightSaveResult =
        withContext(Dispatchers.Default) {
            require(job.id.matches(Regex("[a-f0-9-]{36}")))
            val first = burst.frames.first()
            require(first.width == 4080 && first.height == 3060) { "NightRequires1248Mp" }
            val directory = File(pendingRoot, job.id).apply { mkdirs() }
            val baseline = File(directory, "baseline.dng")
            val jpeg = File(directory, "result.jpg")
            val stages = JSONObject()
            val started = SystemClock.elapsedRealtime()
            val receipt = JSONObject().put("schemaVersion", 1).put("pipelineRevision", 4)
                .put("jobId", job.id)
                .put(
                    "appVersion",
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                )
                .put("status", "processing").put("serRequested", ser).put("serApplied", false)
                .put("width", first.width).put("height", first.height)
                .put("outputCount", 0).put("captureMs", burst.elapsedMs)
                .put("shutterStartedElapsedMs", job.shutterStartedElapsedMs)
                .put("shutterStartedWallTimeMs", job.shutterStartedWallTimeMs)
                .put("rawReceivedElapsedMs", job.rawReceivedElapsedMs)
                .put("cameraId", burst.cameraId ?: JSONObject.NULL)
                .put("physicalIds", JSONArray(burst.frames.map { it.physicalId }.distinct()))
                .put("evidenceFrames", burst.frames.size).put("jpegEncodeCount", 0)
                .put("processingStagesMs", stages).put("calibratedSnr", false)
                .put("captureTiming", burst.nightTiming?.toJson() ?: JSONObject.NULL)
                .put(
                    "frames",
                    JSONArray(
                        burst.frames.map { frame ->
                            JSONObject().put("width", frame.width).put("height", frame.height)
                                .put("physicalId", frame.physicalId ?: JSONObject.NULL)
                                .put(
                                    "sensorTimestamp",
                                    frame.result.get(CaptureResult.SENSOR_TIMESTAMP)
                                )
                                .put(
                                    "exposureNs",
                                    frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                )
                                .put("iso", frame.result.get(CaptureResult.SENSOR_SENSITIVITY))
                                .put(
                                    "frameDurationNs",
                                    frame.result.get(CaptureResult.SENSOR_FRAME_DURATION)
                                )
                        }
                    )
                )
            fun <T> measured(name: String, block: () -> T): T {
                val phase = SystemClock.elapsedRealtime()
                try {
                    return block()
                } finally {
                    stages.put(name, SystemClock.elapsedRealtime() - phase)
                }
            }
            var bitmap: Bitmap? = null
            var selected = baseline
            var mime = "image/x-adobe-dng"
            try {
                RawPendingFiles.writeReceipt(directory, receipt)
                measured("preserveReference") { preserveReference(burst, baseline) }
                RawPendingFiles.writeReceipt(directory, receipt)
                SerNightJournal.write(context, receipt, false)
                try {
                    coroutineContext.ensureActive()
                    val inputs = measured("metadata") { nightRawInputs(burst) }
                    val useSer = ser && inputs.evidenceAllowed && inputs.frames.size == 3
                    // Single-frame bindings are intentional fallback, never extra observations.
                    val frames = if (useSer) inputs.frames else inputs.frames.take(1)
                    val plan = measured("alignmentAndEvidence") {
                        NativeSerNight.prepare(first.width, first.height, frames, inputs.activeArea)
                    }
                    val color =
                        nightColor(burst, plan.baselineIndex, frames[plan.baselineIndex].metadata)
                    receipt.put(
                        "colorMetadata",
                        JSONObject().put("cfa", color.cfa)
                            .put("blackLevels", JSONArray(color.blackLevels.toList()))
                            .put("whiteLevel", color.whiteLevel)
                            .put("whiteBalance", JSONArray(color.whiteBalance.toList()))
                            .put("sensorToSrgb", JSONArray(color.sensorToSrgb.toList()))
                            .put(
                                "neutralPointRGB",
                                burst.frames[plan.baselineIndex].result
                                    .get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { point ->
                                        JSONArray(point.map { it.toDouble() })
                                    } ?: JSONObject.NULL
                            )
                            .put(
                                "lensShading",
                                color.shading?.let {
                                    JSONObject().put("columns", it.columns).put("rows", it.rows)
                                        .put("gainMin", it.gains.minOrNull())
                                        .put("gainMax", it.gains.maxOrNull())
                                        .put("activeArea", JSONArray(it.activeArea.toList()))
                                } ?: JSONObject.NULL
                            )
                    )
                    receipt.put(
                        "serEvidenceReason",
                        if (useSer) plan.reason else inputs.evidenceReason
                    )
                        .put("baselineIndex", plan.baselineIndex)
                        .put("acceptedMask", plan.acceptedMask)
                        .put("shifts", JSONArray(plan.shifts.toList()))
                    val gpu = measured("gpu") {
                        GpuSerNight.process(first.width, first.height, frames, plan, color, useSer)
                    }
                    when (gpu) {
                        is GpuSerNightResult.Success -> {
                            bitmap = gpu.bitmap
                            receipt.put("backend", "gpu_gles31")
                                .put("gpuStagesNs", JSONObject(gpu.stagesNs))
                                .put("gpuTotalNs", gpu.totalNs)
                                .put("changedPixels", gpu.stats.changedPixels)
                                .put("liftedCells", gpu.stats.liftedCells)
                                .put("serApplied", useSer && gpu.stats.liftedCells > 0)
                        }
                        is GpuSerNightResult.Unavailable -> {
                            receipt.put("backend", "cpu").put("gpuFallbackReason", gpu.reason)
                            val fusion = measured("cpuFusion") {
                                NativeSerNight.fuse(first.width, first.height, frames, plan, useSer)
                            }
                            bitmap = measured("cpuRender") {
                                NativeSerRender.render(
                                    first.width,
                                    first.height,
                                    fusion.packedPixels,
                                    color
                                )
                            }
                            receipt.put("changedPixels", fusion.changedPixels)
                                .put("liftedCells", fusion.liftedCells)
                                .put("serApplied", useSer && fusion.liftedCells > 0)
                        }
                    }
                    val outputBitmap = requireNotNull(bitmap)
                    require(outputBitmap.width == 4080 && outputBitmap.height == 3060)
                    measured("jpegEncode") {
                        FileOutputStream(jpeg).use { output ->
                            check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 97, output))
                            output.fd.sync()
                        }
                    }
                    receipt.put("jpegEncodeCount", 1)
                    measured("jpegMetadata") { writeJpegMetadata(jpeg, burst, plan.baselineIndex) }
                    check(isCompleteCapturedJpeg(jpeg))
                    // A complete JPEG bitstream does not prove EXIF orientation was committed.
                    // Recovery may use it only after metadata and the completion marker are synced.
                    FileOutputStream(jpeg, true).use { it.fd.sync() }
                    RawPendingFiles.markJpegReady(directory)
                    selected = jpeg
                    mime = "image/jpeg"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    receipt.put("processingFailure", error.javaClass.simpleName)
                        .put("processingReason", error.message?.take(160))
                        .put("serApplied", false)
                } catch (_: OutOfMemoryError) {
                    receipt.put("processingFailure", "OutOfMemoryError").put("serApplied", false)
                } finally {
                    bitmap?.recycle()
                    bitmap = null
                }
                coroutineContext.ensureActive()
                // Publication is outside rendering fallback. Storage failure cannot create both
                // JPEG and DNG or discard the complete private reference needed for recovery.
                val published = measured("publish") { publish(selected, job.id, mime) }
                receipt.put("output", published.first.toString()).put("mimeType", published.second)
                    .put("outputCount", 1).put("status", "saved")
                    .put("processSaveMs", SystemClock.elapsedRealtime() - started)
                    .put("totalMs", SystemClock.elapsedRealtime() - job.shutterStartedElapsedMs)
                if (published.second != "image/jpeg") receipt.put("serApplied", false)
                job.shutterReadyElapsedMs?.let {
                    receipt.put("shutterReadyElapsedMs", it)
                        .put("shutterReadyMs", it - job.shutterStartedElapsedMs)
                        .put("within1200Ms", it - job.shutterStartedElapsedMs <= 1200)
                }
                SerNightJournal.write(context, receipt, true)
                // Delete only these explicitly owned inputs after the one-output commit.
                listOf(baseline, jpeg, File(directory, "jpeg.ready")).forEach { it.delete() }
                RawPendingFiles.deleteReceipt(directory)
                directory.delete()
                SerNightSaveResult(
                    published.first,
                    when {
                        published.second != "image/jpeg" -> "색·처리 근거가 부족해 기준 DNG 1장 보존"
                        receipt.optBoolean("serApplied") -> "SER 나이트 JPEG 1장 저장 완료 · 12.48MP"
                        ser -> "12.48MP JPEG 1장 저장 · 불확실 영역은 기준 유지"
                        else -> "SER OFF · 12.48MP JPEG 1장 저장 완료"
                    } + if (burst.nightTiming?.exposureLimited == true) {
                        " · ISO 상한으로 기준보다 어두울 수 있음"
                    } else {
                        ""
                    },
                    receipt
                )
            } catch (error: Exception) {
                receipt.put("status", "failed").put("failure", error.javaClass.simpleName)
                SerNightJournal.write(context, receipt, false)
                throw error
            } finally {
                bitmap?.recycle()
            }
        }

    private fun preserveReference(burst: RawBurst, file: File) {
        val frame = burst.frames.first()
        DngCreator(burst.characteristics, frame.result).use { creator ->
            creator.setOrientation(exifOrientation(burst.orientationDegrees))
            creator.setDescription("PHOTON Night private reference")
            RawPendingFiles.writeBaseline(requireNotNull(file.parentFile)) { output ->
                creator.writeByteBuffer(
                    output,
                    Size(frame.width, frame.height),
                    frame.pixels.duplicate(),
                    0
                )
            }
        }
        require(file.length() > 8)
    }

    private fun writeJpegMetadata(file: File, burst: RawBurst, baseline: Int) {
        val result = burst.frames[baseline].result
        ExifInterface(file).apply {
            setAttribute("Orientation", exifOrientation(burst.orientationDegrees).toString())
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                setAttribute("ExposureTime", (it / 1_000_000_000.0).toString())
            }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                setAttribute("PhotographicSensitivity", it.toString())
            }
            result.get(CaptureResult.LENS_APERTURE)?.let { setAttribute("FNumber", it.toString()) }
            result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                setAttribute("FocalLength", it.toString())
            }
            setAttribute("ImageWidth", "4080")
            setAttribute("ImageLength", "3060")
            setAttribute("ColorSpace", "1")
            setAttribute("Software", "LUNA PHOTON SER Night 0.11 r4")
            saveAttributes()
        }
    }

    private fun exifOrientation(rotation: Int): Int = when (rotation) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    /** Exact own-job publication is also used by reference recovery and synthetic tests. */
    internal fun publish(file: File, id: String, mime: String): Pair<Uri, String> {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(mime == "image/jpeg" || mime == "image/x-adobe-dng")
        val jpgName = "PHOTON_NIGHT_$id.jpg"
        val dngName = "PHOTON_NIGHT_$id.dng"
        // Both possible extensions share one job identity. Retry after commit-before-cleanup
        // returns an existing completed output rather than creating its alternative format.
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id", "is_pending", "mime_type"),
            "_display_name IN (?, ?) AND owner_package_name = ?",
            arrayOf(jpgName, dngName, context.packageName),
            null
        )?.use { rows ->
            while (rows.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    rows.getLong(0)
                )
                if (rows.getInt(1) == 0) return uri to rows.getString(2)
                resolver.delete(uri, null, null)
            }
        }
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                if (mime ==
                    "image/jpeg"
                ) {
                    jpgName
                } else {
                    dngName
                }
            )
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LUNA PHOTON Night")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val candidate =
            requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        var committed = false
        try {
            requireNotNull(resolver.openOutputStream(candidate, "w")).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(resolver.update(candidate, complete, null, null) == 1)
            committed = true
            return candidate to mime
        } finally {
            if (!committed) runCatching { resolver.delete(candidate, null, null) }
        }
    }

    fun pendingCount(): Int = pendingDirectories().size

    /** Recovery exports an already complete JPEG or its private reference, never reruns capture. */
    suspend fun recoverPending(): SerNightSaveResult? = withContext(Dispatchers.IO) {
        val directory = pendingDirectories().firstOrNull() ?: return@withContext null
        val receipt = requireNotNull(RawPendingFiles.readReceipt(directory))
        require(receipt.getString("jobId") == directory.name)
        val jpeg = File(directory, "result.jpg")
        val baseline = File(directory, "baseline.dng")
        val useJpeg = nightJpegReadyForRecovery(directory)
        if (!useJpeg) {
            val exif = ExifInterface(baseline)
            require(exif.getAttributeInt("ImageWidth", 0) == 4080)
            require(exif.getAttributeInt("ImageLength", 0) == 3060)
        }
        coroutineContext.ensureActive()
        val output = publish(
            if (useJpeg) jpeg else baseline,
            directory.name,
            if (useJpeg) "image/jpeg" else "image/x-adobe-dng"
        )
        receipt.put("status", "saved").put("outputCount", 1)
            .put("output", output.first.toString()).put("mimeType", output.second)
            .put("recoveredReference", true).put("serApplied", false)
        SerNightJournal.write(context, receipt, true)
        listOf(jpeg, baseline, File(directory, "jpeg.ready")).forEach { it.delete() }
        RawPendingFiles.deleteReceipt(directory)
        directory.delete()
        SerNightSaveResult(output.first, "보존된 사진 1장 복구 저장 완료", receipt)
    }

    private fun pendingDirectories(): List<File> = pendingRoot.listFiles()?.filter { directory ->
        if (!directory.isDirectory || !directory.name.matches(Regex("[a-f0-9-]{36}"))) {
            return@filter false
        }
        File(directory, "baseline.dng").length() > 8 &&
            RawPendingFiles.readReceipt(directory)?.optString("jobId") == directory.name
    }?.sortedBy { it.name }.orEmpty()
}

internal fun nightJpegReadyForRecovery(directory: File): Boolean =
    RawPendingFiles.isJpegReady(directory) && isCompleteCapturedJpeg(File(directory, "result.jpg"))
