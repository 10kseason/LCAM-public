package dev.lunacam.app.photon.night

import android.content.Context
import android.util.AtomicFile
import dev.lunacam.app.photon.raw.RawCaptureJob
import java.io.File
import org.json.JSONObject

/** Only app-owned job metadata. Images live in a different private pending directory. */
internal object SerNightJournal {
    @Synchronized
    fun write(context: Context, receipt: JSONObject, completed: Boolean) {
        runCatching {
            val id = receipt.getString("jobId")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            val folder = File(context.filesDir, "photon-night-receipts").apply { mkdirs() }
            val file = File(folder, "$id.json")
            read(file)?.let { mergeReady(receipt, it) }
            writeAtomic(file, receipt)
            if (completed && receipt.optInt("outputCount") == 1 &&
                receipt.optString("status") == "saved"
            ) {
                writeAtomic(File(context.filesDir, "photon-night-last-save.json"), receipt)
            }
            folder.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }
                ?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
        }
    }

    @Synchronized
    fun updateShutterReady(context: Context, job: RawCaptureJob) {
        val ready = job.shutterReadyElapsedMs ?: return
        runCatching {
            require(job.id.matches(Regex("[a-f0-9-]{36}")))
            val file = File(context.filesDir, "photon-night-receipts/${job.id}.json")
            val update = JSONObject().put("shutterReadyElapsedMs", ready)
                .put("shutterReadyMs", ready - job.shutterStartedElapsedMs)
            read(file)?.let { receipt ->
                mergeReady(receipt, update)
                writeAtomic(file, receipt)
            }
            val lastFile = File(context.filesDir, "photon-night-last-save.json")
            read(lastFile)?.takeIf { it.optString("jobId") == job.id }?.let {
                mergeReady(it, update)
                writeAtomic(lastFile, it)
            }
        }
    }

    @Synchronized
    fun markCameraClosed(context: Context, job: RawCaptureJob, elapsedMs: Long) {
        if (elapsedMs < job.shutterStartedElapsedMs ||
            job.shutterReadyElapsedMs?.let { elapsedMs >= it } == true
        ) {
            return
        }
        runCatching {
            require(job.id.matches(Regex("[a-f0-9-]{36}")))
            val folder = File(context.filesDir, "photon-night-receipts").apply { mkdirs() }
            val file = File(folder, "${job.id}.json")
            val record = read(file) ?: JSONObject().put("jobId", job.id)
                .put("shutterStartedElapsedMs", job.shutterStartedElapsedMs)
                .put("status", "queued").put("outputCount", 0)
            val update = JSONObject().put("cameraClosedBeforeReadyElapsedMs", elapsedMs)
            mergeReady(record, update)
            writeAtomic(file, record)
            val lastFile = File(context.filesDir, "photon-night-last-save.json")
            read(lastFile)?.takeIf { it.optString("jobId") == job.id }?.let {
                mergeReady(it, update)
                writeAtomic(lastFile, it)
            }
        }
    }

    private fun mergeReady(target: JSONObject, known: JSONObject) {
        val closed = known.optLong("cameraClosedBeforeReadyElapsedMs", -1)
        if (closed >= 0 && (
                target.optLong("cameraClosedBeforeReadyElapsedMs", -1) < 0 ||
                    closed < target.optLong("cameraClosedBeforeReadyElapsedMs")
                )
        ) {
            target.put("cameraClosedBeforeReadyElapsedMs", closed)
                .put(
                    "cameraClosedBeforeReadyMs",
                    closed - target.getLong("shutterStartedElapsedMs")
                )
        }
        val ready = known.optLong("shutterReadyElapsedMs", -1)
        if (ready >= 0 && (
                target.optLong("shutterReadyElapsedMs", -1) < 0 ||
                    ready < target.optLong("shutterReadyElapsedMs")
                )
        ) {
            val duration = known.optLong("shutterReadyMs", -1).takeIf { it >= 0 }
                ?: ready - target.getLong("shutterStartedElapsedMs")
            target.put("shutterReadyElapsedMs", ready).put("shutterReadyMs", duration)
                .put("within1200Ms", duration <= 1200)
        }
    }

    private fun read(file: File): JSONObject? =
        if (file.isFile && file.length() in 1..262144) JSONObject(file.readText()) else null

    private fun writeAtomic(file: File, receipt: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(receipt.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
