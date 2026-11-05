package com.tdarby.comet.data

import java.io.File

/**
 * On-disk WebView data of the private (`:incognito`) process. With data-directory suffix S, WebView
 * keeps its profile in `<dataDir>/app_webview_S` and its HTTP/GPU cache in `<cacheDir>/webview_S`
 * (Chromium AwBrowserProcess.loadLibrary: `webview_` + suffix is both the `Context.getDir` name and
 * the cache sub-directory). The normal profile (`app_webview`, `cache/WebView`) is never touched.
 */
object PrivateProfile {
    const val DATA_SUFFIX = "incognito"

    /** The private profile's directories that currently exist under the given app roots. */
    fun existingDirs(appDataDir: File, cacheDir: File): List<File> =
        listOf(File(appDataDir, "app_webview_$DATA_SUFFIX"), File(cacheDir, "webview_$DATA_SUFFIX"))
            .filter { it.exists() }

    /** Delete what an earlier private process left behind. Call before any WebView exists. */
    fun wipeLeftovers(appDataDir: File, cacheDir: File) {
        existingDirs(appDataDir, cacheDir).forEach { it.deleteRecursively() }
    }
}
