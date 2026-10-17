package dev.optimizer.android

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    @Test fun versions() {
        assertTrue(AppVersions.isNewer("1.0.7", "1.0.6"))
        assertTrue(AppVersions.isNewer("v0.5.16", "0.5.15"))
        assertTrue(AppVersions.isNewer("1.10.0", "1.9.9"))
        assertFalse(AppVersions.isNewer("1.0.6", "1.0.6"))
        assertFalse(AppVersions.isNewer("1.0.5", "1.0.6"))
        assertEquals(listOf(0, 1, 4), AppVersions.parse("v0.1.4-debug"))
    }

    private val rx = Regex("^AndroidOptimizer-standard-release\\.apk$")
    private fun rel(draft: Boolean = false, pre: Boolean = false, name: String = "AndroidOptimizer-standard-release.apk") =
        """{"tag_name":"v9.9.9","html_url":"https://github.com/x/y/releases/tag/v9.9.9","draft":$draft,"prerelease":$pre,
           "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":1},
                     {"name":"$name","browser_download_url":"https://github.com/x/y/releases/download/v9.9.9/$name","size":1234}]}"""

    @Test fun parsesMatchingApk() {
        val i = AppReleaseParser.parse(rel(), rx)!!
        assertEquals("9.9.9", i.version); assertEquals(1234L, i.size)
        assertTrue(i.apkUrl.startsWith("https://"))
    }
    @Test fun ignoresDraftPreAndOtherAssets() {
        assertNull(AppReleaseParser.parse(rel(draft = true), rx))
        assertNull(AppReleaseParser.parse(rel(pre = true), rx))
        assertNull(AppReleaseParser.parse(rel(name = "other.zip"), rx))
    }
}
