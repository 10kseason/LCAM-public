package dev.lunacam.app

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Remove only app-owned reports from the retired diagnostic schema, never saved photos/exports. */
internal object LegacyDiagnosticCleanup {
    fun clear(filesDirectory: File, cacheDirectory: File): Int {
        var failures = 0
        fun remove(directory: File, name: String) {
            try {
                val file = File(directory, name)
                if (file.exists() && (!file.isFile || !file.delete())) failures++
            } catch (_: IOException) {
                failures++
            } catch (_: SecurityException) {
                failures++
            }
        }
        val legacy = File(filesDirectory, "capability-explorer")
        for (name in listOf("cache-key.txt", "report.json", "report.md")) {
            remove(legacy, name)
            remove(legacy, "$name.tmp")
        }
        // Opt-in probes also wrote diagnostic identity into the target app's files directory.
        for (name in listOf("photon-resolution-metadata.json", "photon-capabilities-live.json")) {
            val probeReport = File(filesDirectory, name)
            try {
                if (probeReport.isFile) {
                    val isLegacy = try {
                        val report = JSONObject(probeReport.readText(Charsets.UTF_8))
                        report.optInt("schemaVersion", 1) < 2 || report.has("device")
                    } catch (_: JSONException) {
                        true
                    }
                    if (isLegacy) remove(filesDirectory, name)
                }
            } catch (_: IOException) {
                failures++
            } catch (_: SecurityException) {
                failures++
            }
        }
        val shares = File(cacheDirectory, "photon-reports")
        try {
            shares.listFiles()?.filter {
                it.name.startsWith("capabilities-v1-") && it.name.endsWith(".json")
            }?.forEach { remove(shares, it.name) }
        } catch (_: SecurityException) {
            failures++
        }
        return failures
    }
}
