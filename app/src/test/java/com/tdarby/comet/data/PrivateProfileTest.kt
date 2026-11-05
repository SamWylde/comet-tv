package com.tdarby.comet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivateProfileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(vararg path: String): File =
        File(tmp.root, path.joinToString("/")).apply { mkdirs() }

    @Test fun wipesOnlyThePrivateProfileLeftByAKilledProcess() {
        val privateData = dir("data", "app_webview_incognito", "Default")
        File(privateData, "Cookies").writeText("session")
        val privateCache = dir("cache", "webview_incognito")
        File(privateCache, "entry").writeText("cached")
        val normalData = dir("data", "app_webview", "Default")
        File(normalData, "Cookies").writeText("keep")
        val normalCache = dir("cache", "WebView")
        val otherFiles = dir("data", "files")

        PrivateProfile.wipeLeftovers(File(tmp.root, "data"), File(tmp.root, "cache"))

        assertFalse(File(tmp.root, "data/app_webview_incognito").exists())
        assertFalse(File(tmp.root, "cache/webview_incognito").exists())
        assertTrue(File(normalData, "Cookies").exists())
        assertTrue(normalCache.exists())
        assertTrue(otherFiles.exists())
    }
}
