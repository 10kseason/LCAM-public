package dev.lunacam.app.photon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Fresh, public metadata only. Does not open a camera or export a file. */
class PhotonCapabilityScanner(context: Context) {
    private val context = context.applicationContext

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    suspend fun scan(): JSONObject {
        check(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
        val errors = JSONArray()
        val cameras = withContext(Dispatchers.IO) {
            val manager = context.getSystemService(CameraManager::class.java)
            val ids = probe(errors, null, "cameraIdList") { manager.cameraIdList } ?: emptyArray()
            JSONArray().apply {
                ids.forEach { id ->
                    val characteristics = probe(errors, id, "characteristics") {
                        manager.getCameraCharacteristics(id)
                    }
                    put(readCamera(id, characteristics, errors))
                }
            }
        }
        // Initialization is bounded; a failed provider must not erase the Camera2 report.
        try {
            withContext(Dispatchers.Main) {
                withTimeout(10_000) {
                    val provider = ProcessCameraProvider.getInstance(
                        context
                    ).awaitResult(context.mainExecutor)
                    val extensions = ExtensionsManager.getInstanceAsync(context, provider)
                        .awaitResult(context.mainExecutor)
                    val infos = provider.availableCameraInfos
                    for (index in 0 until cameras.length()) {
                        val camera = cameras.getJSONObject(index)
                        val id = camera.getString("id")
                        val info = infos.firstOrNull { Camera2CameraInfo.from(it).cameraId == id }
                        if (info == null) {
                            addProbeError(errors, id, "extensions", "CameraNotExposedByCameraX")
                            continue
                        }
                        val states = camera.getJSONObject("extensions")
                        for ((name, mode) in mapOf(
                            "HDR" to ExtensionMode.HDR,
                            "NIGHT" to ExtensionMode.NIGHT
                        )) {
                            states.put(
                                name,
                                extensionSupport(
                                    probe(errors, id, "extensions.$name") {
                                        extensions.isExtensionAvailable(info.cameraSelector, mode)
                                    }
                                )
                            )
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            addProbeError(errors, null, "extensions", "Timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            addProbeError(errors, null, "extensions", error.javaClass.simpleName)
        }
        return photonCapabilityReport(Instant.now().toString(), cameras, errors)
    }

    private fun readCamera(id: String, c: CameraCharacteristics?, errors: JSONArray): JSONObject {
        fun <T> read(key: CameraCharacteristics.Key<T>): T? =
            if (c == null) null else probe(errors, id, key.name) { c.get(key) }

        val facing = when (read(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        val level = when (val value = read(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            0 -> "LIMITED"
            1 -> "FULL"
            2 -> "LEGACY"
            3 -> "LEVEL_3"
            4 -> "EXTERNAL"
            null -> "unknown"
            else -> "UNKNOWN_$value"
        }
        val capabilities = read(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val map = read(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val formats = JSONObject()
        for ((name, value) in mapOf(
            "JPEG" to ImageFormat.JPEG,
            "YUV_420_888" to ImageFormat.YUV_420_888,
            "RAW_SENSOR" to ImageFormat.RAW_SENSOR
        )) {
            // null means failed/unknown. Empty array means a successful query found no sizes.
            val sizes = if (map == null) {
                null
            } else {
                probe(errors, id, "formats.$name") {
                    map.getOutputSizes(value)?.map { "${it.width}x${it.height}" } ?: emptyList()
                }
            }
            formats.put(name, sizes?.let(::JSONArray) ?: JSONObject.NULL)
        }
        val exposure = read(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = read(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val focus = read(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val physical = if (c ==
            null
        ) {
            null
        } else {
            probe(errors, id, "physicalCameraIds") { c.physicalCameraIds.sorted() }
        }
        return JSONObject().put("id", id).put("facing", facing).put("hardwareLevel", level)
            .put("physicalCameraIds", physical?.let(::JSONArray) ?: JSONObject.NULL)
            .put(
                "capabilities",
                capabilities?.map(::capabilityName)?.let(::JSONArray) ?: JSONObject.NULL
            )
            .put("formats", formats)
            .put(
                "manual",
                JSONObject()
                    .put(
                        "exposureTimeRangeNs",
                        exposure?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL
                    )
                    .put(
                        "isoRange",
                        iso?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL
                    )
                    .put("minimumFocusDistance", focus ?: JSONObject.NULL)
            )
            .put("extensions", JSONObject().put("HDR", "unknown").put("NIGHT", "unknown"))
    }

    private fun capabilityName(value: Int): String = when (value) {
        0 -> "BACKWARD_COMPATIBLE"
        1 -> "MANUAL_SENSOR"
        2 -> "MANUAL_POST_PROCESSING"
        3 -> "RAW"
        4 -> "PRIVATE_REPROCESSING"
        5 -> "READ_SENSOR_SETTINGS"
        6 -> "BURST_CAPTURE"
        7 -> "YUV_REPROCESSING"
        8 -> "DEPTH_OUTPUT"
        9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        10 -> "MOTION_TRACKING"
        11 -> "LOGICAL_MULTI_CAMERA"
        12 -> "MONOCHROME"
        13 -> "SECURE_IMAGE_DATA"
        14 -> "SYSTEM_CAMERA"
        15 -> "OFFLINE_PROCESSING"
        16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        17 -> "REMOSAIC_REPROCESSING"
        18 -> "DYNAMIC_RANGE_TEN_BIT"
        19 -> "STREAM_USE_CASE"
        20 -> "COLOR_SPACE_PROFILES"
        else -> "UNKNOWN_$value"
    }
}

internal fun addProbeError(errors: JSONArray, id: String?, field: String, reason: String) {
    // Exception messages may contain vendor paths or identifiers; record only the class/category.
    errors.put(
        JSONObject().put(
            "cameraId",
            id ?: JSONObject.NULL
        ).put("field", field).put("reason", reason)
    )
}

internal fun <T> probe(errors: JSONArray, id: String?, field: String, read: () -> T?): T? = try {
    read().also { if (it == null) addProbeError(errors, id, field, "NotReported") }
} catch (error: Exception) {
    addProbeError(errors, id, field, error.javaClass.simpleName)
    null
}

internal suspend fun <T> ListenableFuture<T>.awaitResult(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) {
                try {
                    continuation.resume(get())
                } catch (
                    error: Exception
                ) {
                    continuation.resumeWithException(error)
                }
            }
        }, executor)
        // These provider futures are process-wide; cancel only the waiter, not initialization.
    }
