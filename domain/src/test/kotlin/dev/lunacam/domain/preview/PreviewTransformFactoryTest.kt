package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformFactoryTest {
    @Test
    fun `back camera at ninety degrees maps the sensor center to the view center`() {
        val transform = backPortraitTransform()

        val center = transform.mapSensorToView(PointD(2000.0, 1500.0))

        assertNotNull(center)
        assertEquals(540.0, center!!.x, EPSILON)
        assertEquals(960.0, center.y, EPSILON)
    }

    @Test
    fun `back camera at ninety degrees moves the sensor origin to the view top right`() {
        val transform = backPortraitTransform()

        val origin = transform.mapSensorToView(PointD(0.0, 0.0))

        assertNotNull(origin)
        assertEquals(1080.0, origin!!.x, EPSILON)
        assertEquals(0.0, origin.y, EPSILON)
    }

    @Test
    fun `a matching aspect ratio fills the view with no letterbox`() {
        val transform = backPortraitTransform()

        assertEquals(IntRect(0, 0, 4000, 3000), transform.visibleSensorRect)
        assertEquals(IntRect(0, 0, 1080, 1920), transform.displayedViewRect)
    }

    @Test
    fun `a taller view letterboxes instead of cropping or stretching`() {
        val transform =
            PreviewTransformFactory.create(
                activeArray = ACTIVE_ARRAY,
                bufferSize = IntSize(1920, 1080),
                viewSize = IntSize(1080, 2400),
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 0,
                mirrored = false
            )

        assertNotNull(transform)
        // Nothing is cropped away: the viewfinder frames exactly what a capture would record.
        assertEquals(ACTIVE_ARRAY, transform!!.visibleSensorRect)
        // The image is centered, leaving an equal bar above and below.
        assertEquals(IntRect(0, 240, 1080, 2160), transform.displayedViewRect)

        val center = transform.mapSensorToView(PointD(2000.0, 1500.0))
        assertNotNull(center)
        assertEquals(540.0, center!!.x, EPSILON)
        assertEquals(1200.0, center.y, EPSILON)
    }

    @Test
    fun `the displayed rect keeps the upright buffer ratio`() {
        val transform =
            PreviewTransformFactory.create(
                activeArray = ACTIVE_ARRAY,
                bufferSize = IntSize(1440, 1080),
                viewSize = IntSize(1080, 2400),
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 0,
                mirrored = false
            )

        assertNotNull(transform)
        // Upright buffer is 1080x1440, fitted to the 1080 wide view at scale 1.0.
        assertEquals(IntRect(0, 480, 1080, 1920), transform!!.displayedViewRect)
        assertEquals(1080.0 / 1440.0, transform.displayAspectRatio, EPSILON)
    }

    @Test
    fun `a wider view letterboxes on the sides`() {
        val transform =
            PreviewTransformFactory.create(
                activeArray = ACTIVE_ARRAY,
                bufferSize = IntSize(1920, 1080),
                viewSize = IntSize(2400, 1080),
                sensorOrientationDegrees = 0,
                displayRotationDegrees = 0,
                mirrored = false
            )

        assertNotNull(transform)
        assertEquals(ACTIVE_ARRAY, transform!!.visibleSensorRect)
        assertEquals(IntRect(240, 0, 2160, 1080), transform.displayedViewRect)
    }

    @Test
    fun `mirroring reflects the view x axis for a front camera`() {
        val notMirrored = identityScaleTransform(mirrored = false)
        val mirrored = identityScaleTransform(mirrored = true)

        val plain = notMirrored.mapSensorToView(PointD(100.0, 200.0))
        val flipped = mirrored.mapSensorToView(PointD(100.0, 200.0))

        assertNotNull(plain)
        assertNotNull(flipped)
        assertEquals(100.0, plain!!.x, EPSILON)
        assertEquals(200.0, plain.y, EPSILON)
        assertEquals(1820.0, flipped!!.x, EPSILON)
        assertEquals(200.0, flipped.y, EPSILON)
    }

    @Test
    fun `half turn maps the sensor origin to the opposite view corner`() {
        val transform =
            PreviewTransformFactory.create(
                activeArray = IntRect(0, 0, 1920, 1080),
                bufferSize = IntSize(1920, 1080),
                viewSize = IntSize(1920, 1080),
                sensorOrientationDegrees = 180,
                displayRotationDegrees = 0,
                mirrored = false
            )

        assertNotNull(transform)
        val mapped = transform!!.mapSensorToView(PointD(100.0, 200.0))
        assertNotNull(mapped)
        assertEquals(1820.0, mapped!!.x, EPSILON)
        assertEquals(880.0, mapped.y, EPSILON)
    }

    @Test
    fun `every rotation and mirror combination round trips through both matrices`() {
        val probes =
            listOf(
                PointD(0.0, 0.0),
                PointD(2000.0, 1500.0),
                PointD(3999.0, 2999.0),
                PointD(137.0, 2400.0)
            )

        for (sensorOrientation in ROTATIONS) {
            for (displayRotation in ROTATIONS) {
                for (mirrored in listOf(false, true)) {
                    val transform =
                        PreviewTransformFactory.create(
                            activeArray = ACTIVE_ARRAY,
                            bufferSize = IntSize(1920, 1080),
                            viewSize = IntSize(1080, 2400),
                            sensorOrientationDegrees = sensorOrientation,
                            displayRotationDegrees = displayRotation,
                            mirrored = mirrored
                        )
                    val label = "sensor=$sensorOrientation display=$displayRotation " +
                        "mirrored=$mirrored"
                    assertNotNull(label, transform)

                    for (probe in probes) {
                        val view = transform!!.mapSensorToView(probe)
                        assertNotNull(label, view)
                        val back = transform.mapViewToSensor(view!!)
                        assertNotNull(label, back)
                        assertEquals(label, probe.x, back!!.x, ROUND_TRIP_EPSILON)
                        assertEquals(label, probe.y, back.y, ROUND_TRIP_EPSILON)
                    }
                }
            }
        }
    }

    @Test
    fun `the whole active array stays visible for every rotation`() {
        for (sensorOrientation in ROTATIONS) {
            for (displayRotation in ROTATIONS) {
                val transform =
                    PreviewTransformFactory.create(
                        activeArray = ACTIVE_ARRAY,
                        bufferSize = IntSize(1920, 1080),
                        viewSize = IntSize(1440, 2960),
                        sensorOrientationDegrees = sensorOrientation,
                        displayRotationDegrees = displayRotation,
                        mirrored = false
                    )
                val label = "sensor=$sensorOrientation display=$displayRotation"
                assertNotNull(label, transform)
                // Letterboxing never discards sensor area, so the visible rect is the whole array.
                assertEquals(label, ACTIVE_ARRAY, transform!!.visibleSensorRect)
                assertTrue(label, transform.displayedViewRect.isPositive)
            }
        }
    }

    @Test
    fun `the displayed rect never exceeds the view`() {
        val viewSize = IntSize(1440, 2960)
        for (sensorOrientation in ROTATIONS) {
            for (displayRotation in ROTATIONS) {
                val transform =
                    requireNotNull(
                        PreviewTransformFactory.create(
                            activeArray = ACTIVE_ARRAY,
                            bufferSize = IntSize(1920, 1080),
                            viewSize = viewSize,
                            sensorOrientationDegrees = sensorOrientation,
                            displayRotationDegrees = displayRotation,
                            mirrored = false
                        )
                    )
                val displayed = transform.displayedViewRect
                val label = "sensor=$sensorOrientation display=$displayRotation"
                assertEquals(label, displayed, displayed.intersect(IntRect.ofSize(viewSize)))
            }
        }
    }

    @Test
    fun `a zero sized surface yields no transform`() {
        assertNull(create(viewSize = IntSize(0, 0)))
        assertNull(create(bufferSize = IntSize(0, 1080)))
        assertNull(create(activeArray = IntRect(0, 0, 0, 0)))
    }

    @Test
    fun `a rotation that is not a quarter turn yields no transform`() {
        assertNull(create(sensorOrientationDegrees = 45))
        assertNull(create(displayRotationDegrees = 100))
    }

    @Test
    fun `negative rotations normalize to the same transform as their positive form`() {
        val negative = create(sensorOrientationDegrees = -90)
        val positive = create(sensorOrientationDegrees = 270)

        assertNotNull(negative)
        assertEquals(positive, negative)
    }

    private fun backPortraitTransform(): PreviewTransform = requireNotNull(create())

    private fun identityScaleTransform(mirrored: Boolean): PreviewTransform = requireNotNull(
        PreviewTransformFactory.create(
            activeArray = IntRect(0, 0, 1920, 1080),
            bufferSize = IntSize(1920, 1080),
            viewSize = IntSize(1920, 1080),
            sensorOrientationDegrees = 0,
            displayRotationDegrees = 0,
            mirrored = mirrored
        )
    )

    private fun create(
        activeArray: IntRect = ACTIVE_ARRAY,
        bufferSize: IntSize = IntSize(1920, 1080),
        viewSize: IntSize = IntSize(1080, 1920),
        sensorOrientationDegrees: Int = 90,
        displayRotationDegrees: Int = 0,
        mirrored: Boolean = false
    ): PreviewTransform? = PreviewTransformFactory.create(
        activeArray = activeArray,
        bufferSize = bufferSize,
        viewSize = viewSize,
        sensorOrientationDegrees = sensorOrientationDegrees,
        displayRotationDegrees = displayRotationDegrees,
        mirrored = mirrored
    )

    private companion object {
        val ACTIVE_ARRAY = IntRect(0, 0, 4000, 3000)
        val ROTATIONS = listOf(0, 90, 180, 270)
        const val EPSILON = 1e-9
        const val ROUND_TRIP_EPSILON = 1e-6
    }
}
