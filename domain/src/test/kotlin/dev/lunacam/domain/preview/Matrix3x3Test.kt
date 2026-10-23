package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Matrix3x3Test {
    @Test
    fun `identity maps a point to itself`() {
        val mapped = Matrix3x3.IDENTITY.mapPoint(PointD(12.5, -3.25))

        assertNotNull(mapped)
        assertEquals(12.5, mapped!!.x, EPSILON)
        assertEquals(-3.25, mapped.y, EPSILON)
    }

    @Test
    fun `composition applies the right operand first`() {
        val scaleThenTranslate = Matrix3x3.translate(10.0, 20.0) * Matrix3x3.scale(2.0, 3.0)

        val mapped = scaleThenTranslate.mapPoint(PointD(1.0, 1.0))

        assertNotNull(mapped)
        assertEquals(12.0, mapped!!.x, EPSILON)
        assertEquals(23.0, mapped.y, EPSILON)
    }

    @Test
    fun `invert undoes a scale and translate chain`() {
        val forward = Matrix3x3.translate(10.0, 20.0) * Matrix3x3.scale(2.0, 3.0)
        val inverse = forward.invert()

        assertNotNull(inverse)
        val roundTrip = inverse!!.mapPoint(forward.mapPoint(PointD(7.0, -4.0))!!)

        assertNotNull(roundTrip)
        assertEquals(7.0, roundTrip!!.x, EPSILON)
        assertEquals(-4.0, roundTrip.y, EPSILON)
    }

    @Test
    fun `invert returns null for a singular matrix`() {
        val collapsed = Matrix3x3.scale(0.0, 1.0)

        assertNull(collapsed.invert())
    }

    @Test
    fun `mapPoint returns null when the homogeneous divisor collapses`() {
        val degenerate = Matrix3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)

        assertNull(degenerate.mapPoint(PointD(1.0, 1.0)))
    }

    @Test
    fun `quarter turn clockwise moves the source top left to the destination top right`() {
        val rotate = Matrix3x3.rotateClockwiseQuarterTurns(1, 400.0, 200.0)

        val mapped = rotate.mapPoint(PointD(0.0, 0.0))

        assertNotNull(mapped)
        assertEquals(200.0, mapped!!.x, EPSILON)
        assertEquals(0.0, mapped.y, EPSILON)
    }

    @Test
    fun `three quarter turns clockwise moves the source top left to the bottom left`() {
        val rotate = Matrix3x3.rotateClockwiseQuarterTurns(3, 400.0, 200.0)

        val mapped = rotate.mapPoint(PointD(0.0, 0.0))

        assertNotNull(mapped)
        assertEquals(0.0, mapped!!.x, EPSILON)
        assertEquals(400.0, mapped.y, EPSILON)
    }

    @Test
    fun `half turn maps the source top left to the destination bottom right`() {
        val rotate = Matrix3x3.rotateClockwiseQuarterTurns(2, 400.0, 200.0)

        val mapped = rotate.mapPoint(PointD(0.0, 0.0))

        assertNotNull(mapped)
        assertEquals(400.0, mapped!!.x, EPSILON)
        assertEquals(200.0, mapped.y, EPSILON)
    }

    @Test
    fun `quarter turns wrap for negative and oversized inputs`() {
        val negative = Matrix3x3.rotateClockwiseQuarterTurns(-3, 400.0, 200.0)
        val oversized = Matrix3x3.rotateClockwiseQuarterTurns(5, 400.0, 200.0)
        val expected = Matrix3x3.rotateClockwiseQuarterTurns(1, 400.0, 200.0)

        assertEquals(expected, negative)
        assertEquals(expected, oversized)
    }

    @Test
    fun `mirror reflects across the vertical center`() {
        val mirror = Matrix3x3.mirrorHorizontal(400.0)

        val mapped = mirror.mapPoint(PointD(100.0, 50.0))

        assertNotNull(mapped)
        assertEquals(300.0, mapped!!.x, EPSILON)
        assertEquals(50.0, mapped.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
