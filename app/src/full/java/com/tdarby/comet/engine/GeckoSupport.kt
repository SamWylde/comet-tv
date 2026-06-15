package com.tdarby.comet.engine

import android.content.Context

/** `full` flavor: GeckoView is bundled and available. */
object GeckoSupport {
    const val available = true

    fun create(context: Context, callbacks: EngineCallbacks): BrowserEngine =
        GeckoEngine(context, callbacks)
}
