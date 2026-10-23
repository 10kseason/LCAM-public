package dev.lunacam.app.photon.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawDngCropTest {
    private val crop = RawZoomCrop(22, 14, 82, 62)

    @Test
    fun zoomDerivesRasterCoordinatesFromDngActiveArea() {
        withCandidate(fixture(ByteOrder.LITTLE_ENDIAN, 4)) { file ->
            val expected = RawZoomPolicy.crop(2, 4, 98, 76, 2f)
            assertEquals(expected, RawDngCrop.applyZoom(file, 2f))
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(listOf(24L, 18L), values(b, 192, 4))
            assertEquals(listOf(48L, 36L), values(b, 208, 4))
        }
    }

    @Test
    fun patchesBothEndianFilesRelativeToActiveAreaAndPreservesOtherBytes() {
        listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).forEach { order ->
            for (type in listOf(3, 4, 5)) {
                val bytes = fixture(order, type)
                withCandidate(bytes) { file ->
                    assertEquals(crop, RawDngCrop.apply(file, crop))
                    val output = file.readBytes()
                    val b = ByteBuffer.wrap(output).order(order)
                    val atOrigin = if (type == 3) position(50719) + 8 else 192
                    val atSize = if (type == 3) position(50720) + 8 else 208
                    assertEquals(listOf(20L, 10L), values(b, atOrigin, type))
                    assertEquals(listOf(60L, 48L), values(b, atSize, type))
                    val size = when (type) {
                        3 -> 4
                        4 -> 8
                        else -> 16
                    }
                    val changed = listOf(atOrigin until atOrigin + size, atSize until atSize + size)
                    output.indices.filter { i -> changed.none { i in it } }.forEach { i ->
                        assertEquals("unrelated byte $i", bytes[i], output[i])
                    }
                    // Repeating the same framing is idempotent and never compounds the crop.
                    RawDngCrop.apply(file, crop)
                    assertArrayEquals(output, file.readBytes())
                }
            }
        }
    }

    @Test
    fun followsSubIfdWithoutMovingStripOrCalibrationData() {
        val bytes = fixture(ByteOrder.LITTLE_ENDIAN, 4)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val root = bytes.copyOfRange(8, 8 + 2 + tags.size * 12 + 4)
        System.arraycopy(root, 0, bytes, 256, root.size)
        b.putShort(8, 1)
        field(b, 10, 330, 4, 1, 256)
        b.putInt(22, 0)
        withCandidate(bytes) { file ->
            RawDngCrop.apply(file, crop)
            val result = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(listOf(20L, 10L), values(result, 192, 4))
            assertArrayEquals(bytes.copyOfRange(480, 512), file.readBytes().copyOfRange(480, 512))
        }
    }

    @Test
    fun usesFullRasterWhenActiveAreaIsAbsent() {
        val bytes = fixture(ByteOrder.LITTLE_ENDIAN, 4)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // The last entry is ActiveArea; shrink the directory while retaining every payload offset.
        b.putShort(8, (tags.size - 1).toShort())
        b.putInt(position(50829), 0)
        withCandidate(bytes) { file ->
            RawDngCrop.apply(file, crop)
            assertEquals(
                listOf(22L, 14L),
                values(ByteBuffer.wrap(file.readBytes()).order(b.order()), 192, 4)
            )
        }
    }

    @Test
    fun malformedOrAliasedFilesRemainUnchanged() {
        val mutations: List<(ByteBuffer) -> Unit> = listOf(
            { it.putShort(2, 43) },
            { it.putInt(4, 510) },
            { it.putShort(8, 3000) },
            { it.putInt(8 + 2 + tags.size * 12, 8) },
            { it.putShort(position(50719), 50720.toShort()) },
            { it.putShort(position(50719) + 2, 11) },
            { it.putInt(position(50719) + 4, 3) },
            { it.putInt(position(50719) + 8, 508) },
            { it.putInt(position(50719) + 8, 480) }, // aliases sensor pixels
            { it.putInt(position(50719) + 8, 224) }, // aliases ActiveArea
            { it.putInt(position(50719) + 8, 208) }, // aliases crop size
            { it.putInt(position(50719) + 8, 10) }, // aliases directory
            { it.putInt(position(273) + 8, 500) }, // truncated pixels
            { it.putShort(position(262) + 8, 2) }, // not CFA RAW
            { it.putInt(224 + 12, 101) }, // ActiveArea exceeds raster
            { it.putInt(position(50829) + 4, 3) } // bad ActiveArea count
        )
        mutations.forEach { mutate ->
            val original = fixture(ByteOrder.LITTLE_ENDIAN, 4)
            mutate(ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN))
            withCandidate(original) { file ->
                assertThrows(IllegalArgumentException::class.java) { RawDngCrop.apply(file, crop) }
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    @Test
    fun missingCropTagAndOutsideCropFailBeforeWriting() {
        val missing = fixture(ByteOrder.LITTLE_ENDIAN, 4)
        ByteBuffer.wrap(
            missing
        ).order(ByteOrder.LITTLE_ENDIAN).putShort(position(50719), 50718.toShort())
        withCandidate(missing) { file ->
            assertThrows(IllegalArgumentException::class.java) { RawDngCrop.apply(file, crop) }
            assertArrayEquals(missing, file.readBytes())
        }
        for (bad in listOf(RawZoomCrop(0, 0, 100, 80), RawZoomCrop(23, 14, 83, 62))) {
            val bytes = fixture(ByteOrder.LITTLE_ENDIAN, 4)
            withCandidate(bytes) { file ->
                assertThrows(IllegalArgumentException::class.java) { RawDngCrop.apply(file, bad) }
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }

    private val tags = listOf(256, 257, 262, 273, 279, 50719, 50720, 50829)
    private fun position(tag: Int) = 10 + tags.indexOf(tag) * 12

    private fun fixture(order: ByteOrder, cropType: Int): ByteArray {
        val bytes = ByteArray(512) { (it % 251).toByte() }
        val b = ByteBuffer.wrap(bytes).order(order)
        bytes[0] = if (order == ByteOrder.LITTLE_ENDIAN) 0x49 else 0x4d
        bytes[1] = bytes[0]
        b.putShort(2, 42)
        b.putInt(4, 8)
        b.putShort(8, tags.size.toShort())
        field(b, position(256), 256, 4, 1, 100)
        field(b, position(257), 257, 4, 1, 80)
        field(b, position(262), 262, 3, 1, 0)
        b.putShort(position(262) + 8, 32803.toShort())
        field(b, position(273), 273, 4, 1, 480)
        field(b, position(279), 279, 4, 1, 32)
        field(b, position(50719), 50719, cropType, 2, 192)
        field(b, position(50720), 50720, cropType, 2, 208)
        field(b, position(50829), 50829, 4, 4, 224)
        b.putInt(8 + 2 + tags.size * 12, 0)
        listOf(4, 2, 76, 98).forEachIndexed { i, value -> b.putInt(224 + i * 4, value) }
        return bytes
    }

    private fun field(b: ByteBuffer, at: Int, tag: Int, type: Int, count: Int, value: Int) {
        b.putShort(at, tag.toShort())
        b.putShort(at + 2, type.toShort())
        b.putInt(at + 4, count)
        b.putInt(at + 8, value)
    }

    private fun values(b: ByteBuffer, at: Int, type: Int): List<Long> = List(2) {
        when (type) {
            3 -> (b.getShort(at + it * 2).toInt() and 65535).toLong()
            4 -> b.getInt(at + it * 4).toLong() and 0xffffffffL
            else -> {
                assertEquals(1, b.getInt(at + it * 8 + 4))
                b.getInt(at + it * 8).toLong() and 0xffffffffL
            }
        }
    }

    private fun withCandidate(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("photon-crop-candidate-", ".dng")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
