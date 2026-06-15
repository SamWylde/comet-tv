package com.tdarby.comet.engine

import android.content.Context

/**
 * `webview` flavor: GeckoView is not bundled, so the engine is unavailable here. EngineFactory
 * falls back to WebView when GECKO is requested.
 */
object GeckoSupport {
    const val available = false

    fun create(context: Context, callbacks: EngineCallbacks): BrowserEngine =
        throw UnsupportedOperationException("GeckoView is not available in the webview flavor")
}
