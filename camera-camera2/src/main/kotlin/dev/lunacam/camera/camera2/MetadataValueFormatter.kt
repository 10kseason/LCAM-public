package dev.lunacam.camera.camera2

import android.graphics.Rect
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF

internal object MetadataValueFormatter {
    fun format(value: Any?): String? = when (value) {
        null -> null
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> "byte[${value.size}]"
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { format(it) ?: "null" }
        is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") {
            format(it)
                ?: "null"
        }
        is Size -> "${value.width}x${value.height}"
        is SizeF -> "${value.width}x${value.height}mm"
        is Rect -> "[${value.left},${value.top},${value.right},${value.bottom}]"
        is Range<*> -> "[${format(value.lower)},${format(value.upper)}]"
        is Rational -> "${value.numerator}/${value.denominator}"
        is StreamConfigurationMap ->
            "StreamConfigurationMap(outputFormats=${value.outputFormats.sorted().joinToString()})"
        else -> value.toString()
    }?.take(MAX_VALUE_LENGTH)

    private const val MAX_VALUE_LENGTH = 4_096
}
