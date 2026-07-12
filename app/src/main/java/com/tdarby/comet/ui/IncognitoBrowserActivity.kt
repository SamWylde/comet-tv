package com.tdarby.comet.ui

/** A private browser window backed by its own WebView data-directory process. */
class IncognitoBrowserActivity : BrowserActivity() {
    override val isIncognito: Boolean = true
}
