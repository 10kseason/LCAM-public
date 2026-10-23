package dev.lunacam.app.photon.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeneratedDngMetadataTest {
    @Test
    fun removesAcrossSubAndNextDirectoriesWithoutMovingPayload() {
        listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).forEach { order ->
            val original = fixture(order)
            withCandidate(original) { file ->
                assertEquals(3, GeneratedDngMetadata.removeNoiseProfile(file))
                val actual = file.readBytes()
                assertEquals(original.size, actual.size)
                val before = ByteBuffer.wrap(original).order(order)
                val after = ByteBuffer.wrap(actual).order(order)
                // IFD0 has strip offsets, counts, SubIFD and NoiseProfile; last tag removed.
                assertEquals(3, after.getShort(8).toInt())
                assertEquals(192, after.getInt(8 + 2 + 3 * 12))
                assertEquals(128, after.getInt(8 + 2 + 2 * 12 + 8))
                listOf(128, 192).forEach { offset ->
                    assertEquals(1, after.getShort(offset).toInt())
                    assertEquals(0, after.getInt(offset + 2 + 12))
                    assertEquals(before.getInt(offset + 2 + 8), after.getInt(offset + 2 + 8))
                }
                // All bytes outside directory prefixes (including noise values) are identical.
                val prefixes = listOf(8 until 50, 128 until 146, 192 until 210)
                actual.indices.filter { i -> prefixes.none { i in it } }.forEach { i ->
                    assertEquals("unrelated byte $i", original[i], actual[i])
                }
                assertArrayEquals(original.copyOfRange(480, 512), actual.copyOfRange(480, 512))
                val once = file.readBytes()
                assertEquals(0, GeneratedDngMetadata.removeNoiseProfile(file))
                assertArrayEquals(once, file.readBytes())
            }
        }
    }

    @Test
    fun removesMiddleEntryAndPreservesFollowingTagAndNextPointer() {
        val bytes = fixture(ByteOrder.LITTLE_ENDIAN)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Nested directory: put a higher tag after NoiseProfile instead of before it.
        field(buffer, 130, 51041, 12, 6, 320)
        field(buffer, 142, 51125, 4, 1, 43)
        withCandidate(bytes) { file ->
            GeneratedDngMetadata.removeNoiseProfile(file)
            val result = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(51125, result.getShort(130).toInt() and 0xffff)
            assertEquals(43, result.getInt(138))
            assertEquals(0, result.getInt(142))
        }
    }

    @Test
    fun followsMetadataDirectoryPointersAndRejectsAliases() {
        for (tag in listOf(34665, 34853, 40965)) {
            val bytes = fixture(ByteOrder.LITTLE_ENDIAN)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(34, tag.toShort())
            withCandidate(bytes) { assertEquals(3, GeneratedDngMetadata.removeNoiseProfile(it)) }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(42, 8)
            withCandidate(bytes) { file ->
                assertThrows(IllegalArgumentException::class.java) {
                    GeneratedDngMetadata.removeNoiseProfile(file)
                }
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }

    @Test
    fun malformedCandidatesRemainByteForByteUnchanged() {
        val mutations: List<(ByteBuffer) -> Unit> = listOf(
            { it.putShort(2, 43) }, // BigTIFF
            { it.putInt(4, 510) }, // truncated directory
            { it.putShort(8, 3000) }, // excessive directory
            { it.putInt(58, 8) }, // cyclic next pointer
            { it.putInt(42, 192) }, // shared SubIFD and next pointer
            { it.putInt(42, 510) }, // invalid nested directory after valid root
            { it.putInt(58, 0x7fffffff) }, // invalid next directory
            { it.putInt(58, 128) }, // duplicate reference
            { it.putShort(48, 4) }, // wrong NoiseProfile type
            { it.putInt(50, 7) }, // invalid NoiseProfile count
            { it.putInt(54, 500) }, // NoiseProfile payload outside file
            { it.putInt(18, 128) }, // pixel data overlaps a nested directory
            { it.putInt(54, 128) }, // value data overlaps a nested directory
            { it.putShort(22, 273) } // duplicate tag
        )
        mutations.forEach { mutate ->
            val original = fixture(ByteOrder.LITTLE_ENDIAN)
            mutate(ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN))
            withCandidate(original) { file ->
                assertThrows(IllegalArgumentException::class.java) {
                    GeneratedDngMetadata.removeNoiseProfile(file)
                }
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    private fun fixture(order: ByteOrder): ByteArray {
        val bytes = ByteArray(512) { (it % 251).toByte() }
        val b = ByteBuffer.wrap(bytes).order(order)
        bytes[0] = if (order == ByteOrder.LITTLE_ENDIAN) 0x49 else 0x4d
        bytes[1] = bytes[0]
        b.putShort(2, 42)
        b.putInt(4, 8)
        b.putShort(8, 4)
        field(b, 10, 273, 4, 1, 480)
        field(b, 22, 279, 4, 1, 32)
        field(b, 34, 330, 4, 1, 128)
        field(b, 46, 51041, 12, 6, 256)
        b.putInt(58, 192)
        listOf(128 to 320, 192 to 384).forEach { (directory, profile) ->
            b.putShort(directory, 2)
            field(b, directory + 2, 256, 4, 1, 4)
            field(b, directory + 14, 51041, 12, 6, profile)
            b.putInt(directory + 26, 0)
        }
        return bytes
    }

    private fun field(b: ByteBuffer, at: Int, tag: Int, type: Int, count: Int, value: Int) {
        b.putShort(at, tag.toShort())
        b.putShort(at + 2, type.toShort())
        b.putInt(at + 4, count)
        b.putInt(at + 8, value)
    }

    private fun withCandidate(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("photon-generated-candidate-", ".dng")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
