package com.tdarby.comet.engine

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.tdarby.comet.adblock.AdBlocker
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

/** GeckoView (Firefox engine) implementation of [BrowserEngine] — `full` flavor only. */
class GeckoEngine(
    context: Context,
    private val callbacks: EngineCallbacks
) : BrowserEngine {

    private val geckoView = GeckoView(context)
    private val session = GeckoSession()

    private var url: String? = null
    private var title: String? = null
    private var canBack = false
    private var canForward = false

    override val view: View get() = geckoView

    init {
        val runtime = runtime(context)

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canBack = canGoBack
                callbacks.onNavigationStateChanged(canBack, canForward)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canForward = canGoForward
                callbacks.onNavigationStateChanged(canBack, canForward)
            }

            // Popup policy parity with WebView: open as a new tab when allowed, else suppress.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (AdBlocker.popupEnabled) callbacks.onPopupBlocked(uri)
                else callbacks.onOpenInNewTab(uri)
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                this@GeckoEngine.url = url
                callbacks.onPageStarted(url)
                callbacks.onUrlChanged(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                url?.let {
                    callbacks.onPageFinished(it)
                    callbacks.onUrlChanged(it)
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                callbacks.onProgressChanged(progress)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                this@GeckoEngine.title = title
                callbacks.onTitleChanged(title)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (fullScreen) {
                    callbacks.onEnterFullscreen(geckoView) { session.exitFullScreen() }
                } else {
                    callbacks.onExitFullscreen()
                }
            }

            // Downloads: hand the URL to the activity's DownloadManager flow.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                callbacks.onDownloadRequested(response.uri, null, response.headers["Content-Type"])
            }
        }

        // GeckoView's blocking path: Enhanced Tracking Protection (ads via the CONTENT category).
        session.settings.useTrackingProtection = AdBlocker.networkEnabled

        session.open(runtime)
        geckoView.setSession(session)
    }

    override fun loadUrl(url: String) = session.loadUri(url)
    override fun currentUrl(): String? = url
    override fun currentTitle(): String? = title
    override fun canGoBack(): Boolean = canBack
    override fun goBack() = session.goBack()
    override fun canGoForward(): Boolean = canForward
    override fun goForward() = session.goForward()
    override fun reload() = session.reload()
    override fun stop() = session.stop()

    override fun setDesktopMode(enabled: Boolean) {
        session.settings.userAgentMode =
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        session.reload()
    }

    override fun setBlockingEnabled(enabled: Boolean) {
        session.settings.useTrackingProtection = enabled
    }

    override fun setSiteAllowlisted(host: String, allowlisted: Boolean) {
        // Allowlist is managed centrally; ETP exceptions could be added here in future.
    }

    override fun destroy() {
        session.close()
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
    }

    companion object {
        @Volatile
        private var runtimeInstance: GeckoRuntime? = null

        private fun runtime(context: Context): GeckoRuntime =
            runtimeInstance ?: synchronized(this) {
                runtimeInstance ?: createRuntime(context).also { runtimeInstance = it }
            }

        private fun createRuntime(context: Context): GeckoRuntime {
            val contentBlocking = ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .build()
            val settings = GeckoRuntimeSettings.Builder()
                .contentBlocking(contentBlocking)
                .build()
            return GeckoRuntime.create(context.applicationContext, settings)
        }
    }
}
