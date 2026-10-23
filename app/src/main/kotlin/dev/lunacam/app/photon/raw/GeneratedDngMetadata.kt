package dev.lunacam.app.photon.raw

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metadata repair for a newly generated, unpublished FUSED candidate only; never an original. */
object GeneratedDngMetadata {
    private const val NOISE_PROFILE = 51041
    private const val SUB_IFDS = 330
    private const val MAX_DIRECTORIES = 32
    private const val MAX_ENTRIES = 2048
    private const val MAX_ARRAY = 65536

    /**
     * Removes the single-frame noise model, which cannot describe adaptive temporal fusion.
     * All structure/range checks and edit plans finish before the first write. I/O failure can
     * still leave a partial candidate, so the caller must delete it and must not publish it.
     * Pixel strips and value blocks never move; their absolute TIFF offsets remain valid.
     */
    fun removeNoiseProfile(candidate: File): Int = RandomAccessFile(candidate, "rw").use { file ->
        val length = file.length()
        require(length >= 8) { "Truncated TIFF header" }
        val header = ByteArray(8).also(file::readFully)
        val order = when {
            header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("Unsupported TIFF byte order")
        }
        val h = ByteBuffer.wrap(header).order(order)
        require(ushort(h, 2) == 42) { "Only classic TIFF is supported" }
        val first = uint(h, 4)
        require(first != 0L) { "Missing TIFF image directory" }
        val directories = mutableListOf<Directory>()
        val protected = mutableListOf(Span(0, 8))
        val pending = ArrayDeque<Long>().apply { add(first) }
        val visited = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            require(visited.add(offset) && visited.size <= MAX_DIRECTORIES) {
                "Cyclic, shared or excessive TIFF directories"
            }
            checkRange(offset, 2, length)
            require(offset >= 8) { "Directory overlaps TIFF header" }
            val countBuffer = ByteBuffer.wrap(read(file, offset, 2)).order(order)
            val count = ushort(countBuffer, 0)
            require(count in 1..MAX_ENTRIES) { "Unsupported TIFF directory size" }
            val bytes = 2 + count * 12 + 4
            checkRange(offset, bytes.toLong(), length)
            val buffer = ByteBuffer.wrap(read(file, offset, bytes)).order(order)
            val entries = ArrayList<Entry>(count)
            var previousTag = -1
            for (index in 0 until count) {
                val position = 2 + index * 12
                val tag = ushort(buffer, position)
                require(tag > previousTag) { "Unsorted or duplicate TIFF tags" }
                previousTag = tag
                val type = ushort(buffer, position + 2)
                val amount = uint(buffer, position + 4)
                val elementSize = when (type) {
                    1, 2, 6, 7 -> 1
                    3, 8 -> 2
                    4, 9, 11, 13 -> 4
                    5, 10, 12 -> 8
                    else -> throw IllegalArgumentException("Unsupported TIFF field type")
                }
                require(amount > 0) { "Empty TIFF field" }
                val valueBytes = amount * elementSize
                val valueOffset = if (valueBytes <= 4) {
                    offset + position + 8
                } else {
                    uint(buffer, position + 8)
                }
                checkRange(valueOffset, valueBytes, length)
                if (valueBytes > 4) protected += Span(valueOffset, valueBytes)
                val entry = Entry(tag, type, amount, valueOffset, position)
                entries += entry
                if (tag == SUB_IFDS || tag in setOf(34665, 34853, 40965)) {
                    require(type == 4 || type == 13) { "Invalid SubIFD type" }
                    if (tag != SUB_IFDS) require(amount == 1L) { "Invalid metadata IFD pointer" }
                    require(amount <= MAX_DIRECTORIES) { "Excessive SubIFDs" }
                    numbers(file, entry, order).forEach {
                        require(it != 0L) { "Null SubIFD" }
                        pending.add(it)
                    }
                }
                if (tag == NOISE_PROFILE) {
                    require(type == 12 && (amount == 2L || amount == 6L)) {
                        "Unexpected NoiseProfile representation"
                    }
                }
            }
            val next = uint(buffer, 2 + count * 12)
            if (next != 0L) pending.add(next)
            // Strip/tile payload pointers are semantic offsets, unlike ordinary tag values.
            protectPixels(file, entries, 273, 279, order, length, protected)
            protectPixels(file, entries, 324, 325, order, length, protected)
            directories += Directory(offset, buffer, entries, next)
        }
        val spans = directories.map { Span(it.offset, it.buffer.capacity().toLong()) }
        spans.forEachIndexed { index, span ->
            require(protected.none { it.overlaps(span) }) { "Directory overlaps TIFF payload" }
            require(spans.take(index).none { it.overlaps(span) }) { "Overlapping TIFF directories" }
        }
        val plans = directories.mapNotNull { directory ->
            if (directory.entries.none { it.tag == NOISE_PROFILE }) return@mapNotNull null
            val keep = directory.entries.filter { it.tag != NOISE_PROFILE }
            require(keep.isNotEmpty()) { "Cannot remove the only TIFF entry" }
            val output = ByteBuffer.allocate(2 + keep.size * 12 + 4).order(order)
            output.putShort(keep.size.toShort())
            keep.forEach { output.put(directory.buffer.array(), it.position, 12) }
            output.putInt(directory.next.toInt())
            directory.offset to output.array()
        }
        plans.forEach { (offset, bytes) ->
            file.seek(offset)
            file.write(bytes)
        }
        plans.size
    }

    private fun protectPixels(
        file: RandomAccessFile,
        entries: List<Entry>,
        offsetsTag: Int,
        countsTag: Int,
        order: ByteOrder,
        length: Long,
        protected: MutableList<Span>
    ) {
        val offsets = entries.find { it.tag == offsetsTag }
        val counts = entries.find { it.tag == countsTag }
        if (offsets == null && counts == null) return
        require(offsets != null && counts != null && offsets.amount == counts.amount) {
            "Invalid TIFF pixel payload arrays"
        }
        val starts = numbers(file, offsets, order)
        val sizes = numbers(file, counts, order)
        starts.indices.forEach {
            require(sizes[it] > 0) { "Empty TIFF pixel payload" }
            checkRange(starts[it], sizes[it], length)
            protected += Span(starts[it], sizes[it])
        }
    }

    private fun numbers(file: RandomAccessFile, entry: Entry, order: ByteOrder): List<Long> {
        require(entry.type == 3 || entry.type == 4 || entry.type == 13) {
            "Invalid TIFF offset type"
        }
        require(entry.amount <= MAX_ARRAY) { "Excessive TIFF offset array" }
        val size = if (entry.type == 3) 2 else 4
        val bytes = read(file, entry.valueOffset, entry.amount.toInt() * size)
        val buffer = ByteBuffer.wrap(bytes).order(order)
        return List(entry.amount.toInt()) { index ->
            if (size == 2) ushort(buffer, index * size).toLong() else uint(buffer, index * size)
        }
    }

    private fun read(file: RandomAccessFile, offset: Long, count: Int): ByteArray {
        file.seek(offset)
        return ByteArray(count).also(file::readFully)
    }

    private fun checkRange(offset: Long, count: Long, length: Long) {
        require(offset >= 0 && count >= 0 && offset <= length && count <= length - offset) {
            "TIFF range outside candidate"
        }
    }

    private fun ushort(buffer: ByteBuffer, at: Int) = buffer.getShort(at).toInt() and 0xffff
    private fun uint(buffer: ByteBuffer, at: Int) = buffer.getInt(at).toLong() and 0xffffffffL

    private data class Entry(
        val tag: Int,
        val type: Int,
        val amount: Long,
        val valueOffset: Long,
        val position: Int
    )

    private data class Directory(
        val offset: Long,
        val buffer: ByteBuffer,
        val entries: List<Entry>,
        val next: Long
    )

    private data class Span(val offset: Long, val length: Long) {
        fun overlaps(other: Span) =
            offset < other.offset + other.length && other.offset < offset + length
    }
}
