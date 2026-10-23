package dev.lunacam.processing.nativebridge

import android.graphics.Bitmap
import android.graphics.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Row-major [R, GreenEven, GreenOdd, B] gains over the RAW active-area pixel centers. */
data class SerNightShading(
    val columns: Int,
    val rows: Int,
    val gains: FloatArray,
    val activeArea: IntArray
) {
    internal fun validate(width: Int, height: Int) {
        require(columns in 1..64 && rows in 1..64)
        require(gains.size == columns * rows * 4)
        require(gains.all { it.isFinite() && it in 1f..16f })
        require(activeArea.size == 4 && activeArea[0] >= 0 && activeArea[1] >= 0)
        require(activeArea[2] <= width && activeArea[3] <= height)
        require(activeArea[2] > activeArea[0] && activeArea[3] > activeArea[1])
    }
}

/** Capture-result color metadata. Matrix is row-major sensor RGB to linear sRGB. */
data class SerNightColor(
    val cfa: Int,
    val blackLevels: FloatArray,
    val whiteLevel: Float,
    val whiteBalance: FloatArray,
    val sensorToSrgb: FloatArray,
    val shading: SerNightShading? = null
) {
    internal fun validated(): FloatArray {
        require(cfa in 0..3 && blackLevels.size == 4 && whiteBalance.size == 4)
        require(whiteLevel.isFinite() && whiteLevel in 64f..65535f)
        require(blackLevels.all { it.isFinite() && it >= 0f && whiteLevel - it >= 32f })
        require(whiteBalance.all { it.isFinite() && it > 0f && it <= 16f })
        require(
            sensorToSrgb.size == 9 &&
                sensorToSrgb.all { it.isFinite() && kotlin.math.abs(it) <= 16f }
        )
        val m = sensorToSrgb
        val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        require(determinant.isFinite() && kotlin.math.abs(determinant) >= 0.000001f)
        return blackLevels + floatArrayOf(whiteLevel) + whiteBalance + sensorToSrgb
    }
}

/** CPU reference renderer. No input mutation, rescaling, sharpening, or invented color metadata. */
object NativeSerRender {
    init {
        System.loadLibrary("lunacore")
    }

    fun render(width: Int, height: Int, packedPixels: ByteBuffer, color: SerNightColor): Bitmap =
        renderInternal(width, height, packedPixels, color, null)

    /**
     * Daytime global tone curve before 8-bit encoding. Uses common RGB scaling and a smooth
     * highlight shoulder; ignores packed Night EV lift. The noise floor is a heuristic guard,
     * not denoising or a calibrated sensor noise estimate. No recovery of clipped RAW is claimed.
     */
    fun renderNatural(
        width: Int,
        height: Int,
        packedPixels: ByteBuffer,
        color: SerNightColor,
        exposureGain: Float,
        shadowStrength: Float,
        noiseFloor: Float
    ): Bitmap {
        require(exposureGain.isFinite() && exposureGain in 1f..16f)
        require(shadowStrength.isFinite() && shadowStrength in 0f..1f)
        require(noiseFloor.isFinite() && noiseFloor in 0f..0.1f)
        return renderInternal(
            width,
            height,
            packedPixels,
            color,
            floatArrayOf(exposureGain, shadowStrength, noiseFloor)
        )
    }

    private fun renderInternal(
        width: Int,
        height: Int,
        packedPixels: ByteBuffer,
        color: SerNightColor,
        tone: FloatArray?
    ): Bitmap {
        require(width >= 4 && height >= 4 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 16_000_000)
        require(packedPixels.isDirect && packedPixels.order() == ByteOrder.nativeOrder())
        require(packedPixels.position() == 0 && packedPixels.remaining() == width * height * 4)
        val metadata = color.validated()
        val shading = color.shading
        shading?.validate(width, height)
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
            false,
            ColorSpace.get(ColorSpace.Named.SRGB)
        )
        try {
            check(
                if (tone == null) {
                    nativeRender(
                        width, height, packedPixels, color.cfa, metadata, bitmap,
                        shading?.gains, shading?.columns ?: 0, shading?.rows ?: 0,
                        shading?.activeArea ?: intArrayOf(0, 0, width, height)
                    )
                } else {
                    nativeRenderNatural(
                        width, height, packedPixels, color.cfa, metadata, bitmap,
                        shading?.gains, shading?.columns ?: 0, shading?.rows ?: 0,
                        shading?.activeArea ?: intArrayOf(0, 0, width, height),
                        tone[0], tone[1], tone[2]
                    )
                }
            ) {
                "ser_cpu_render_failed"
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private external fun nativeRender(
        width: Int,
        height: Int,
        packedPixels: ByteBuffer,
        cfa: Int,
        metadata: FloatArray,
        bitmap: Bitmap,
        shadingGains: FloatArray?,
        shadingColumns: Int,
        shadingRows: Int,
        shadingArea: IntArray
    ): Boolean

    private external fun nativeRenderNatural(
        width: Int,
        height: Int,
        packedPixels: ByteBuffer,
        cfa: Int,
        metadata: FloatArray,
        bitmap: Bitmap,
        shadingGains: FloatArray?,
        shadingColumns: Int,
        shadingRows: Int,
        shadingArea: IntArray,
        exposureGain: Float,
        shadowStrength: Float,
        noiseFloor: Float
    ): Boolean
}
