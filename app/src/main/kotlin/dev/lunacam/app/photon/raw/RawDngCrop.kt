package dev.lunacam.app.photon.raw

import dev.lunacam.app.photon.PhotonAspectRatio
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Patches framing on a newly generated private DngCreator candidate, never an original photo. */
internal object RawDngCrop {
    private const val CROP_ORIGIN = 50719
    private const val CROP_SIZE = 50720
    private const val ACTIVE_AREA = 50829
    private const val MAX_DIRECTORIES = 32
    private const val MAX_ENTRIES = 2048
    private const val MAX_ARRAY = 65536

    /**
     * DNG crop origin is relative to ActiveArea, whereas [crop] uses full RAW raster coordinates.
     * DngCreator emits both crop tags; absent/unsupported tags fail without modifying the candidate.
     * All metadata, pixel ranges and edit aliases are validated before writing. A write failure may
     * leave a partial private candidate: the caller must retain its source and not publish this file.
     * Pixel strips, CFA phase, calibration and their absolute offsets remain untouched.
     */
    fun apply(candidate: File, crop: RawZoomCrop): RawZoomCrop = patch(candidate) { crop }

    /** Uses the emitted DNG's ActiveArea, avoiding assumptions about sensor-to-raster offsets. */
    fun applyZoom(
        candidate: File,
        digitalRatio: Float,
        aspect: PhotonAspectRatio = PhotonAspectRatio.ORIGINAL
    ): RawZoomCrop = patch(candidate) { active ->
        RawZoomPolicy.crop(
            active.left,
            active.top,
            active.right,
            active.bottom,
            digitalRatio,
            aspect
        )
    }

