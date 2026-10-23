package dev.lunacam.app.photon.raw

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import dev.lunacam.app.photon.PhotonAspectRatio
import dev.lunacam.processing.nativebridge.NativeRawFusion
import dev.lunacam.processing.nativebridge.RawFusionFrame
import dev.lunacam.processing.nativebridge.RawFusionMetadata
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RawSaveResult(val outputCount: Int, val fused: Boolean, val message: String)

/** Capture inputs stay in memory. Publish exactly one result, or one baseline on fusion failure. */
class RawPhotoStore(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    suspend fun save(burst: RawBurst, merge: Boolean, job: RawCaptureJob): RawSaveResult =
        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            val captureName = "PHOTON_RAW_${job.shutterStartedWallTimeMs}_${job.id}"
            var output: Uri? = null
            var fused = false
            var baselineIndex = 0
            val receipt = JSONObject().put("schemaVersion", 2).put("jobId", job.id)
                .put("frameCount", burst.frames.size).put("captureMs", burst.elapsedMs)
                .put("mergeRequested", merge).put("status", "processing")
                .put("cameraId", burst.cameraId ?: JSONObject.NULL)
                .put("opticalRatio", burst.opticalRatio ?: JSONObject.NULL)
                .put("digitalZoom", burst.digitalZoom)
                .put("aspectRatio", burst.aspectRatio.id)
                .put("zoomRatio", burst.opticalRatio?.times(burst.digitalZoom) ?: JSONObject.NULL)
                .put(
                    "framing",
                    if (burst.digitalZoom > 1f ||
                        burst.aspectRatio != PhotonAspectRatio.ORIGINAL
                    ) {
                        "dng_default_crop"
                    } else {
                        "full_sensor"
                    }
                )
                .put("physicalIds", JSONArray(burst.frames.map { it.physicalId }.distinct()))
                .put("shutterStartedWallTimeMs", job.shutterStartedWallTimeMs)
                .put("shutterStartedElapsedMs", job.shutterStartedElapsedMs)
                .put("rawReceivedElapsedMs", job.rawReceivedElapsedMs)
                .put("shutterReadyElapsedMs", job.shutterReadyElapsedMs ?: JSONObject.NULL)
                .put(
                    "shutterReadyMs",
                    job.shutterReadyElapsedMs?.minus(job.shutterStartedElapsedMs) ?: JSONObject.NULL
                )
                .put("processingStartedElapsedMs", started)
                .put("queueWaitMs", started - job.rawReceivedElapsedMs)
            burst.requestedControls?.let { controls ->
                receipt.put(
                    "requestedControls",
                    JSONObject()
                        .put("manualExposure", controls.manualExposure)
                        .put("exposureNs", controls.exposureNs).put("iso", controls.iso)
                        .put("evIndex", controls.evIndex).put("awbMode", controls.whiteBalance.mode)
                        .put("focusDiopters", controls.focusDiopters ?: JSONObject.NULL)
                )
            }
            RawJobJournal.write(context, receipt, false)
            try {
                require(burst.frames.size == if (merge) 3 else 1)
                require(burst.frames.map { it.physicalId }.distinct().size == 1) {
                    "PhysicalCameraChanged"
                }
                receipt.put("frames", JSONArray(burst.frames.map(::frameReport)))
                var preferred: OutputCandidate? = null
                if (merge) {
                    try {
                        val first = burst.frames.first()
                        val area = requireNotNull(
                            burst.characteristics.get(
                                CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
                            )
                        ) { "MissingActiveArea" }
                        val result = NativeRawFusion.fuse(
                            first.width,
                            first.height,
                            fusionFrames(burst),
                            intArrayOf(area.left, area.top, area.right, area.bottom)
                        )
                        coroutineContext.ensureActive()
                        baselineIndex = result.baselineIndex
                        receipt.put("fusionMs", result.elapsedMs)
                            .put("acceptedFrames", result.acceptedFrames)
                            .put("changedPixels", result.changedPixels)
                            .put("mergedPixels", result.mergedPixels)
                            .put("effectiveSamples", result.meanEffectiveSamples)
                            .put("shifts", JSONArray(result.shifts.toList()))
                        if (result.changedPixels > 0) {
                            preferred = OutputCandidate(
                                burst.frames[baselineIndex],
                                result.pixels,
                                true
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        receipt.put("fusionFailure", error.javaClass.simpleName)
                            .put("fusionFailureReason", error.message?.take(160))
                    } catch (_: OutOfMemoryError) {
                        receipt.put("fusionFailure", "OutOfMemoryError")
                    }
                }
                coroutineContext.ensureActive()
                val baseline = burst.frames[baselineIndex]
                val fallback = OutputCandidate(baseline, baseline.pixels, false)
                receipt.put("baselineIndex", baselineIndex)
                val saveStart = SystemClock.elapsedRealtime()
                val committed = RawSingleOutput.commit(preferred, fallback) { selected ->
                    val suffix = if (selected.fused) "FUSED" else "ORIGINAL"
                    saveDng(
                        burst,
                        selected.frame,
                        selected.pixels,
                        "${captureName}_$suffix.dng",
                        selected.fused,
                        receipt
                    )
                }
                output = committed.output
                fused = committed.usedPreferred
                receipt.put("saveOutputMs", SystemClock.elapsedRealtime() - saveStart)
                    .put("status", "saved")
                committed.preferredFailure?.let {
                    receipt.put("fusedWriteFailure", it.javaClass.simpleName)
                }
                RawSaveResult(
                    1,
                    fused,
                    when {
                        fused -> "합성 DNG 1장 저장 완료"
                        merge -> "합성 대신 기준 원본 DNG 1장을 저장했습니다."
                        else -> "원본 RAW 1장 저장 완료"
                    }
                )
            } catch (error: CancellationException) {
                receipt.put("status", "cancelled")
                throw error
            } catch (error: RawPublicationUncertainException) {
                receipt.put("status", "publication_uncertain")
                    .put("failure", error.javaClass.simpleName)
                RawSaveResult(
                    0,
                    false,
                    "DNG 저장 결과를 확인하지 못했습니다. 파일은 삭제하지 않았으니 갤러리를 확인해 주세요."
                )
            } catch (error: Exception) {
                val saved = output != null
                runCatching {
                    receipt.put("status", if (saved) "saved" else "failed")
                        .put("failure", error.javaClass.simpleName)
                        .put("reason", error.message?.take(160))
                }
                RawSaveResult(
                    if (saved) 1 else 0,
                    saved && fused,
                    if (saved) {
                        "DNG 1장은 저장됐지만 처리 기록을 완료하지 못했습니다."
                    } else {
                        "RAW 저장을 완료하지 못했습니다. 저장 공간을 확인해 주세요."
                    }
                )
            } catch (_: OutOfMemoryError) {
                val saved = output != null
                runCatching {
                    receipt.put("status", if (saved) "saved" else "failed")
                        .put("failure", "OutOfMemoryError")
                }
                RawSaveResult(
                    if (saved) 1 else 0,
                    saved && fused,
                    if (saved) {
                        "DNG 1장 저장 완료 · 처리 기록 메모리 부족"
                    } else {
                        "메모리가 부족해 RAW를 저장하지 못했습니다."
                    }
                )
            } finally {
                // Optional metadata must not turn an already committed photo into a save failure.
                runCatching {
                    val outputs = output?.let { listOf(it.toString()) } ?: emptyList()
                    val completed = SystemClock.elapsedRealtime()
                    receipt.put("outputs", JSONArray(outputs)).put("outputCount", outputs.size)
                        .put("originals", JSONArray(if (fused) emptyList<String>() else outputs))
                        .put("fused", if (fused) output?.toString() else JSONObject.NULL)
                        .put("completedElapsedMs", completed)
                        .put("completedWallTimeMs", System.currentTimeMillis())
                        .put("totalMs", completed - job.shutterStartedElapsedMs)
                        .put("shutterReadyElapsedMs", job.shutterReadyElapsedMs ?: JSONObject.NULL)
                        .put(
                            "shutterReadyMs",
                            job.shutterReadyElapsedMs?.minus(job.shutterStartedElapsedMs)
                                ?: JSONObject.NULL
                        )
                    RawJobJournal.write(context, receipt, true)
                }
            }
        }

    private data class OutputCandidate(
        val frame: RawFrame,
        val pixels: ByteBuffer,
        val fused: Boolean
    )

    private fun frameReport(frame: RawFrame): JSONObject {
        val result = frame.result
        return JSONObject().put("width", frame.width).put("height", frame.height)
            .put("physicalId", frame.physicalId ?: JSONObject.NULL)
            .put("focalLength", result.get(CaptureResult.LENS_FOCAL_LENGTH))
            .put("sensorTimestamp", result.get(CaptureResult.SENSOR_TIMESTAMP))
            .put("exposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            .put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
            .put("aeMode", result.get(CaptureResult.CONTROL_AE_MODE))
            .put("evIndex", result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION))
            .put("awbMode", result.get(CaptureResult.CONTROL_AWB_MODE))
            .put("afMode", result.get(CaptureResult.CONTROL_AF_MODE))
            .put("focusDiopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
            .put("noiseReductionMode", result.get(CaptureResult.NOISE_REDUCTION_MODE))
            .put("edgeMode", result.get(CaptureResult.EDGE_MODE))
            .put(
                "noiseProfile",
                result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { profile ->
                    JSONArray(profile.map { JSONArray(listOf(it.first, it.second)) })
                }
            )
            .put(
                "dynamicBlack",
                result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let {
                    JSONArray(it.toList())
                }
            )
            .put("dynamicWhite", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL))
    }

    private fun fusionFrames(burst: RawBurst): List<RawFusionFrame> {
        val info = burst.characteristics
        require(info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in 0..3)
        val first = burst.frames.first()
        val baseExposure = exposure(first)
        val ids = burst.frames.map {
            it.physicalId ?: it.result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        }
        require(ids.distinct().size <= 1) { "PhysicalCameraChanged" }
        val timestamps = burst.frames.map { it.result.get(CaptureResult.SENSOR_TIMESTAMP) }
        require(timestamps.all { it != null } && timestamps.distinct().size == 3)
        val white = requireNotNull(info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
        val fixedBlack = requireNotNull(info.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN))
        return burst.frames.map { frame ->
            require(frame.width == first.width && frame.height == first.height)
            // DngCreator writes static WhiteLevel. Reject a mismatch rather than mislabel pixels.
            val dynamicWhite = frame.result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            require(dynamicWhite == null || dynamicWhite == white) { "UnsupportedDynamicWhite" }
            val dynamicBlack = frame.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            require(dynamicBlack == null || dynamicBlack.size == 4)
            val black = DoubleArray(4) { index ->
                dynamicBlack?.get(index)?.toDouble()
                    ?: fixedBlack.getOffsetForIndex(index % 2, index / 2).toDouble()
            }
            val profile = requireNotNull(frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE)) {
                "MissingNoiseProfile"
            }
            require(profile.size == 4)
            // Both arrays already follow CFA raster order, unlike COLOR_CORRECTION_GAINS.
            RawFusionFrame(
                frame.pixels,
                RawFusionMetadata(
                    black,
                    white.toDouble(),
                    exposure(frame) / baseExposure,
                    DoubleArray(4) { profile[it].first },
                    DoubleArray(4) { profile[it].second }
                )
            )
        }
    }

    private fun exposure(frame: RawFrame): Double {
        val time = requireNotNull(frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        val iso = requireNotNull(frame.result.get(CaptureResult.SENSOR_SENSITIVITY))
        require(time > 0 && iso > 0)
        return time.toDouble() * iso
    }

    private suspend fun saveDng(
        burst: RawBurst,
        reference: RawFrame,
        pixels: ByteBuffer,
        name: String,
        fused: Boolean,
        receipt: JSONObject
    ): Uri {
        var candidate: Uri? = null
        val publication = RawPublicationGuard()
        var temporary: File? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LUNA PHOTON RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            candidate =
                requireNotNull(
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                )
            DngCreator(burst.characteristics, reference.result).use { creator ->
                creator.setOrientation(
                    when (burst.orientationDegrees) {
                        90 -> 6
                        180 -> 3
                        270 -> 8
                        else -> 1
                    }
                )
                val description = if (fused) {
                    "PHOTON RAW fusion 0.2; single output"
                } else {
                    "PHOTON original RAW"
                }
                creator.setDescription(
                    if (burst.digitalZoom > 1f || burst.aspectRatio != PhotonAspectRatio.ORIGINAL) {
                        "$description; digitalZoom=${burst.digitalZoom}; editable full sensor with default crop"
                    } else {
                        description
                    }
                )
                val size = Size(reference.width, reference.height)
                val input = pixels.duplicate().apply { rewind() }
                if (fused || burst.digitalZoom > 1f ||
                    burst.aspectRatio != PhotonAspectRatio.ORIGINAL
                ) {
                    temporary =
                        File.createTempFile("photon-raw-candidate-", ".dng", context.cacheDir)
                    temporary.outputStream().use { creator.writeByteBuffer(it, size, input, 0) }
                    // Adaptive fusion has no single valid NoiseProfile.
                    if (fused) GeneratedDngMetadata.removeNoiseProfile(temporary)
                    if (burst.digitalZoom > 1f || burst.aspectRatio != PhotonAspectRatio.ORIGINAL) {
                        val crop = RawDngCrop.applyZoom(
                            temporary,
                            burst.digitalZoom,
                            burst.aspectRatio
                        )
                        receipt.put(
                            "defaultCropRaster",
                            JSONArray(
                                listOf(crop.left, crop.top, crop.right, crop.bottom)
                            )
                        )
                    }
                    coroutineContext.ensureActive()
                    requireNotNull(resolver.openOutputStream(candidate, "w")).use { output ->
                        temporary!!.inputStream().use { it.copyTo(output) }
                    }
                } else {
                    requireNotNull(resolver.openOutputStream(candidate, "w")).use {
                        creator.writeByteBuffer(it, size, input, 0)
                    }
                }
            }
            coroutineContext.ensureActive()
            val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            publication.commit(publish = {
                check(resolver.update(candidate, complete, null, null) == 1)
            }, isVisible = {
                // A provider can commit and then lose the reply. Avoid a second visible fallback.
                resolver.query(candidate, arrayOf("is_pending"), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) == 0 else false
                } ?: throw IllegalStateException("Cannot inspect DNG commit")
            })
            return candidate
        } finally {
            runCatching { temporary?.delete() }
            candidate?.let { uri ->
                publication.cleanUnpublished { resolver.delete(uri, null, null) }
            }
        }
    }
}
