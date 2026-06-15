package com.tdarby.comet.engine

import android.content.Context

/**
 * Creates the right [BrowserEngine] for a requested [EngineType].
 *
 * GECKO is only available in the `full` flavor; [GeckoSupport] is resolved per-flavor (a real
 * implementation in `src/full`, a stub reporting `available = false` in `src/webview`). When GECKO
 * is requested but unavailable, we transparently fall back to WebView.
 */
object EngineFactory {
    fun create(context: Context, type: EngineType, callbacks: EngineCallbacks): BrowserEngine =
        when (type) {
            EngineType.WEBVIEW -> WebViewEngine(context, callbacks)
            EngineType.GECKO ->
                if (GeckoSupport.available) GeckoSupport.create(context, callbacks)
                else WebViewEngine(context, callbacks)
        }

    /** True if the requested engine can actually be created in this build. */
    fun isAvailable(type: EngineType): Boolean = when (type) {
        EngineType.WEBVIEW -> true
        EngineType.GECKO -> GeckoSupport.available
    }
}
