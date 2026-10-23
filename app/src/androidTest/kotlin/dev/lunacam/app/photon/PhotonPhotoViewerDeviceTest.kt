package dev.lunacam.app.photon

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata and captured Intents only. Never decodes photos or starts a real external activity. */
@RunWith(AndroidJUnit4::class)
class PhotonPhotoViewerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = Uri.parse("content://media/external_primary/images/media/123456789")

    @Test fun receiptOnlyAcceptsAnIndividualMediaImage() {
        assertEquals(uri, parseRecentPhotonPhoto("{\"output\":\"$uri\"}"))
        for (bad in listOf(
            "file:///sdcard/photo.jpg", "https://example.test/photo.jpg",
            "content://another.provider/external/images/media/1",
            "content://media/external/images/media", "content://media/external/video/media/1",
            "content://media/external/images/media/0", "content://media/external/images/media/-1",
            "content://media/external/images/media/1?query=x",
            "content://media/external/images/media/1#x"
        )) {
            assertNull(bad, parseRecentPhotonPhoto("{\"output\":\"$bad\"}"))
        }
        assertNull(parseRecentPhotonPhoto("not json"))
        assertNull(parseRecentPhotonPhoto("{}"))
    }

    @Test fun startupReadsOnlyBoundedSyntheticReceiptAndHandlesDeletion() = runBlocking {
        val directory = File(context.cacheDir, "viewer-${UUID.randomUUID()}").apply { mkdirs() }
        val receipt = File(directory, "photon-auto-last-save.json")
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        }
        try {
            assertNull(loadRecentPhotonPhoto(scoped))
            receipt.writeText("{\"output\":\"$uri\"}")
            assertEquals(uri, loadRecentPhotonPhoto(scoped))
            receipt.writeBytes(ByteArray(256 * 1024 + 1) { 32 })
            assertNull(loadRecentPhotonPhoto(scoped))
            receipt.writeText("{\"output\":")
            assertNull(loadRecentPhotonPhoto(scoped))
            assertTrue(receipt.delete())
            assertNull(loadRecentPhotonPhoto(scoped))
        } finally {
            receipt.delete()
            directory.delete()
        }
    }

    @Test fun directIntentGrantsOnlyOneSyntheticUriForReading() {
        val component = ComponentName("test.synthetic.viewer", "test.synthetic.viewer.Activity")
        val intent = photonImageViewIntent(uri, component)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(uri, intent.data)
        assertEquals(component, intent.component)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
    }

    @Test fun rawReceiptsKeepModesAndMimeTypesSeparate() = runBlocking {
        val jpeg = "{\"pipeline\":\"raw_auto_hdr_v2\",\"output\":\"$uri\"," +
            "\"mimeType\":\"image/jpeg\"}"
        val dng = "{\"outputs\":[\"$uri\"]}"
        assertEquals(RecentRawPhoto(uri, "image/jpeg"), parseRecentRawPhoto(jpeg, true))
        assertEquals(RecentRawPhoto(uri, "image/x-adobe-dng"), parseRecentRawPhoto(dng, false))
        assertNull(parseRecentRawPhoto(jpeg, false))
        assertNull(parseRecentRawPhoto(dng, true))
        assertNull(parseRecentRawPhoto(jpeg.replace("image/jpeg", "text/html"), true))
        assertNull(parseRecentRawPhoto(dng.replace(uri.toString(), "file:///private.dng"), false))
        val directory = File(context.cacheDir, "raw-viewer-${UUID.randomUUID()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        }
        val legacy = File(directory, "photon-raw-last-save.json")
        val alias = File(directory, "photon-raw-auto-last-save.json")
        try {
            legacy.writeText(jpeg)
            assertEquals(uri, loadRecentRawPhoto(scoped, true)?.uri)
            assertNull(loadRecentRawPhoto(scoped, false))
            alias.writeText(jpeg)
            legacy.writeText(dng)
            assertEquals("image/jpeg", loadRecentRawPhoto(scoped, true)?.mimeType)
            assertEquals("image/x-adobe-dng", loadRecentRawPhoto(scoped, false)?.mimeType)
            alias.writeBytes(ByteArray(256 * 1024 + 1))
            assertNull(loadRecentRawPhoto(scoped, true))
        } finally {
            alias.delete()
            legacy.delete()
            directory.delete()
        }
    }

    @Test fun dngIntentAndChooserKeepCorrectTypeWithoutLaunching() {
        val intent = photonImageViewIntent(uri, mimeType = "image/x-adobe-dng")
        assertEquals("image/x-adobe-dng", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        val fake = CapturingContext(context)
        try {
            PhotonPhotoViewer(fake).open(uri, "image/x-adobe-dng")
            val chooser = requireNotNull(fake.started)
            val target = requireNotNull(
                chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            )
            assertEquals("image/x-adobe-dng", target.type)
            assertEquals(uri, target.data)
            assertFalse(target.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        } finally {
            fake.clear()
        }
    }

    @Test fun missingPreferredAppFallsBackAndClearsPersistedSelectionWithoutLaunching() {
        val fake = CapturingContext(context)
        try {
            val viewer = PhotonPhotoViewer(fake)
            viewer.select(
                PhotonViewerApp(ComponentName("invalid.synthetic.viewer", "Missing"), "Synthetic")
            )
            assertEquals("Synthetic", PhotonPhotoViewer(fake).selectedName)
            viewer.open(uri)
            assertEquals("시스템 선택", viewer.selectedName)
            assertEquals("시스템 선택", PhotonPhotoViewer(fake).selectedName)
            val chooser = requireNotNull(fake.started)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val target =
                requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(uri, target.data)
            assertNull(target.component)
            assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
            fake.started = null
            viewer.open(Uri.parse("file:///invalid.jpg"))
            assertNull(fake.started)
        } finally {
            fake.clear()
        }
    }

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        private val preferenceName = "viewer-test-${UUID.randomUUID()}"
        var started: Intent? = null
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(preferenceName, mode)
        override fun startActivity(intent: Intent) {
            started = intent
        }
        fun clear() {
            deleteSharedPreferences(preferenceName)
        }
    }
}
