package dev.lunacam.app.photon.raw

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** Recovery metadata belongs to one private capture directory, never a public photograph. */
internal object RawPendingFiles {
    private const val MAX_RECEIPT_BYTES = 262144L

    fun writeBaseline(directory: File, write: (FileOutputStream) -> Unit) {
        val destination = File(directory, "baseline.dng")
        val temporary = File(directory, "baseline.pending")
        check(!destination.exists() && !temporary.exists())
        try {
            FileOutputStream(temporary).use {
                write(it)
                it.fd.sync()
            }
            // Recovery sees the final name only after every pixel has been durably written.
            // The initial receipt already exists, so a crash on either side of this rename
            // cannot make a partial DNG eligible or strand a complete named baseline.
            check(temporary.renameTo(destination)) { "Cannot commit private RAW reference" }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun writeReceipt(directory: File, receipt: JSONObject) {
        require(receipt.getString("jobId") == directory.name)
        val bytes = receipt.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_RECEIPT_BYTES)
        val atomic = AtomicFile(File(directory, "job.json"))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun readReceipt(directory: File): JSONObject? = runCatching {
        // openRead restores an interrupted AtomicFile update before parsing. Serialize against
        // pending-count refreshes so they cannot discard a live writer's temporary file.
        AtomicFile(File(directory, "job.json")).openRead().use { input ->
            require(input.channel.size() in 1..MAX_RECEIPT_BYTES)
            JSONObject(input.readBytes().toString(Charsets.UTF_8)).also {
                require(it.getString("jobId") == directory.name)
            }
        }
    }.getOrNull()

    fun markJpegReady(directory: File) {
        FileOutputStream(File(directory, "jpeg.ready")).use {
            it.write(1)
            it.fd.sync()
        }
    }

    fun isJpegReady(directory: File): Boolean = runCatching {
        val marker = File(directory, "jpeg.ready")
        marker.length() == 1L && marker.inputStream().use { it.read() == 1 }
    }.getOrDefault(false)

    @Synchronized
    fun deleteReceipt(directory: File) {
        AtomicFile(File(directory, "job.json")).delete()
    }
}
