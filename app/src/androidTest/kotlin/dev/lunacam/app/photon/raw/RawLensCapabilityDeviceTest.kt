package dev.lunacam.app.photon.raw

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.View
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.MainActivity
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in metadata probe. RAW is configured but never targeted; preview pixels are never read. */
@RunWith(AndroidJUnit4::class)
class RawLensCapabilityDeviceTest {
    @Test
    fun inspectRawLensAndPreviewZoomWithoutShutter() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("probeRawLenses") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val publicIds = manager.cameraIdList.toSet()
        val report = JSONObject()
            .put("photosCaptured", 0)
            .put("pixelsRead", false)
            .put("rawOutputTargeted", false)
            .put("warning", "Preview active physical IDs do not verify the source of a RAW capture")
        val cameras = JSONArray()
        val sessions = JSONArray()
        report.put("cameras", cameras).put("sessions", sessions)
        val ids = publicIds.toMutableSet()
        publicIds.forEach { ids.addAll(manager.getCameraCharacteristics(it).physicalCameraIds) }
        ids.sorted().forEach { id ->
            val item = JSONObject().put("id", id).put("public", id in publicIds)
            cameras.put(item)
            runCatching { describe(manager.getCameraCharacteristics(id), item) }
                .onFailure { item.put("error", it.toString()) }
        }
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val thread = HandlerThread("photon-raw-lens-probe").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { command -> if (!handler.post(command)) command.run() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(View(activity))
                    provider.unbindAll()
                }
                publicIds.sorted().forEach { id ->
                    val info = manager.getCameraCharacteristics(id)
                    if (info.get(CameraCharacteristics.LENS_FACING) !=
                        CameraCharacteristics.LENS_FACING_BACK
                    ) {
                        return@forEach
                    }
                    val raw = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                        ?.filter { it.width.toLong() * it.height <= 16_000_000 }
                        ?.maxByOrNull { it.width.toLong() * it.height } ?: return@forEach
                    val observation = JSONObject().put("cameraId", id).put("rawSize", size(raw))
                    sessions.put(observation)
                    runCatching { probe(manager, id, info, raw, handler, executor, observation) }
                        .onFailure { observation.put("error", it.toString()) }
                    info.physicalCameraIds.sorted().forEach physicalLoop@{ physicalId ->
                        val physical = manager.getCameraCharacteristics(physicalId)
                        val physicalRaw = physical.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                        )?.getOutputSizes(ImageFormat.RAW_SENSOR)
                            ?.filter { it.width.toLong() * it.height <= 16_000_000 }
                            ?.maxByOrNull { it.width.toLong() * it.height }
                            ?: return@physicalLoop
                        val physicalObservation = JSONObject().put("cameraId", id)
                            .put("physicalId", physicalId).put("rawSize", size(physicalRaw))
                        sessions.put(physicalObservation)
                        runCatching {
                            probe(
                                manager,
                                id,
                                physical,
                                physicalRaw,
                                handler,
                                executor,
                                physicalObservation,
                                physicalId
                            )
                        }.onFailure { physicalObservation.put("error", it.toString()) }
                    }
                }
            }
        } finally {
            thread.quitSafely()
            thread.join(2_000)
            File(context.filesDir, "photon-raw-lens-capabilities.json")
                .writeText(report.toString(2))
        }
    }

    private fun describe(info: CameraCharacteristics, json: JSONObject) {
        val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val multi = info.get(CameraCharacteristics.SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP)
        val multiRaw = JSONArray()
        if (multi != null && ImageFormat.RAW_SENSOR in multi.outputFormats) {
            multi.getOutputInfo(ImageFormat.RAW_SENSOR).forEach {
                multiRaw.put(
                    JSONObject().put("physicalId", it.physicalCameraId)
                        .put("width", it.width).put("height", it.height)
                )
            }
        }
        json.put("facing", info.get(CameraCharacteristics.LENS_FACING))
            .put("sensorOrientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("physicalIds", JSONArray(info.physicalCameraIds.toList()))
            .put(
                "capabilities",
                JSONArray(
                    info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.toList().orEmpty()
                )
            )
            .put(
                "zoomRatioRange",
                info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString()
            )
            .put(
                "maxDigitalZoom",
                info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            )
            .put(
                "focalLengths",
                JSONArray(
                    info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.toList().orEmpty()
                )
            )
            .put(
                "physicalSensorSize",
                info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.toString()
            )
            .put(
                "pixelArraySize",
                info.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString()
            )
            .put(
                "activeArray",
                info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString()
            )
            .put(
                "rawSizes",
                JSONArray(map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map(::size).orEmpty())
            )
            .put("multiResolutionRaw", multiRaw)
            .put(
                "physicalRequestKeys",
                JSONArray(info.availablePhysicalCameraRequestKeys?.map { it.name }.orEmpty())
            )
            .put("resultKeys", JSONArray(info.availableCaptureResultKeys.map { it.name }))
    }

    @SuppressLint("MissingPermission")
    private fun probe(
        manager: CameraManager,
        id: String,
        info: CameraCharacteristics,
        rawSize: Size,
        handler: Handler,
        executor: Executor,
        json: JSONObject,
        physicalId: String? = null
    ) {
        val previews = requireNotNull(
            info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ).getOutputSizes(ImageFormat.YUV_420_888)
        val previewSize = previews.filter { it.width <= 1280 && it.height <= 960 }
            .maxByOrNull { it.width.toLong() * it.height } ?: error("No bounded YUV preview")
        json.put("previewSize", size(previewSize))
        val finished = AtomicBoolean(false)
        val opened = CompletableFuture<CameraDevice>()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val preview = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        // Drain preview buffers without accessing planes or retaining any image.
        preview.setOnImageAvailableListener({ reader ->
            runCatching { reader.acquireLatestImage()?.close() }
        }, handler)
        try {
            manager.openCamera(
                id,
                executor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (finished.get() || !opened.complete(camera)) camera.close()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        opened.completeExceptionally(IllegalStateException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        opened.completeExceptionally(IllegalStateException("Camera error $error"))
                    }
                }
            )
            device = opened.get(10, TimeUnit.SECONDS)
            val configured = CompletableFuture<CameraCaptureSession>()
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(preview.surface, raw.surface).map { surface ->
                        OutputConfiguration(surface).apply {
                            physicalId?.let(::setPhysicalCameraId)
                        }
                    },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (finished.get() || !configured.complete(value)) value.close()
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) {
                            value.close()
                            configured.completeExceptionally(
                                IllegalStateException("RAW + preview session rejected")
                            )
                        }
                    }
                )
            )
            session = configured.get(10, TimeUnit.SECONDS)
            val zooms = JSONArray()
            json.put("zooms", zooms)
            val range = info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val requestedZooms = if (physicalId == null) listOf(0.5f, 1f, 3f, 5f) else listOf(1f)
            for (requested in requestedZooms) {
                val applied = range?.clamp(requested) ?: 1f
                val completed = CompletableFuture<JSONArray>()
                val samples = JSONArray()
                val token = Any()
                val builder = if (physicalId == null) {
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                } else {
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, setOf(physicalId))
                }
                val request = builder.apply {
                    addTarget(preview.surface)
                    setTag(token)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    if (range != null) set(CaptureRequest.CONTROL_ZOOM_RATIO, applied)
                }.build()
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        active: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (request.tag !== token || completed.isDone) return
                        samples.put(
                            JSONObject().put("frameNumber", result.frameNumber)
                                .put("sensorTimestamp", result.get(CaptureResult.SENSOR_TIMESTAMP))
                                .put(
                                    "activePhysicalId",
                                    result.get(
                                        CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID
                                    )
                                )
                                .put("focalLength", result.get(CaptureResult.LENS_FOCAL_LENGTH))
                                .put("zoomRatio", result.get(CaptureResult.CONTROL_ZOOM_RATIO))
                                .put(
                                    "cropRegion",
                                    result.get(CaptureResult.SCALER_CROP_REGION)?.toShortString()
                                )
                                .put(
                                    "physicalResultIds",
                                    JSONArray(result.physicalCameraTotalResults.keys.toList())
                                )
                                .put(
                                    "physicalFocalLength",
                                    physicalId?.let { result.physicalCameraTotalResults[it] }
                                        ?.get(CaptureResult.LENS_FOCAL_LENGTH)
                                )
                                .put(
                                    "physicalTimestamp",
                                    physicalId?.let { result.physicalCameraTotalResults[it] }
                                        ?.get(CaptureResult.SENSOR_TIMESTAMP)
                                )
                        )
                        if (samples.length() == 30) completed.complete(samples)
                    }
                }
                session.setRepeatingRequest(request, callback, handler)
                zooms.put(
                    JSONObject().put("requestedZoom", requested).put("appliedZoom", applied)
                        .put("samples", completed.get(8, TimeUnit.SECONDS))
                )
            }
        } finally {
            finished.set(true)
            session?.close()
            device?.close()
            preview.close()
            raw.close()
        }
    }

    private fun size(value: Size) = JSONObject()
        .put("width", value.width)
        .put("height", value.height)
}
