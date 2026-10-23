package dev.lunacam.processing.nativebridge

import android.graphics.Bitmap

/** Same pixel decisions as Texture Guard 0.1, with one JNI call and three cached source rows. */
object NativeTextureGuard {
    init {
        System.loadLibrary("lunacore")
    }

    /** Mutates only opaque interior pixels; returns the number of changed pixels. */
    fun process(bitmap: Bitmap): Long {
        require(!bitmap.isRecycled && bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        require(bitmap.width.toLong() * bitmap.height <= 16_000_000L)
        return nativeProcess(bitmap)
    }

    private external fun nativeProcess(bitmap: Bitmap): Long
}
