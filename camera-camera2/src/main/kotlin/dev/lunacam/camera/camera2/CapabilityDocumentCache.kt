package dev.lunacam.camera.camera2

import android.util.Log
import dev.lunacam.domain.capability.CapabilityDocuments
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class CapabilityDocumentCache(private val filesDirectory: File) {
    private val cacheDirectory = File(filesDirectory, "capability-explorer")
    private val keyFile = File(cacheDirectory, "cache-key.txt")
    private val jsonFile = File(cacheDirectory, "report.json")
    private val markdownFile = File(cacheDirectory, "report.md")

    fun read(expectedKey: String): CapabilityDocuments? = try {
        val storedKey = keyFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.trim()
        if (storedKey != expectedKey) {
            clear()
            null
        } else {
            val json = jsonFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
            val markdown = markdownFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
            if (json == null || markdown == null) {
                clear()
                null
            } else {
                CapabilityDocuments(json = json, markdown = markdown)
            }
        }
    } catch (error: IOException) {
        Log.w(TAG, "Capability cache read failed; the live probe will replace it", error)
        clear()
        null
    }

    fun write(cacheKey: String, documents: CapabilityDocuments) {
        try {
            if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
                throw IOException("Could not create capability cache directory")
            }
            writeAtomically(jsonFile, documents.json)
            writeAtomically(markdownFile, documents.markdown)
            writeAtomically(keyFile, "$cacheKey\n")
        } catch (error: IOException) {
            Log.w(TAG, "Capability cache write failed; the live report remains usable", error)
        }
    }

    private fun clear() {
        listOf(keyFile, jsonFile, markdownFile).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not remove stale capability cache file: ${file.name}")
            }
        }
    }

    private fun writeAtomically(destination: File, content: String) {
        val temporary = File(cacheDirectory, "${destination.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private companion object {
        const val TAG = "LunaCamCapabilityCache"
    }
}
