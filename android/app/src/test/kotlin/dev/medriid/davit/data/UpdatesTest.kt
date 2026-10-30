package dev.medriid.davit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.2"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertFalse(Updates.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updates.isNewer("0.1.9", "0.2.0"))
        assertFalse(Updates.isNewer("0.2.0", "0.2.0-m1"))
    }

    @Test
    fun readsTheApkAndItsChecksum() {
        val r = Updates.parse(
            """{"tag_name":"v0.6.0","html_url":"https://github.com/medriid/davit/releases/tag/v0.6.0","assets":[
              {"name":"notes.txt","browser_download_url":"https://x/notes.txt","digest":"sha256:00"},
              {"name":"davit-0.6.0.apk","browser_download_url":"https://x/davit-0.6.0.apk","digest":"sha256:7b7b1b45"}]}""",
        )
        assertEquals("0.6.0", r.version)
        assertEquals("https://x/davit-0.6.0.apk", r.apk)
        assertEquals("7b7b1b45", r.sha256)
    }

    @Test
    fun aReleaseWithoutAnApkHasNothingToDownload() {
        val r = Updates.parse("""{"tag_name":"v0.6.0","html_url":"https://x","assets":[]}""")
        assertNull(r.apk)
        assertNull(r.sha256)
    }
}
