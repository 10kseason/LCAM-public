package dev.lunacam.app.photon

import android.media.MediaActionSound
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Plays one system shutter click. No capture, microphone, screenshots or photo access. */
@RunWith(AndroidJUnit4::class)
class PhotonShutterSoundDeviceTest {
    @Test fun systemSoundAcceptsOneClickWithoutTakingAPhoto() {
        PhotonShutterSound().use { sound ->
            SystemClock.sleep(300)
            assertTrue("System shutter sound request must succeed", sound.play())
            SystemClock.sleep(500)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "systemShutterRequired",
                    MediaActionSound.mustPlayShutterSound().toString()
                )
                putString("acousticMeasurement", "not_measured")
            }
        )
    }
}
