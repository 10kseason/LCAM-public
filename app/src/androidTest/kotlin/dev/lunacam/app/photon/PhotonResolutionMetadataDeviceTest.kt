package dev.lunacam.app.photon

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Characteristics only. Never opens a camera, creates a session, or reads photograph data. */
@RunWith(AndroidJUnit4::class)
class PhotonResolutionMetadataDeviceTest {
    @Test
    fun inspectNormalHighAndMaximumResolutionMetadata() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("probeResolution") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val errors = JSONArray()
        val publicIds = manager.cameraIdList.toSet()
        val allIds = publicIds.toMutableSet()
        publicIds.forEach { id ->
            read(errors, "$id.physicalIds") {
                allIds.addAll(manager.getCameraCharacteristics(id).physicalCameraIds)
            }
        }
        val cameras = JSONArray()
        allIds.sorted().forEach { id ->
            val item = JSONObject().put("id", id).put("public", id in publicIds)
            cameras.put(item)
            val info = read(errors, "$id.characteristics") {
                manager.getCameraCharacteristics(id)
            } ?: return@forEach
            val capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            item.put("capabilities", JSONArray(capabilities?.toList().orEmpty()))
                .put(
                    "ultraHighResolutionSensor",
                    CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
                        in (capabilities ?: intArrayOf())
                )
                .put("facing", info.get(CameraCharacteristics.LENS_FACING))
                .put("physicalIds", JSONArray(info.physicalCameraIds.toList()))
                .put(
                    "supportsPixelModeRequest",
                    CaptureRequest.SENSOR_PIXEL_MODE in info.availableCaptureRequestKeys
                )
                .put(
                    "pixelArray",
                    info.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let(::size)
                        ?: JSONObject.NULL
                )
                .put(
                    "maximumPixelArray",
                    read(errors, "$id.maximumPixelArray") {
                        info.get(
                            CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION
                        )
                    }?.let(::size) ?: JSONObject.NULL
                )
                .put(
                    "defaultMode",
                    streamMap(
                        read(errors, "$id.defaultMap") {
                            info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        },
                        "$id.defaultMode",
                        errors
                    )
                )
                .put(
                    "maximumResolutionMode",
                    streamMap(
                        read(errors, "$id.maximumMap") {
                            info.get(
                                CameraCharacteristics
                                    .SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                            )
                        },
                        "$id.maximumResolutionMode",
                        errors
                    )
                )
        }
        val report = JSONObject().put("schemaVersion", 2)
            .put("createdAtUtc", Instant.now().toString())
            .put("cameras", cameras).put("errors", errors)
            .put("cameraOpened", false).put("photosCaptured", 0).put("pixelsRead", false)
        File(context.filesDir, "photon-resolution-metadata.json").writeText(report.toString(2))
    }

    private fun streamMap(
        map: StreamConfigurationMap?,
        prefix: String,
        errors: JSONArray
    ): JSONObject {
        val result = JSONObject().put("available", map != null)
        if (map == null) return result
        val formats = JSONObject()
        result.put("formats", formats)
        for ((name, format) in mapOf(
            "JPEG" to ImageFormat.JPEG,
            "YUV_420_888" to ImageFormat.YUV_420_888,
            "RAW_SENSOR" to ImageFormat.RAW_SENSOR,
            "PRIVATE" to ImageFormat.PRIVATE
        )) {
            val entry = JSONObject().put("advertised", format in map.outputFormats)
            formats.put(name, entry)
            if (format !in map.outputFormats) continue
            val normal = read(errors, "$prefix.$name.normalSizes") { map.getOutputSizes(format) }
            val high = read(errors, "$prefix.$name.highResolutionSizes") {
                map.getHighResolutionOutputSizes(format)
            }
            entry.put("normalSizes", sizes(normal, map, format, "$prefix.$name", errors))
                .put(
                    "highResolutionSizes",
                    sizes(high, map, format, "$prefix.$name", errors)
                )
        }
        return result
    }

    private fun sizes(
        values: Array<Size>?,
        map: StreamConfigurationMap,
        format: Int,
        prefix: String,
        errors: JSONArray
    ): Any = values?.let {
        val sorted = it.sortedByDescending { value -> value.width.toLong() * value.height }
        JSONArray(
            sorted.map { value ->
                size(value)
                    .put(
                        "minFrameDurationNs",
                        read(errors, "$prefix.$value.minFrameDuration") {
                            map.getOutputMinFrameDuration(format, value)
                        } ?: JSONObject.NULL
                    )
                    .put(
                        "stallDurationNs",
                        read(errors, "$prefix.$value.stallDuration") {
                            map.getOutputStallDuration(format, value)
                        } ?: JSONObject.NULL
                    )
            }
        )
    } ?: JSONObject.NULL

    private fun size(value: Size): JSONObject = JSONObject()
        .put("width", value.width).put("height", value.height)
        .put("pixels", value.width.toLong() * value.height)

    private fun <T> read(errors: JSONArray, field: String, block: () -> T): T? = try {
        block()
    } catch (error: Exception) {
        errors.put(JSONObject().put("field", field).put("type", error.javaClass.simpleName))
        null
    }
}