    private fun patch(candidate: File, select: (RawZoomCrop) -> RawZoomCrop): RawZoomCrop =
        RandomAccessFile(candidate, "rw").use { file ->
            val length = file.length()
            range(0, 8, length)
            val header = read(file, 0, 8)
            val order = when {
                header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
                header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() -> ByteOrder.BIG_ENDIAN
                else -> throw IllegalArgumentException("Unsupported TIFF byte order")
            }
            val h = ByteBuffer.wrap(header).order(order)
            require(ushort(h, 2) == 42) { "Only classic TIFF is supported" }
            val first = uint(h, 4)
            require(first != 0L) { "Missing TIFF directory" }
            val pending = ArrayDeque<Long>().apply { add(first) }
            val visited = mutableSetOf<Long>()
            val directories = mutableListOf<Directory>()
            val protected = mutableListOf(Span(0, 8, -1))
            var serial = 0
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                require(visited.add(offset) && visited.size <= MAX_DIRECTORIES) {
                    "Cyclic, shared or excessive TIFF directories"
                }
                range(offset, 2, length)
                require(offset >= 8)
                val count = ushort(ByteBuffer.wrap(read(file, offset, 2)).order(order), 0)
                require(count in 1..MAX_ENTRIES) { "Unsupported TIFF directory size" }
                val size = 2 + count * 12 + 4
                range(offset, size.toLong(), length)
                val b = ByteBuffer.wrap(read(file, offset, size)).order(order)
                val entries = ArrayList<Entry>(count)
                var previous = -1
                repeat(count) { index ->
                    val at = 2 + index * 12
                    val tag = ushort(b, at)
                    require(tag > previous) { "Unsorted or duplicate TIFF tags" }
                    previous = tag
                    val type = ushort(b, at + 2)
                    val amount = uint(b, at + 4)
                    val unit = when (type) {
                        1, 2, 6, 7 -> 1
                        3, 8 -> 2
                        4, 9, 11, 13 -> 4
                        5, 10, 12 -> 8
                        else -> throw IllegalArgumentException("Unsupported TIFF field type")
                    }
                    require(amount > 0)
                    val bytes = amount * unit
                    val valueOffset = if (bytes <= 4) offset + at + 8 else uint(b, at + 8)
                    range(valueOffset, bytes, length)
                    val entry = Entry(tag, type, amount, valueOffset, bytes, serial++)
                    entries += entry
                    if (bytes > 4) protected += Span(valueOffset, bytes, entry.id)
                    if (tag in setOf(330, 34665, 34853, 40965)) {
                        require(type == 4 || type == 13)
                        require(amount <= MAX_DIRECTORIES && (tag == 330 || amount == 1L))
                        integers(file, entry, order).forEach {
                            require(it != 0L) { "Null nested TIFF directory" }
                            pending.add(it)
                        }
                    }
                }
                val next = uint(b, 2 + count * 12)
                if (next != 0L) pending.add(next)
                protectPixels(file, entries, 273, 279, order, length, protected)
                protectPixels(file, entries, 324, 325, order, length, protected)
                protectPixels(file, entries, 513, 514, order, length, protected)
                directories += Directory(Span(offset, size.toLong(), -1), entries)
            }
            directories.forEachIndexed { index, directory ->
                require(protected.none { it.overlaps(directory.span) }) {
                    "TIFF directory overlaps payload"
                }
                require(directories.take(index).none { it.span.overlaps(directory.span) })
            }
            val raw = directories.filter { directory ->
                val photo = directory.entries.find { it.tag == 262 }
                photo != null && integers(file, photo, order) == listOf(32803L)
            }.singleOrNull() ?: throw IllegalArgumentException("Expected one CFA RAW directory")
            fun entry(tag: Int) = raw.entries.singleOrNull { it.tag == tag }
                ?: throw IllegalArgumentException("Missing DNG tag $tag")
            fun scalar(tag: Int): Long {
                val values = integers(file, entry(tag), order)
                require(values.size == 1)
                return values.single()
            }
            val width = scalar(256)
            val height = scalar(257)
            require(width in 2..Int.MAX_VALUE.toLong() && height in 2..Int.MAX_VALUE.toLong())
            val active = raw.entries.find { it.tag == ACTIVE_AREA }?.let {
                integers(file, it, order).also { values -> require(values.size == 4) }
            } ?: listOf(0, 0, height, width)
            val (top, left, bottom, right) = active
            require(left >= 0 && top >= 0 && right > left && bottom > top)
            require(right <= width && bottom <= height)
            val crop = select(RawZoomCrop(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()))
            require(crop.left >= 0 && crop.top >= 0 && crop.width >= 2 && crop.height >= 2)
            require(crop.right > crop.left && crop.bottom > crop.top)
            require(listOf(crop.left, crop.top, crop.width, crop.height).all { it % 2 == 0 })
            require(
                crop.left >= left && crop.top >= top && crop.right <= right && crop.bottom <= bottom
            ) {
                "Requested crop lies outside DNG ActiveArea"
            }
            val plans = listOf(
                plan(entry(CROP_ORIGIN), longArrayOf(crop.left - left, crop.top - top), order),
                plan(
                    entry(CROP_SIZE),
                    longArrayOf(crop.width.toLong(), crop.height.toLong()),
                    order
                )
            )
            plans.forEachIndexed { index, plan ->
                require(protected.none { it.id != plan.id && it.overlaps(plan.span) }) {
                    "Crop metadata aliases another TIFF payload"
                }
                require(plans.take(index).none { it.span.overlaps(plan.span) })
            }
            plans.forEach {
                file.seek(it.span.offset)
                file.write(it.bytes)
            }
            file.fd.sync()
            crop
        }

    private fun plan(entry: Entry, values: LongArray, order: ByteOrder): Plan {
        require(entry.amount == 2L && entry.type in setOf(3, 4, 5)) {
            "Unsupported DNG crop representation"
        }
        val b = ByteBuffer.allocate(entry.bytes.toInt()).order(order)
        values.forEach { value ->
            require(value in 0..0xffffffffL)
            when (entry.type) {
                3 -> {
                    require(value <= 65535)
                    b.putShort(value.toShort())
                }
                4 -> b.putInt(value.toInt())
                5 -> {
                    b.putInt(value.toInt())
                    b.putInt(1)
                }
            }
        }
        return Plan(Span(entry.offset, entry.bytes, entry.id), b.array(), entry.id)
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
        require(offsets != null && counts != null && offsets.amount == counts.amount)
        val starts = integers(file, offsets, order)
        val sizes = integers(file, counts, order)
        starts.indices.forEach {
            require(sizes[it] > 0)
            range(starts[it], sizes[it], length)
            protected += Span(starts[it], sizes[it], -1)
        }
    }

    private fun integers(file: RandomAccessFile, entry: Entry, order: ByteOrder): List<Long> {
        require(entry.type in setOf(3, 4, 13) && entry.amount <= MAX_ARRAY)
        val b = ByteBuffer.wrap(read(file, entry.offset, entry.bytes.toInt())).order(order)
        return List(entry.amount.toInt()) {
            if (entry.type == 3) ushort(b, it * 2).toLong() else uint(b, it * 4)
        }
    }

    private fun read(file: RandomAccessFile, offset: Long, count: Int): ByteArray {
        file.seek(offset)
        return ByteArray(count).also(file::readFully)
    }

    private fun range(offset: Long, size: Long, length: Long) {
        require(offset >= 0 && size >= 0 && offset <= length && size <= length - offset) {
            "TIFF range outside candidate"
        }
    }

    private fun ushort(buffer: ByteBuffer, at: Int) = buffer.getShort(at).toInt() and 0xffff
    private fun uint(buffer: ByteBuffer, at: Int) = buffer.getInt(at).toLong() and 0xffffffffL

    private data class Entry(
        val tag: Int,
        val type: Int,
        val amount: Long,
        val offset: Long,
        val bytes: Long,
        val id: Int
    )
    private data class Directory(val span: Span, val entries: List<Entry>)
    private data class Plan(val span: Span, val bytes: ByteArray, val id: Int)
    private data class Span(val offset: Long, val length: Long, val id: Int) {
        fun overlaps(other: Span) =
            offset < other.offset + other.length && other.offset < offset + length
    }
}
