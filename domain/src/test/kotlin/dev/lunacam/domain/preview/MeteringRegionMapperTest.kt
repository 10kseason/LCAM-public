package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringRegionMapperTest {
    @Test
    fun `a center tap produces a centered sensor region`() {
        val region = map(PointD(540.0, 960.0))

        assertNotNull(region)
        assertEquals(IntRect(1775, 1275, 2225, 1725), region!!.rect)
        assertEquals(MeteringRegionMapper.MAX_WEIGHT, region.weight)
    }

    @Test
    fun `a corner tap is clamped inside the active array without shrinking`() {
        val region = map(PointD(0.0, 0.0))

        assertNotNull(region)
        val rect = region!!.rect
        assertEquals(450, rect.width)
        assertEquals(450, rect.height)
        assertTrue(rect.left >= ACTIVE_ARRAY.left)
        assertTrue(rect.top >= ACTIVE_ARRAY.top)
        assertTrue(rect.right <= ACTIVE_ARRAY.right)
        assertTrue(rect.bottom <= ACTIVE_ARRAY.bottom)
    }

    @Test
    fun `every view corner stays inside the active array`() {
        val corners =
            listOf(
                PointD(0.0, 0.0),
                PointD(1080.0, 0.0),
                PointD(0.0, 1920.0),
                PointD(1080.0, 1920.0)
            )

        for (corner in corners) {
            val region = map(corner)
            assertNotNull("corner=$corner", region)
            val rect = region!!.rect
            assertEquals("corner=$corner", rect, rect.intersect(ACTIVE_ARRAY))
        }
    }

    @Test
    fun `a tap on the letterbox bar is ignored rather than clamped`() {
        val letterboxed =
            requireNotNull(
                PreviewTransformFactory.create(
                    activeArray = ACTIVE_ARRAY,
                    bufferSize = IntSize(1920, 1080),
                    viewSize = IntSize(1080, 2400),
                    sensorOrientationDegrees = 90,
                    displayRotationDegrees = 0,
                    mirrored = false
                )
            )
        // The image occupies y in [240, 2160]; everything else is a black bar.
        assertEquals(IntRect(0, 240, 1080, 2160), letterboxed.displayedViewRect)

        fun mapAt(tap: PointD) = MeteringRegionMapper.map(
            transform = letterboxed,
            viewSize = IntSize(1080, 2400),
            tap = tap,
            activeArray = ACTIVE_ARRAY
        )

        assertNull(mapAt(PointD(540.0, 100.0)))
        assertNull(mapAt(PointD(540.0, 2300.0)))
        assertNotNull(mapAt(PointD(540.0, 1200.0)))
    }

    @Test
    fun `a tap outside the view is ignored`() {
        assertNull(map(PointD(-1.0, 500.0)))
        assertNull(map(PointD(500.0, -1.0)))
        assertNull(map(PointD(1081.0, 500.0)))
        assertNull(map(PointD(500.0, 1921.0)))
    }

    @Test
    fun `a non finite tap is ignored`() {
        assertNull(map(PointD(Double.NaN, 500.0)))
        assertNull(map(PointD(500.0, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `a tap before layout is ignored`() {
        val region =
            MeteringRegionMapper.map(
                transform = transform(),
                viewSize = IntSize(0, 0),
                tap = PointD(0.0, 0.0),
                activeArray = ACTIVE_ARRAY
            )

        assertNull(region)
    }

    @Test
    fun `a tiny visible crop still produces at least the minimum region`() {
        val region =
            MeteringRegionMapper.map(
                transform = transform(),
                viewSize = VIEW_SIZE,
                tap = PointD(540.0, 960.0),
                activeArray = ACTIVE_ARRAY,
                regionFraction = 0.0001
            )

        assertNotNull(region)
        assertEquals(MeteringRegionMapper.MIN_REGION_PIXELS, region!!.rect.width)
        assertEquals(MeteringRegionMapper.MIN_REGION_PIXELS, region.rect.height)
    }

    @Test
    fun `a region larger than the active array is capped to it`() {
        val region =
            MeteringRegionMapper.map(
                transform = transform(),
                viewSize = VIEW_SIZE,
                tap = PointD(540.0, 960.0),
                activeArray = ACTIVE_ARRAY,
                regionFraction = 10.0
            )

        assertNotNull(region)
        assertEquals(3000, region!!.rect.width)
        assertEquals(3000, region.rect.height)
        assertEquals(region.rect, region.rect.intersect(ACTIVE_ARRAY))
    }

    private fun map(tap: PointD): MeteringRegion? = MeteringRegionMapper.map(
        transform = transform(),
        viewSize = VIEW_SIZE,
        tap = tap,
        activeArray = ACTIVE_ARRAY
    )

    private fun transform(): PreviewTransform = requireNotNull(
        PreviewTransformFactory.create(
            activeArray = ACTIVE_ARRAY,
            bufferSize = IntSize(1920, 1080),
            viewSize = VIEW_SIZE,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            mirrored = false
        )
    )

    private companion object {
        val ACTIVE_ARRAY = IntRect(0, 0, 4000, 3000)
        val VIEW_SIZE = IntSize(1080, 1920)
    }
}
