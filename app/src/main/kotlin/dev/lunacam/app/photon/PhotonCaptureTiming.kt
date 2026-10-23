package dev.lunacam.app.photon

import org.json.JSONObject

/** A saved photo exists independently of camera readiness; missing measurements stay unknown. */
internal fun autoReadinessReceipt(
    report: JSONObject,
    started: Long,
    sensorReady: Long?,
    shutterReady: Long?
): JSONObject = report
    .put("sensorReadyMs", sensorReady?.let { it - started } ?: JSONObject.NULL)
    .put("shutterReadyMs", shutterReady?.let { it - started } ?: JSONObject.NULL)
    .put("within1200Ms", shutterReady?.let { it - started <= 1200 } ?: JSONObject.NULL)
