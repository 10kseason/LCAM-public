package dev.lunacam.processing.nativebridge

import dev.lunacam.processing.api.LunaCoreVersionProvider

class NativeLunaCoreVersionProvider : LunaCoreVersionProvider {
    override fun version(): String = nativeVersion()

    private external fun nativeVersion(): String

    private companion object {
        init {
            System.loadLibrary("lunacore")
        }
    }
}
