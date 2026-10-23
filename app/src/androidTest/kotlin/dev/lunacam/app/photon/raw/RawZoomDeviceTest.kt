package dev.lunacam.app.photon.raw

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lunacam.app.photon.PhotonAspectRatio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata round trip only: synthetic sensor bytes, no camera session or gallery publication. */
@RunWith(AndroidJUnit4::class)
class RawZoomDeviceTest {
    @Test fun aspectCropKeepsFullSensorAndCalibrationBytes() {
        for (aspect in listOf(PhotonAspectRatio.WIDE, PhotonAspectRatio.CINEMA)) {
            val original = fixture(ByteOrder.LITTLE_ENDIAN)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val candidate = File.createTempFile("raw-aspect-synthetic-", ".dng", context.cacheDir)
            try {
                candidate.writeBytes(original)
                val crop = RawDngCrop.applyZoom(candidate, 2f, aspect)
                val actual = candidate.readBytes()
                val sides = if (aspect == PhotonAspectRatio.WIDE) 16 to 9 else 7 to 3
                assertEquals(crop.width * sides.second, crop.height * sides.first)
                assertArrayEquals(
                    intArrayOf(crop.width, crop.height),
                    readLongPair(actual, ByteOrder.LITTLE_ENDIAN, 50720)
                )
                actual.indices.filter { it !in 192..199 && it !in 208..215 }.forEach { at ->
                    assertEquals("unchanged sensor/calibration byte $at", original[at], actual[at])
                }
            } finally {
                check(candidate.delete())
            }
        }
    }

    @Test fun syntheticDngCropIsReadableAndFullSensorPayloadRemainsUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val original = fixture(order)
            val candidate = File.createTempFile("raw-zoom-synthetic-", ".dng", context.cacheDir)
            try {
                candidate.writeBytes(original)
                val crop = RawDngCrop.applyZoom(candidate, 2f)
                assertEquals(RawZoomCrop(26, 22, 74, 58), crop)
                val actual = candidate.readBytes()
                // ExifInterface 1.4.2 omits DNG crop tags from its IFD0 TIFF tag map. Read the
                // standard RAW-directory tags independently; ExifInterface still verifies that
                // the full raster dimensions remain intact rather than labelling it as resized.
                assertArrayEquals(intArrayOf(24, 18), readLongPair(actual, order, 50719))
                assertArrayEquals(intArrayOf(48, 36), readLongPair(actual, order, 50720))
                val exif = ExifInterface(candidate)
                assertEquals(100, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, -1))
                assertEquals(80, exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, -1))
                actual.indices.filter { it !in 192..199 && it !in 208..215 }.forEach { at ->
                    assertEquals("unrelated byte $at", original[at], actual[at])
                }
                assertArrayEquals(
                    original.copyOfRange(480, original.size),
                    actual.copyOfRange(480, original.size)
                )
            } finally {
                check(candidate.delete()) { "Synthetic DNG cleanup failed" }
            }
        }
    }

    private fun readLongPair(bytes: ByteArray, order: ByteOrder, tag: Int): IntArray {
        val b = ByteBuffer.wrap(bytes).order(order)
        assertEquals(42, b.getShort(2).toInt())
        val ifd = b.getInt(4)
        val count = b.getShort(ifd).toInt() and 65535
        val at = (0 until count).map { ifd + 2 + it * 12 }
            .single { (b.getShort(it).toInt() and 65535) == tag }
        assertEquals(4, b.getShort(at + 2).toInt())
        assertEquals(2, b.getInt(at + 4))
        val offset = b.getInt(at + 8)
        return intArrayOf(b.getInt(offset), b.getInt(offset + 4))
    }

    private fun fixture(order: ByteOrder): ByteArray {
        val bytes = ByteArray(480 + 100 * 80 * 2) { (it % 251).toByte() }
        val b = ByteBuffer.wrap(bytes).order(order)
        bytes[0] = if (order == ByteOrder.LITTLE_ENDIAN) 0x49 else 0x4d
        bytes[1] = bytes[0]
        b.putShort(2, 42)
        b.putInt(4, 8)
        val tags = listOf(256, 257, 258, 259, 262, 273, 277, 278, 279, 50706, 50719, 50720, 50829)
        b.putShort(8, tags.size.toShort())
        fun field(tag: Int, type: Int, count: Int, value: Int) {
            val at = 10 + tags.indexOf(tag) * 12
            b.putShort(at, tag.toShort())
            b.putShort(at + 2, type.toShort())
            b.putInt(at + 4, count)
            b.putInt(at + 8, value)
            if (type == 3 && count == 1) {
                b.putShort(at + 8, value.toShort())
                b.putShort(at + 10, 0)
            }
        }
        field(256, 4, 1, 100)
        field(257, 4, 1, 80)
        field(258, 3, 1, 16)
        field(259, 3, 1, 1)
        field(262, 3, 1, 32803)
        field(273, 4, 1, 480)
        field(277, 3, 1, 1)
        field(278, 4, 1, 80)
        field(279, 4, 1, 100 * 80 * 2)
        field(50706, 1, 4, 0)
        val version = 10 + tags.indexOf(50706) * 12 + 8
        bytes[version] = 1
        bytes[version + 1] = 4
        bytes[version + 2] = 0
        bytes[version + 3] = 0
        field(50719, 4, 2, 192)
        field(50720, 4, 2, 208)
        field(50829, 4, 4, 224)
        b.putInt(8 + 2 + tags.size * 12, 0)
        b.putInt(192, 0)
        b.putInt(196, 0)
        b.putInt(208, 96)
        b.putInt(212, 72)
        listOf(4, 2, 76, 98).forEachIndexed { i, value -> b.putInt(224 + i * 4, value) }
        return bytes
    }
}
