package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewSizeSelectorTest {
    @Test
    fun `a four by three sensor selects the largest four by three candidate`() {
        val selection = PreviewSizeSelector.select(CANDIDATES, FOUR_BY_THREE)

        assertEquals(PreviewSizeSelection(IntSize(1440, 1080), PreviewSizeTier.FULL_HD), selection)
    }

    @Test
    fun `a sixteen by nine sensor selects the largest sixteen by nine candidate`() {
        val selection = PreviewSizeSelector.select(CANDIDATES, SIXTEEN_BY_NINE)

        assertEquals(PreviewSizeSelection(IntSize(1920, 1080), PreviewSizeTier.FULL_HD), selection)
    }

    /**
     * Regression for the defect found on the S25 Ultra: selecting against the 2.167 screen ratio
     * picked 1920x824, a 2.33:1 readout that discards 43 percent of a 4:3 sensor's field of view.
     */
    @Test
    fun `the real device candidate list never selects the ultra wide readout`() {
        val selection = PreviewSizeSelector.select(S25_ULTRA_PREVIEW_SIZES, S25_ULTRA_SENSOR)

        assertEquals(PreviewSizeSelection(IntSize(1440, 1080), PreviewSizeTier.FULL_HD), selection)
        assertNotEquals(IntSize(1920, 824), selection?.size)
    }

    @Test
    fun `starting at the HD rung honours the smaller bound`() {
        val selection =
            PreviewSizeSelector.select(CANDIDATES, FOUR_BY_THREE, PreviewSizeTier.HD)

        assertEquals(PreviewSizeSelection(IntSize(640, 480), PreviewSizeTier.HD), selection)
    }

    @Test
    fun `candidates above every bound fall back to the smallest acceptable size`() {
        val oversized = listOf(IntSize(4032, 3024), IntSize(2560, 1440))

        val selection = PreviewSizeSelector.select(oversized, FOUR_BY_THREE)

        assertEquals(
            PreviewSizeSelection(IntSize(2560, 1440), PreviewSizeTier.SMALLEST),
            selection
        )
    }

    @Test
    fun `a candidate whose short edge exceeds the bound is not accepted`() {
        val nearMiss = listOf(IntSize(1600, 1200))

        val selection = PreviewSizeSelector.select(nearMiss, FOUR_BY_THREE)

        assertEquals(
            PreviewSizeSelection(IntSize(1600, 1200), PreviewSizeTier.SMALLEST),
            selection
        )
    }

    @Test
    fun `degenerate candidates are ignored`() {
        val withJunk = listOf(IntSize(0, 0), IntSize(-1, 720), IntSize(1280, 720))

        val selection = PreviewSizeSelector.select(withJunk, SIXTEEN_BY_NINE)

        assertEquals(PreviewSizeSelection(IntSize(1280, 720), PreviewSizeTier.FULL_HD), selection)
    }

    @Test
    fun `an empty candidate list yields no selection`() {
        assertNull(PreviewSizeSelector.select(emptyList(), FOUR_BY_THREE))
        assertNull(PreviewSizeSelector.select(listOf(IntSize(0, 0)), FOUR_BY_THREE))
    }

    @Test
    fun `a degenerate target ratio still returns a usable size`() {
        assertEquals(
            PreviewSizeSelection(IntSize(1920, 1080), PreviewSizeTier.FULL_HD),
            PreviewSizeSelector.select(CANDIDATES, 0.0)
        )
        assertEquals(
            PreviewSizeSelection(IntSize(1920, 1080), PreviewSizeTier.FULL_HD),
            PreviewSizeSelector.select(CANDIDATES, Double.NaN)
        )
    }

    @Test
    fun `the active array ratio is read as a long over short edge ratio`() {
        assertEquals(
            4.0 / 3.0,
            PreviewSizeSelector.normalizedAspectRatioOf(IntRect(0, 0, 4080, 3060)),
            1e-9
        )
    }

    @Test
    fun `tier descent stops after the smallest rung`() {
        assertEquals(PreviewSizeTier.HD, PreviewSizeSelector.nextTier(PreviewSizeTier.FULL_HD))
        assertEquals(PreviewSizeTier.SMALLEST, PreviewSizeSelector.nextTier(PreviewSizeTier.HD))
        assertNull(PreviewSizeSelector.nextTier(PreviewSizeTier.SMALLEST))
    }

    private companion object {
        const val FOUR_BY_THREE = 4.0 / 3.0
        const val SIXTEEN_BY_NINE = 16.0 / 9.0

        /** 4080x3060 active array of the S25 Ultra main back camera. */
        const val S25_ULTRA_SENSOR = 4080.0 / 3060.0

        val CANDIDATES =
            listOf(
                IntSize(4032, 3024),
                IntSize(1920, 1080),
                IntSize(1440, 1080),
                IntSize(1280, 720),
                IntSize(640, 480),
                IntSize(320, 240)
            )

        /** PRIVATE preview sizes from the 2026-09-21 S25 Ultra capability report. */
        val S25_ULTRA_PREVIEW_SIZES =
            listOf(
                IntSize(4080, 3060),
                IntSize(4032, 3024),
                IntSize(4000, 3000),
                IntSize(3840, 2160),
                IntSize(2560, 1440),
                IntSize(2400, 1080),
                IntSize(2336, 1080),
                IntSize(1920, 1440),
                IntSize(1920, 1080),
                IntSize(1920, 824),
                IntSize(1440, 1080),
                IntSize(1232, 1008),
                IntSize(1088, 1088),
                IntSize(1280, 720),
                IntSize(960, 720),
                IntSize(720, 480),
                IntSize(640, 480),
                IntSize(640, 360),
                IntSize(352, 288),
                IntSize(320, 240),
                IntSize(176, 144)
            )
    }
}
