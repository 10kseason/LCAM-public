package dev.lunacam.app.photon.raw

import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in and receipt URIs only. Reads TIFF metadata, never pixel strips. */
@RunWith(AndroidJUnit4::class)
class RawSavedDngTest {
    @Test
    fun inspectLastExplicitCaptureMetadataOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySavedRaw") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val receiptFile = File(context.filesDir, "photon-raw-last-save.json")
        require(receiptFile.length() in 1..262144)
        val receipt = JSONObject(receiptFile.readText())
        val originals = receipt.getJSONArray("originals")
        require(originals.length() in 0..3)
        val legacyReferences = (0 until originals.length())
            .map { originals.getString(it) }.toMutableList()
        receipt.optString("fused").takeIf { it.isNotBlank() && it != "null" }
            ?.let(legacyReferences::add)
        val references = if (receipt.optInt("schemaVersion") >= 2) {
            assertEquals(1, receipt.getInt("outputCount"))
            val outputs = receipt.getJSONArray("outputs")
            assertEquals(1, outputs.length())
            assertEquals(1, legacyReferences.size)
            listOf(outputs.getString(0)).also { assertEquals(legacyReferences, it) }
        } else {
            legacyReferences
        }
        require(references.distinct().size == references.size && references.size <= 4)
        val images = JSONArray()
        val report = JSONObject().put("images", images).put("pixelsRead", false)
            .put("pixelsDecoded", false).put("photosCaptured", 0)
            .put("jobId", receipt.opt("jobId")).put("captureMs", receipt.opt("captureMs"))
            .put("shutterReadyMs", receipt.opt("shutterReadyMs"))
            .put("totalMs", receipt.opt("totalMs")).put("outputCount", references.size)
        var failure: Throwable? = null
        for (reference in references) {
            val record = JSONObject().put("uri", reference)
            images.put(record)
            try {
                val uri = Uri.parse(reference)
                require(uri.scheme == "content" && uri.authority == "media")
                resolver.query(
                    uri,
                    arrayOf("_size", "mime_type", "is_pending", "owner_package_name"),
                    null,
                    null,
                    null
                )!!.use { row ->
                    assertTrue(row.moveToFirst())
                    record.put("bytes", row.getLong(0)).put("mime", row.getString(1))
                        .put("pending", row.getInt(2))
                    assertTrue(row.getLong(0) > 0)
                    assertEquals("image/x-adobe-dng", row.getString(1))
                    assertEquals(0, row.getInt(2))
                    assertEquals(context.packageName, row.getString(3))
                }
                resolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                    val inspection = TiffReader(descriptor.fileDescriptor).inspect()
                    record.put("tiff", inspection)
                    val directories = inspection.getJSONArray("directories")
                    var dimensions = false
                    for (index in 0 until directories.length()) {
                        val tags = directories.getJSONObject(index).getJSONObject("tags")
                        if (tags.has("256") && tags.has("257")) {
                            val width = tags.getJSONObject("256").getJSONArray("values").getLong(0)
                            val height = tags.getJSONObject("257").getJSONArray("values").getLong(0)
                            assertTrue(width > 0 && height > 0)
                            dimensions = true
                        }
                        if (tags.has("274")) {
                            val orientation = tags.getJSONObject("274")
                                .getJSONArray("values").getInt(0)
                            assertTrue(orientation in 1..8)
                        }
                    }
                    assertTrue("DNG dimensions must be present", dimensions)
                }
            } catch (error: Throwable) {
                record.put("error", error.javaClass.simpleName).put("reason", error.message)
                if (failure == null) failure = error
            }
        }
        File(context.filesDir, "photon-raw-dng-verification.json").writeText(report.toString(2))
        failure?.let { throw AssertionError("DNG metadata verification failed", it) }
    }

    private class TiffReader(private val descriptor: FileDescriptor) {
        private val length = Os.fstat(descriptor).st_size
        private var bytesRead = 0
        private lateinit var order: ByteOrder
        private val payloads = mutableListOf<Span>()
        private val directories = mutableListOf<Directory>()

        fun inspect(): JSONObject {
            val header = read(0, 8)
            order = when {
                header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
                header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() -> ByteOrder.BIG_ENDIAN
                else -> error("Unsupported TIFF byte order")
            }
            val buffer = ByteBuffer.wrap(header).order(order)
            require(unsignedShort(buffer, 2) == 42)
            val pending = ArrayDeque<Long>().apply { add(unsignedInt(buffer, 4)) }
            val visited = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                require(offset >= 8 && visited.add(offset) && visited.size <= 64)
                val count = unsignedShort(ByteBuffer.wrap(read(offset, 2)).order(order), 0)
                require(count in 1..4096)
                val data = ByteBuffer.wrap(read(offset + 2, count * 12 + 4)).order(order)
                val entries = (0 until count).map { index ->
                    val at = index * 12
                    val type = unsignedShort(data, at + 2)
                    val size = typeSize(type)
                    val amount = unsignedInt(data, at + 4)
                    require(amount > 0 && amount <= Int.MAX_VALUE)
                    val bytes = amount * size
                    val valueOffset = if (bytes <= 4) {
                        offset + 2 + at + 8
                    } else {
                        unsignedInt(data, at + 8)
                    }
                    checkRange(valueOffset, bytes)
                    Entry(unsignedShort(data, at), type, amount.toInt(), valueOffset, bytes)
                }
                require(entries.map { it.tag }.distinct().size == entries.size)
                entries.filter { it.tag in POINTER_TAGS }.forEach { entry ->
                    require(entry.type == 4 || entry.type == 13)
                    require(entry.count <= 64)
                    values(entry).forEach { pending.add((it as Number).toLong()) }
                }
                val next = unsignedInt(data, count * 12)
                if (next != 0L) pending.add(next)
                protectPayload(entries, 273, 279)
                protectPayload(entries, 324, 325)
                directories += Directory(offset, 2L + count * 12 + 4, entries)
            }
            val result = JSONArray()
            for (directory in directories) {
                require(payloads.none { it.overlaps(Span(directory.offset, directory.bytes)) })
                val tags = JSONObject()
                for (entry in directory.entries.filter { it.tag in SELECTED_TAGS }) {
                    require(payloads.none { it.overlaps(Span(entry.offset, entry.bytes)) }) {
                        "Metadata field overlaps image payload"
                    }
                    tags.put(
                        entry.tag.toString(),
                        JSONObject().put("type", entry.type).put("count", entry.count)
                            .put("values", JSONArray(values(entry)))
                    )
                }
                result.put(JSONObject().put("offset", directory.offset).put("tags", tags))
            }
            return JSONObject().put("byteOrder", order.toString()).put("directories", result)
                .put("metadataBytesRead", bytesRead).put("fileBytes", length)
        }

        private fun protectPayload(entries: List<Entry>, offsetsTag: Int, countsTag: Int) {
            val offsets = entries.firstOrNull { it.tag == offsetsTag }
            val counts = entries.firstOrNull { it.tag == countsTag }
            if (offsets == null && counts == null) return
            require(offsets != null && counts != null && offsets.count == counts.count)
            val starts = values(offsets)
            val sizes = values(counts)
            starts.indices.forEach { index ->
                val start = (starts[index] as Number).toLong()
                val bytes = (sizes[index] as Number).toLong()
                require(bytes > 0)
                checkRange(start, bytes)
                payloads += Span(start, bytes)
            }
        }

        private fun values(entry: Entry): List<Any> {
            require(entry.bytes <= 65536) { "TIFF field exceeds 64 KiB metadata limit" }
            val buffer = ByteBuffer.wrap(read(entry.offset, entry.bytes.toInt())).order(order)
            return List(entry.count) { index ->
                val at = index * typeSize(entry.type)
                val value: Any = when (entry.type) {
                    1, 2, 7 -> buffer.get(at).toInt() and 255
                    3 -> unsignedShort(buffer, at)
                    4, 13 -> unsignedInt(buffer, at)
                    5 -> unsignedInt(buffer, at).toDouble() / unsignedInt(buffer, at + 4)
                    6 -> buffer.get(at).toInt()
                    8 -> buffer.getShort(at).toInt()
                    9 -> buffer.getInt(at)
                    10 -> buffer.getInt(at).toDouble() / buffer.getInt(at + 4)
                    11 -> buffer.getFloat(at).toDouble()
                    12 -> buffer.getDouble(at)
                    else -> error("Unsupported TIFF field type")
                }
                if (value is Double && !value.isFinite()) value.toString() else value
            }
        }

        private fun read(offset: Long, size: Int): ByteArray {
            require(size in 0..65536 && bytesRead + size <= 2 * 1024 * 1024)
            checkRange(offset, size.toLong())
            val output = ByteArray(size)
            var count = 0
            while (count < size) {
                val received = Os.pread(descriptor, output, count, size - count, offset + count)
                require(received > 0) { "Truncated TIFF metadata" }
                count += received
            }
            bytesRead += size
            return output
        }

        private fun checkRange(offset: Long, size: Long) {
            require(offset >= 0 && size >= 0 && offset <= length && size <= length - offset)
        }

        private fun typeSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> error("Unsupported TIFF field type")
        }

        private fun unsignedShort(buffer: ByteBuffer, at: Int) =
            buffer.getShort(at).toInt() and 65535
        private fun unsignedInt(buffer: ByteBuffer, at: Int) =
            buffer.getInt(at).toLong() and 0xffffffffL
        private data class Entry(
            val tag: Int,
            val type: Int,
            val count: Int,
            val offset: Long,
            val bytes: Long
        )
        private data class Directory(val offset: Long, val bytes: Long, val entries: List<Entry>)
        private data class Span(val offset: Long, val bytes: Long) {
            fun overlaps(other: Span) =
                offset < other.offset + other.bytes && other.offset < offset + bytes
        }

        private companion object {
            val POINTER_TAGS = setOf(330, 34665, 34853, 40965)
            val SELECTED_TAGS = setOf(
                256, 257, 258, 259, 262, 274,
                33421, 33422, 50710, 50711, 50713, 50714, 50717,
                50719, 50720, 50829, 51041
            )
        }
    }
}
