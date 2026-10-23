package dev.lunacam.app.photon.rawauto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import dev.lunacam.app.photon.isCompleteCapturedJpeg
import dev.lunacam.app.photon.night.nightColor
import dev.lunacam.app.photon.night.nightRawInputs
import dev.lunacam.app.photon.raw.RawBurst
import dev.lunacam.app.photon.raw.RawCaptureJob
import dev.lunacam.app.photon.raw.RawDngCrop
import dev.lunacam.app.photon.raw.RawJobJournal
import dev.lunacam.app.photon.raw.RawPendingFiles
import dev.lunacam.app.photon.raw.RawZoomCrop
import dev.lunacam.processing.nativebridge.NativeRawHdr
import dev.lunacam.processing.nativebridge.NativeSerNight
import dev.lunacam.processing.nativebridge.NativeSerRender
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class RawAutoSave(val uri: Uri, val message: String)

/** Independent experimental output namespace. One public JPEG, or one explicit reference DNG. */
internal class RawAutoPhotoStore(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val root = File(this.context.filesDir, "photon-raw-auto-pending")

    suspend fun save(burst: RawBurst, strength: Float, job: RawCaptureJob): RawAutoSave =
        withContext(Dispatchers.Default) {
            require(burst.frames.size in listOf(1, 3) && job.id.matches(Regex("[a-f0-9-]{36}")))
            val frame = burst.frames.first()
            val folder = File(root, job.id).apply { mkdirs() }
            val jpeg = File(folder, "result.jpg")
            val started = SystemClock.elapsedRealtime()
            val receipt = JSONObject().put(
                "schemaVersion",
                2
            ).put("pipeline", if (burst.hdrRequested) "raw_auto_hdr_v2" else "raw_auto_single_v1")
                .put("jobId", job.id).put("status", "processing").put("outputCount", 0)
                .put(
                    "appVersion",
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                )
                .put("width", frame.width).put("height", frame.height)
                .put("physicalId", frame.physicalId ?: JSONObject.NULL)
                .put("captureMs", burst.elapsedMs).put("framesCaptured", burst.frames.size)
                .put("hdrRequested", burst.hdrRequested).put("hdrApplied", false)
                .put("previewRestored", burst.previewRestored)
                .put("digitalZoom", burst.digitalZoom).put("opticalRatio", burst.opticalRatio)
                .put("aspectRatio", burst.aspectRatio.id)
                .put("compensationBiasEv", burst.compensationBiasEv)
                .put("zoomRatio", (burst.opticalRatio ?: 1f) * burst.digitalZoom)
                .put("shadowStrengthRequested", strength).put("jpegEncodeCount", 0)
                .put("shutterStartedElapsedMs", job.shutterStartedElapsedMs)
                .put("shutterStartedWallTimeMs", job.shutterStartedWallTimeMs)
                .put("rawReceivedElapsedMs", job.rawReceivedElapsedMs)
            job.shutterReadyElapsedMs?.let {
                receipt.put("shutterReadyElapsedMs", it)
                    .put("shutterReadyMs", it - job.shutterStartedElapsedMs)
            }
            var bitmap: Bitmap? = null
            try {
                // Commit recovery identity before writing pixels so a fully synced baseline is
                // never stranded between its creation and the first metadata write.
                RawPendingFiles.writeReceipt(folder, receipt)
                // Commit a complete private reference before any experimental rendering.
                DngCreator(burst.characteristics, frame.result).use { creator ->
                    creator.setOrientation(orientation(burst.orientationDegrees))
                    RawPendingFiles.writeBaseline(folder) {
                        creator.writeByteBuffer(
                            it,
                            Size(frame.width, frame.height),
                            frame.pixels.duplicate(),
                            0
                        )
                    }
                }
                // Read the emitted DNG ActiveArea: sensor metadata offsets need not equal file offsets.
                RawPendingFiles.writeReceipt(folder, receipt)
                val (framed, crop) = prepareFramedDng(folder, burst.digitalZoom, burst.aspectRatio)
                receipt.put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom)))
                RawPendingFiles.writeReceipt(folder, receipt)
                var useJpeg = false
                try {
                    coroutineContext.ensureActive()
                    val inputs = nightRawInputs(burst, requireNightResolution = false)
                    var color = nightColor(burst, 0, inputs.frames.first().metadata)
                    val index =
                        requireNotNull(
                            frame.result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                        )
                    val step =
                        requireNotNull(
                            burst.characteristics.get(
                                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
                            )
                        )
                    val ev = index * step.toFloat()
                    val profile = frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE)
                    val matrixBound = (0..2).maxOf { row ->
                        (0..2).sumOf { column ->
                            abs(color.sensorToSrgb[row * 3 + column]).toDouble()
                        }
                    }.toFloat()
                    val colorBound = color.whiteBalance.max() * matrixBound *
                        (color.shading?.gains?.max() ?: 1f)
                    val tone = rawAutoTone(
                        ev,
                        strength,
                        profile?.map { it.first }?.toDoubleArray(),
                        profile?.map { it.second }?.toDoubleArray(),
                        colorBound,
                        burst.compensationBiasEv
                    )
                    receipt.put("appliedEv", ev).put("exposureGain", tone.exposureGain)
                        .put(
                            "shadowStrengthApplied",
                            tone.strength
                        ).put("noiseFloor", tone.noiseFloor)
                        .put("noisePolicy", "bounded_device_model_or_conservative_default")
                        .put("iso", frame.result.get(CaptureResult.SENSOR_SENSITIVITY))
                        .put("exposureNs", frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    var hdrReason = burst.hdrFallbackReason ?: rawAutoHdrEvidence(burst)
                    val hdr = if (hdrReason == null) {
                        runCatching {
                            // Validate every physical color/shading map, including the short reference.
                            val colors = burst.frames.indices.map {
                                nightColor(burst, it, inputs.frames[it].metadata)
                            }
                            val baseShading = requireNotNull(colors.first().shading)
                            colors.drop(1).forEach { candidate ->
                                val shading = requireNotNull(candidate.shading)
                                require(shading.gains.size == baseShading.gains.size)
                                require(
                                    shading.gains.indices.all {
                                        abs(shading.gains[it] / baseShading.gains[it] - 1f) <= 0.02f
                                    }
                                ) { "ShadingChanged" }
                            }
                            NativeRawHdr.fuse(
                                frame.width,
                                frame.height,
                                inputs.frames,
                                inputs.activeArea
                            )
                        }.getOrElse {
                            hdrReason = "hdr_rejected_${it.javaClass.simpleName}"
                            null
                        }
                    } else {
                        null
                    }
                    val hdrGain = hdr?.let { tone.exposureGain * it.exposureGainScale }
                    val gainSupported = hdrGain != null && hdrGain.isFinite() && hdrGain in 1f..16f
                    val useHdr = hdr != null && hdr.mergedPixels > 0 && gainSupported
                    if (hdr != null) {
                        receipt.put("hdrAcceptedMask", hdr.acceptedMask)
                            .put(
                                "hdrMergedPixels",
                                hdr.mergedPixels
                            ).put("hdrRejectedCells", hdr.rejectedCells)
                            .put(
                                "hdrShifts",
                                JSONArray(hdr.shifts.toList())
                            ).put("hdrMs", hdr.elapsedMs)
                            .put("hdrReferenceIndex", hdr.referenceIndex)
                        if (!useHdr) {
                            hdrReason = if (!gainSupported) {
                                "render_gain_out_of_range"
                            } else {
                                "alignment_or_motion_rejected"
                            }
                        }
                    }
                    receipt.put("hdrApplied", useHdr).put("hdrReason", hdrReason ?: "merged")
                        .put(
                            "frameExposureScales",
                            JSONArray(
                                inputs.frames.map {
                                    it.metadata.exposureScale
                                }
                            )
                        )
                    val single = listOf(inputs.frames.first())
                    val packedPixels = if (useHdr) {
                        color =
                            nightColor(
                                burst,
                                hdr!!.referenceIndex,
                                inputs.frames[hdr.referenceIndex].metadata
                            )
                                .copy(
                                    blackLevels = hdr.outputBlackLevels,
                                    whiteLevel = hdr.outputWhiteLevel
                                )
                        hdr.packedPixels
                    } else {
                        val plan = NativeSerNight.prepare(
                            frame.width,
                            frame.height,
                            single,
                            inputs.activeArea
                        )
                        val packed = NativeSerNight.fuse(
                            frame.width,
                            frame.height,
                            single,
                            plan,
                            false
                        )
                        packed.packedPixels
                    }
                    val renderGain = tone.exposureGain * if (useHdr) hdr!!.exposureGainScale else 1f
                    receipt.put("renderExposureGain", renderGain)
                    val renderStarted = SystemClock.elapsedRealtime()
                    bitmap =
                        NativeSerRender.renderNatural(
                            frame.width,
                            frame.height,
                            packedPixels,
                            color,
                            renderGain,
                            tone.strength,
                            tone.noiseFloor
                        )
                    receipt.put(
                        "renderMs",
                        SystemClock.elapsedRealtime() - renderStarted
                    ).put("backend", "cpu")
                    val full = requireNotNull(bitmap)
                    if (crop.left != 0 || crop.top != 0 || crop.width != full.width ||
                        crop.height != full.height
                    ) {
                        bitmap =
                            Bitmap.createBitmap(full, crop.left, crop.top, crop.width, crop.height)
                        if (bitmap !== full) full.recycle()
                    }
                    receipt.put("encodedWidth", crop.width).put("encodedHeight", crop.height)
                    coroutineContext.ensureActive()
                    FileOutputStream(jpeg).use {
                        check(requireNotNull(bitmap).compress(Bitmap.CompressFormat.JPEG, 97, it))
                        it.fd.sync()
                    }
                    receipt.put("jpegEncodeCount", 1)
                    ExifInterface(jpeg).apply {
                        setAttribute(
                            "Orientation",
                            orientation(burst.orientationDegrees).toString()
                        )
                        setAttribute(
                            "ExposureTime",
                            (
                                (
                                    frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                        ?: 0
                                    ) /
                                    1e9
                                ).toString()
                        )
                        setAttribute(
                            "PhotographicSensitivity",
                            frame.result.get(CaptureResult.SENSOR_SENSITIVITY)?.toString()
                        )
                        setAttribute("ExposureBiasValue", ev.toString())
                        setAttribute(
                            "FNumber",
                            frame.result.get(CaptureResult.LENS_APERTURE)?.toString()
                        )
                        setAttribute(
                            "FocalLength",
                            frame.result.get(CaptureResult.LENS_FOCAL_LENGTH)?.toString()
                        )
                        setAttribute("ColorSpace", "1")
                        setAttribute("Software", "LUNA PHOTON RAW AUTO v2")
                        setAttribute("DigitalZoomRatio", burst.digitalZoom.toString())
                        saveAttributes()
                    }
                    check(isCompleteCapturedJpeg(jpeg))
                    FileOutputStream(jpeg, true).use { it.fd.sync() }
                    RawPendingFiles.markJpegReady(folder)
                    useJpeg = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    receipt.put(
                        "renderFailure",
                        error.javaClass.simpleName
                    ).put("renderReason", error.message?.take(160))
                } catch (_: OutOfMemoryError) {
                    receipt.put("renderFailure", "OutOfMemoryError")
                } finally {
                    bitmap?.recycle()
                    bitmap = null
                }
                RawPendingFiles.writeReceipt(folder, receipt)
                coroutineContext.ensureActive()
                val output = publish(
                    if (useJpeg) jpeg else framed,
                    job.id,
                    if (useJpeg) "image/jpeg" else "image/x-adobe-dng"
                )
                receipt.put(
                    "status",
                    "saved"
                ).put("outputCount", 1).put("output", output.first.toString())
                    .put("mimeType", output.second).put(
                        "processSaveMs",
                        SystemClock.elapsedRealtime() - started
                    )
                RawJobJournal.write(context, receipt, true)
                clean(folder)
                RawAutoSave(
                    output.first,
                    if (output.second == "image/jpeg") {
                        "RAW AUTO 사진 1장 저장 완료"
                    } else {
                        "현상할 수 없어 원본 DNG 1장 보존 · RAW 지원 앱에서 확인하세요."
                    }
                )
            } catch (error: Exception) {
                receipt.put("status", "failed").put("failure", error.javaClass.simpleName)
                RawJobJournal.write(context, receipt, false)
                throw error
            } finally {
                bitmap?.recycle()
            }
        }

    internal fun publish(file: File, id: String, mime: String): Pair<Uri, String> {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(mime == "image/jpeg" || mime == "image/x-adobe-dng")
        val stem = "PHOTON_RAW_AUTO_$id"
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id", "is_pending", "mime_type"),
            "_display_name IN (?, ?) AND owner_package_name = ?",
            arrayOf("$stem.jpg", "$stem.dng", context.packageName),
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
                "$stem.${if (mime == "image/jpeg") "jpg" else "dng"}"
            )
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LUNA PHOTON RAW AUTO")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri =
            requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        var committed = false
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            check(
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                ) ==
                    1
            )
            committed = true
            return uri to mime
        } finally {
            if (!committed) runCatching { resolver.delete(uri, null, null) }
        }
    }

    fun pendingCount(): Int = pending().size

    suspend fun recover(): RawAutoSave? = withContext(Dispatchers.IO) {
        val folder = pending().firstOrNull() ?: return@withContext null
        val baseline = File(folder, "baseline.dng")
        val jpeg = File(folder, "result.jpg")
        val useJpeg = RawPendingFiles.isJpegReady(folder) && isCompleteCapturedJpeg(jpeg)
        if (!useJpeg) require(ExifInterface(baseline).getAttributeInt("ImageWidth", 0) >= 64)
        val receipt = requireNotNull(RawPendingFiles.readReceipt(folder))
        val source = if (useJpeg) {
            jpeg
        } else {
            prepareFramedDng(
                folder,
                receipt.optDouble("digitalZoom", 1.0).toFloat(),
                dev.lunacam.app.photon.PhotonAspectRatio.fromPreference(
                    receipt.optString("aspectRatio")
                )
            ).first
        }
        val output = publish(
            source,
            folder.name,
            if (useJpeg) "image/jpeg" else "image/x-adobe-dng"
        )
        receipt.put("status", "saved")
            .put(
                "outputCount",
                1
            ).put("output", output.first.toString()).put("mimeType", output.second)
            .put("recovered", true)
        RawJobJournal.write(context, receipt, true)
        clean(folder)
        RawAutoSave(
            output.first,
            if (output.second == "image/jpeg") {
                "보존된 RAW AUTO 사진 복구 완료"
            } else {
                "보존된 원본 DNG 복구 완료"
            }
        )
    }

    private fun pending(): List<File> = root.listFiles()?.filter {
        it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) &&
            File(it, "baseline.dng").length() > 8 &&
            RawPendingFiles.readReceipt(it)?.optString("jobId") == it.name
    }?.sortedBy { it.name }.orEmpty()

    private fun clean(folder: File) {
        listOf(
            "baseline.dng",
            "baseline.pending",
            "framed.dng",
            "result.jpg",
            "jpeg.ready"
        ).forEach {
            File(folder, it).delete()
        }
        RawPendingFiles.deleteReceipt(folder)
        folder.delete()
    }

    private fun prepareFramedDng(
        folder: File,
        zoom: Float,
        aspect: dev.lunacam.app.photon.PhotonAspectRatio
    ): Pair<File, RawZoomCrop> {
        val candidate = File(folder, "framed.dng")
        // Only this job's generated scratch copy is replaceable. A torn metadata write cannot
        // damage the synced baseline, and recovery recomputes framing from the shutter snapshot.
        File(folder, "baseline.dng").copyTo(candidate, overwrite = true)
        return candidate to RawDngCrop.applyZoom(candidate, zoom, aspect)
    }

    private fun orientation(rotation: Int) = when (rotation) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }
}
