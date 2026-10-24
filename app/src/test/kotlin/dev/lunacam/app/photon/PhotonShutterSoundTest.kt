package dev.lunacam.app.photon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonShutterSoundTest {
    private class Player : ShutterSoundPlayer {
        var loads = 0
        var plays = 0
        var releases = 0
        var failLoad = false
        var failPlay = false
        override fun load() {
            loads++
            check(!failLoad)
        }
        override fun play() {
            plays++
            check(!failPlay)
        }
        override fun release() {
            releases++
        }
    }

    @Test fun preloadsWithoutNoiseAndEachAcceptedRequestPlaysOnce() {
        val player = Player()
        val sound = PhotonShutterSound { player }
        assertEquals(1, player.loads)
        assertEquals(0, player.plays)
        repeat(3) { assertTrue(sound.play()) }
        assertEquals(3, player.plays)
        assertEquals(1, player.loads)
        sound.close()
        sound.close()
        assertEquals(1, player.releases)
        assertFalse(sound.play())
        assertEquals(3, player.plays)
    }

    @Test fun loadFailureReportsFailureAndReleasesCandidate() {
        val player = Player().apply { failLoad = true }
        val sound = PhotonShutterSound { player }
        assertFalse(sound.play())
        assertEquals(0, player.plays)
        assertEquals(player.loads, player.releases)
        player.failLoad = false
        assertTrue(sound.play())
        assertEquals(1, player.plays)
        sound.close()
    }

    @Test fun playbackFailureCannotBeReportedAsSuccessfulOrLeakPlayer() {
        val failed = Player().apply { failPlay = true }
        val retry = Player()
        var created = 0
        val sound = PhotonShutterSound { if (created++ == 0) failed else retry }
        assertFalse(sound.play())
        assertEquals(1, failed.releases)
        assertTrue(sound.play())
        assertEquals(1, retry.loads)
        assertEquals(1, retry.plays)
        sound.close()
        assertEquals(1, retry.releases)
    }
}
