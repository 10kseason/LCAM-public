package dev.lunacam.processing.nativebridge

import android.graphics.Bitmap

/** Opt-in Detail Guard 0.1 pass after TextureGuard. Exact integer decisions, no new exposure. */
object NativeDetailGuard {
    init {
        System.loadLibrary("lunacore")
    }

    /** Mutates the caller's bitmap; returns denoised + sharpened changed pixel count. */
    fun process(bitmap: Bitmap): Long {
        require(!bitmap.isRecycled && bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        require(bitmap.width.toLong() * bitmap.height <= 16_000_000L)
        return nativeProcess(bitmap)
    }

    private external fun nativeProcess(bitmap: Bitmap): Long
}
