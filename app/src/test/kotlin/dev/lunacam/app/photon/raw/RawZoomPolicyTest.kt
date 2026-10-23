package dev.lunacam.app.photon.raw

import dev.lunacam.app.photon.PhotonAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawZoomPolicyTest {
    private val ratios = listOf(0.6f, 1f, 3f, 5f)

    @Test
    fun aspectAndZoomPreserveExactRatioBayerPhaseAndCenter() {
        for ((aspect, sides) in listOf(
            PhotonAspectRatio.WIDE to (16 to 9),
            PhotonAspectRatio.CINEMA to (7 to 3)
        )) {
            for (zoom in listOf(1f, 1.4f, 3f, 8f)) {
                val crop = RawZoomPolicy.crop(3, 7, 4083, 3067, zoom, aspect)
                assertEquals(crop.width * sides.second, crop.height * sides.first)
                assertTrue(listOf(crop.left, crop.top, crop.width, crop.height).all { it % 2 == 0 })
                assertTrue(
                    crop.left >= 3 && crop.top >= 7 && crop.right <= 4083 && crop.bottom <= 3067
                )
                assertTrue(kotlin.math.abs(crop.left + crop.right - 4086) <= 2)
                assertTrue(kotlin.math.abs(crop.top + crop.bottom - 3074) <= 2)
                assertTrue(crop.width <= 4080 / zoom && crop.height <= 3060 / zoom)
            }
        }
        val portrait = RawZoomPolicy.crop(0, 0, 3060, 4080, 2f, PhotonAspectRatio.WIDE)
        assertEquals(portrait.width * 16, portrait.height * 9)
        assertEquals(PhotonAspectRatio.ORIGINAL, PhotonAspectRatio.fromPreference("unknown"))
    }

    @Test
    fun zoomUsesWiderSensorUntilNextLensIsPhysicallyAchievable() {
        val below = RawZoomPolicy.select(ratios, 0, 0.95f)
        assertEquals(0, below.index)
        assertEquals(0.95f / 0.6f, below.digitalRatio, 0.0001f)
        assertEquals(0, RawZoomPolicy.select(ratios, 0, 1.02f).index)
        assertEquals(1, RawZoomPolicy.select(ratios, 0, 1.05f).index)
        assertEquals(1, RawZoomPolicy.select(ratios, 1, 2.9f).index)
        assertEquals(2, RawZoomPolicy.select(ratios, 1, 3.13f).index)
    }

    @Test
    fun zoomOutNeverPretendsNarrowLensCanZoomOut() {
        val result = RawZoomPolicy.select(ratios, 3, 2.99f)
        assertEquals(1, result.index)
        assertEquals(2.99f, result.digitalRatio, 0f)
        var selected = 0
        (6..80).map { it / 10f }.plus((6..80).reversed().map { it / 10f }).forEach { requested ->
            val selection = RawZoomPolicy.select(ratios, selected, requested)
            selected = selection.index
            assertTrue(selection.digitalRatio >= 1f)
            assertEquals(requested, ratios[selected] * selection.digitalRatio, 0.00001f)
        }
    }

    @Test
    fun boundsRespectAvailableSensorsAndEightTimesCeiling() {
        assertEquals(0.6f, RawZoomPolicy.select(ratios, 1, 0.1f).requestedRatio, 0f)
        assertEquals(1f, RawZoomPolicy.select(listOf(1f, 3f), 0, 0.6f).requestedRatio, 0f)
        val maximum = RawZoomPolicy.select(ratios, 0, 100f)
        assertEquals(3, maximum.index)
        assertEquals(8f, maximum.requestedRatio, 0f)
        assertEquals(1.6f, maximum.digitalRatio, 0f)
        assertEquals(1f, RawZoomPolicy.select(ratios, 1, Float.NaN).requestedRatio, 0f)
        assertThrows(IllegalArgumentException::class.java) {
            RawZoomPolicy.select(listOf(1f, 0.6f), 0, 1f)
        }
    }

    @Test
    fun cropsAreCenteredEvenAndWithinPhysicalActiveArea() {
        for (ratio in listOf(1f, 1.1f, 1.6f, 3f, 8f)) {
            val crop = RawZoomPolicy.crop(3, 7, 4083, 3067, ratio)
            assertTrue(crop.left >= 3 && crop.top >= 7 && crop.right <= 4083 && crop.bottom <= 3067)
            assertTrue(listOf(crop.left, crop.top, crop.width, crop.height).all { it % 2 == 0 })
            assertTrue(kotlin.math.abs(crop.left + crop.right - 4086) <= 2)
            assertTrue(kotlin.math.abs(crop.top + crop.bottom - 3074) <= 2)
        }
        assertEquals(RawZoomCrop(1020, 764, 3060, 2294), RawZoomPolicy.crop(0, 0, 4080, 3060, 2f))
        assertEquals(RawZoomCrop(0, 0, 4080, 3060), RawZoomPolicy.crop(0, 0, 4080, 3060, 1f))
        assertThrows(IllegalArgumentException::class.java) {
            RawZoomPolicy.crop(0, 0, 4080, 3060, 0.9f)
        }
    }
}
