package dev.lunacam.domain.preview

import kotlin.math.abs

/**
 * Row-major 3x3 matrix for the 2D mappings between sensor active array, preview buffer,
 * and view coordinates described in DESIGN.md section 9.1.
 *
 * Points are column vectors, so `a * b` applies `b` first and then `a`.
 * Constructor arguments are row-major: `m00 m01 m02 / m10 m11 m12 / m20 m21 m22`.
 */
data class Matrix3x3(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double
) {
    operator fun times(other: Matrix3x3): Matrix3x3 = Matrix3x3(
        m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
        m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
        m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
        m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
        m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
        m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
        m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
        m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
        m20 * other.m02 + m21 * other.m12 + m22 * other.m22
    )

    /** Returns null for a degenerate mapping instead of producing a misleading coordinate. */
    fun mapPoint(point: PointD): PointD? {
        val w = m20 * point.x + m21 * point.y + m22
        if (!w.isFinite() || abs(w) < EPSILON) return null
        val x = (m00 * point.x + m01 * point.y + m02) / w
        val y = (m10 * point.x + m11 * point.y + m12) / w
        if (!x.isFinite() || !y.isFinite()) return null
        return PointD(x, y)
    }

    /** Returns null when the matrix is singular, which callers treat as "ignore this tap". */
    fun invert(): Matrix3x3? {
        val c00 = m11 * m22 - m12 * m21
        val c01 = m12 * m20 - m10 * m22
        val c02 = m10 * m21 - m11 * m20
        val determinant = m00 * c00 + m01 * c01 + m02 * c02
        if (!determinant.isFinite() || abs(determinant) < EPSILON) return null

        val c10 = m02 * m21 - m01 * m22
        val c11 = m00 * m22 - m02 * m20
        val c12 = m01 * m20 - m00 * m21
        val c20 = m01 * m12 - m02 * m11
        val c21 = m02 * m10 - m00 * m12
        val c22 = m00 * m11 - m01 * m10

        return Matrix3x3(
            c00 / determinant,
            c10 / determinant,
            c20 / determinant,
            c01 / determinant,
            c11 / determinant,
            c21 / determinant,
            c02 / determinant,
            c12 / determinant,
            c22 / determinant
        )
    }

    companion object {
        const val EPSILON = 1e-9

        val IDENTITY = Matrix3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun translate(tx: Double, ty: Double): Matrix3x3 =
            Matrix3x3(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0)

        fun scale(sx: Double, sy: Double): Matrix3x3 =
            Matrix3x3(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0)

        /**
         * Rotates image content clockwise by [quarterTurns] quarter turns in a y-down space and
         * translates the result back into `[0, outWidth] x [0, outHeight]`.
         */
        fun rotateClockwiseQuarterTurns(
            quarterTurns: Int,
            sourceWidth: Double,
            sourceHeight: Double
        ): Matrix3x3 = when (((quarterTurns % 4) + 4) % 4) {
            1 -> Matrix3x3(0.0, -1.0, sourceHeight, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            2 -> Matrix3x3(-1.0, 0.0, sourceWidth, 0.0, -1.0, sourceHeight, 0.0, 0.0, 1.0)
            3 -> Matrix3x3(0.0, 1.0, 0.0, -1.0, 0.0, sourceWidth, 0.0, 0.0, 1.0)
            else -> IDENTITY
        }

        /** Mirrors horizontally inside `[0, width]`, used only for front-facing preview. */
        fun mirrorHorizontal(width: Double): Matrix3x3 =
            Matrix3x3(-1.0, 0.0, width, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}
