package dev.lunacam.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

class DiagnosticExportWriter(context: Context) : AutoCloseable {
    private val contentResolver = context.applicationContext.contentResolver
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LunaCam-DiagnosticExport")
        }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun write(uri: Uri, content: String, onComplete: (Result<Unit>) -> Unit) {
        executor.execute {
            val result =
                try {
                    val output =
                        contentResolver.openOutputStream(uri, "wt")
                            ?: throw IOException("The selected document could not be opened")
                    output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
                    Result.success(Unit)
                } catch (error: IOException) {
                    Result.failure(error)
                } catch (error: SecurityException) {
                    Result.failure(error)
                }
            mainHandler.post { onComplete(result) }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
