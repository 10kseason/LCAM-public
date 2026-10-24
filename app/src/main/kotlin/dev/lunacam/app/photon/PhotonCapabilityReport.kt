package dev.lunacam.app.photon

import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic envelopes contain camera capabilities only, with no device identity input. */
internal fun photonCapabilityReport(
    createdAtUtc: String,
    cameras: JSONArray,
    errors: JSONArray
): JSONObject = JSONObject()
    .put("schemaVersion", 2)
    .put("createdAtUtc", createdAtUtc)
    .put("cameras", cameras)
    .put("probeErrors", errors)
