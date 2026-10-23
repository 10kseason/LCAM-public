package dev.lunacam.app.photon.raw

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class RawCaptureJob(
    val id: String,
    val shutterStartedElapsedMs: Long,
    val shutterStartedWallTimeMs: Long,
    val rawReceivedElapsedMs: Long,
    private val nextShutterReady: AtomicLong = AtomicLong(-1)
) {
    val shutterReadyElapsedMs: Long?
        get() = nextShutterReady.get().takeIf { it >= 0 }

    fun markShutterReady(elapsedMs: Long) {
        nextShutterReady.compareAndSet(-1, elapsedMs)
    }
}

/** Bounded app-private metadata, never a pixel buffer or a public photo. */
internal object RawJobJournal {
    @Synchronized
    fun write(context: Context, receipt: JSONObject, completed: Boolean) {
        runCatching {
            val id = receipt.getString("jobId")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            val folder = File(context.filesDir, "photon-raw-jobs").apply { mkdirs() }
            val jobFile = File(folder, "$id.json")
            // A queued/final snapshot can race the UI's later capacity-release notification.
            // Keep the first known readiness even if this snapshot still contains null.
            readReceipt(jobFile)?.let { mergeReadiness(receipt, it) }
            writeAtomic(jobFile, receipt.toString(2))
            if (completed) {
                writeAtomic(
                    File(context.filesDir, "photon-raw-last-save.json"),
                    receipt.toString(2)
                )
                val modeReceipt = if (receipt.optString("pipeline").startsWith("raw_auto_")) {
                    "photon-raw-auto-last-save.json"
                } else {
                    "photon-raw-manual-last-save.json"
                }
                if (receipt.optString("status") == "saved" && receipt.optInt("outputCount") > 0) {
                    writeAtomic(File(context.filesDir, modeReceipt), receipt.toString(2))
                }
            }
            // Only prune this journal's old metadata entries; never touch a MediaStore object.
            folder.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }
                ?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
        }
    }

    @Synchronized
    fun updateShutterReady(context: Context, job: RawCaptureJob) {
        val ready = job.shutterReadyElapsedMs ?: return
        runCatching {
            require(job.id.matches(Regex("[a-f0-9-]{36}")))
            val folder = File(context.filesDir, "photon-raw-jobs")
            val file = File(folder, "${job.id}.json")
            val receipt = readReceipt(file) ?: return@runCatching
            require(receipt.getString("jobId") == job.id)
            val update = JSONObject()
                .put("shutterReadyElapsedMs", ready)
                .put("shutterReadyMs", ready - job.shutterStartedElapsedMs)
            mergeReadiness(receipt, update)
            writeAtomic(file, receipt.toString(2))
            val lastFile = File(context.filesDir, "photon-raw-last-save.json")
            val last = readReceipt(lastFile)
            if (last?.optString("jobId") == job.id) {
                mergeReadiness(last, update)
                writeAtomic(lastFile, last.toString(2))
            }
        }
    }

    internal fun mergeReadiness(target: JSONObject, known: JSONObject) {
        val ready = known.optLong("shutterReadyElapsedMs", -1)
        val current = target.optLong("shutterReadyElapsedMs", -1)
        if (ready >= 0 && (current < 0 || ready < current)) {
            val duration = known.optLong("shutterReadyMs", -1).takeIf { it >= 0 }
                ?: (
                    ready - known.optLong(
                        "shutterStartedElapsedMs",
                        target.getLong("shutterStartedElapsedMs")
                    )
                    )
            target.put("shutterReadyElapsedMs", ready)
                .put("shutterReadyMs", duration)
        }
    }

    private fun readReceipt(file: File): JSONObject? =
        if (file.isFile && file.length() in 1..262144) JSONObject(file.readText()) else null

    private fun writeAtomic(file: File, contents: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(contents.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
