package com.tdarby.comet.web

import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tdarby.comet.engine.BrowserEngine
import com.tdarby.comet.engine.EngineCallbacks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BrowserActivity's engine init reads [TabManager.activeEngine] (issue #1: 1.1.0 crashed at launch
 * because the new tab's engine was not stored yet). Runs on any device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class TabManagerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun initSeesTheNewEngineAsActive() {
        val mismatches = mutableListOf<String>()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val context = instrumentation.targetContext
                lateinit var tabs: TabManager
                var step = ""
                tabs = TabManager(
                    container = FrameLayout(context),
                    homeUrl = "about:blank",
                    create = { FakeEngine(View(context)) },
                    init = { engine, _ -> if (tabs.activeEngine !== engine) mismatches += step },
                    ui = object : EngineCallbacks {},
                    onChanged = {}
                )
                step = "first launch (no saved tabs)"
                tabs.restore(emptyList(), 0)
                step = "new tab"
                tabs.newTab("about:blank")
                step = "engine switch"
                tabs.recreateAll()
                tabs.destroyAll()
                step = "restored tabs"
                tabs.restore(
                    listOf(
                        TabManager.TabSnapshot("about:blank", "one"),
                        TabManager.TabSnapshot("about:blank", "two")
                    ),
                    1
                )
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw AssertionError("TabManager threw during engine init", it) }
        assertEquals(emptyList<String>(), mismatches)
    }

    private class FakeEngine(override val view: View) : BrowserEngine {
        override fun loadUrl(url: String) {}
        override fun currentUrl(): String? = null
        override fun currentTitle(): String? = null
        override fun canGoBack() = false
        override fun goBack() {}
        override fun canGoForward() = false
        override fun goForward() {}
        override fun reload() {}
        override fun stop() {}
        override fun setDesktopMode(enabled: Boolean) {}
        override fun setBlockingEnabled(enabled: Boolean) {}
        override fun setSiteAllowlisted(host: String, allowlisted: Boolean) {}
        override fun destroy() {}
    }
}
