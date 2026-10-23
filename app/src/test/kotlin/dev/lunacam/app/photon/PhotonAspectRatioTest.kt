package dev.lunacam.app.photon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonAspectRatioTest {
    @Test fun originalPreservesAllPixelsAndPreferencesAreStable() {
        assertEquals(
            PhotonCrop(0, 0, 4081, 3061),
            PhotonAspectRatio.ORIGINAL.centerCrop(4081, 3061)
        )
        for (aspect in PhotonAspectRatio.entries) {
            assertEquals(aspect, PhotonAspectRatio.fromPreference(aspect.id))
            assertEquals(1f, aspect.previewRatio(true) * aspect.previewRatio(false), 0.000001f)
        }
        assertEquals(PhotonAspectRatio.ORIGINAL, PhotonAspectRatio.fromPreference(null))
        assertEquals(PhotonAspectRatio.ORIGINAL, PhotonAspectRatio.fromPreference("unknown"))
    }

    @Test fun exactRatiosAreLargestCenteredIntegerRectanglesInEitherOrientation() {
        for ((width, height) in listOf(
            4080 to 3060,
            3060 to 4080,
            4033 to 3025,
            512 to 384,
            400 to 400
        )) {
            for ((aspect, longSide, shortSide) in listOf(
                Triple(PhotonAspectRatio.WIDE, 16, 9),
                Triple(PhotonAspectRatio.CINEMA, 7, 3)
            )) {
                val crop = aspect.centerCrop(width, height)
                val rw = if (width >= height) longSide else shortSide
                val rh = if (width >= height) shortSide else longSide
                assertEquals(crop.width * rh, crop.height * rw)
                assertTrue(crop.width <= width && crop.height <= height)
                assertTrue(crop.width + rw > width || crop.height + rh > height)
                assertTrue(kotlin.math.abs(width - crop.width - 2 * crop.left) <= 1)
                assertTrue(kotlin.math.abs(height - crop.height - 2 * crop.top) <= 1)
            }
        }
        assertEquals(PhotonCrop(0, 48, 512, 288), PhotonAspectRatio.WIDE.centerCrop(512, 384))
        assertEquals(PhotonCrop(0, 82, 511, 219), PhotonAspectRatio.CINEMA.centerCrop(512, 384))
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleExactRatioCannotSilentlyPublishAnotherRatio() {
        PhotonAspectRatio.WIDE.centerCrop(8, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        PhotonAspectRatio.ORIGINAL.centerCrop(0, 4)
    }
}
