package dev.lunacam.app.photon

import android.media.MediaActionSound

internal interface ShutterSoundPlayer {
    fun load()
    fun play()
    fun release()
}

private class SystemShutterSound : ShutterSoundPlayer {
    private val sound = MediaActionSound()
    override fun load() = sound.load(MediaActionSound.SHUTTER_CLICK)
    override fun play() = sound.play(MediaActionSound.SHUTTER_CLICK)
    override fun release() = sound.release()
}

/** Always request the system camera sound; never substitute media volume or an app mute toggle. */
internal class PhotonShutterSound(
    private val createPlayer: () -> ShutterSoundPlayer = { SystemShutterSound() }
) : AutoCloseable {
    private var player: ShutterSoundPlayer? = null
    private var closed = false

    init {
        prepare()
    }

    private fun prepare() {
        if (closed || player != null) return
        var candidate: ShutterSoundPlayer? = null
        try {
            candidate = createPlayer()
            candidate.load()
            player = candidate
        } catch (_: Exception) {
            runCatching { candidate?.release() }
        }
    }

    fun play(): Boolean {
        if (closed) return false
        prepare()
        val active = player ?: return false
        return try {
            // One call per accepted user shutter, not per RAW/HDR constituent frame or save retry.
            active.play()
            true
        } catch (_: Exception) {
            runCatching { active.release() }
            player = null
            false
        }
    }

    override fun close() {
        closed = true
        runCatching { player?.release() }
        player = null
    }
}
