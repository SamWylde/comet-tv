package com.tdarby.comet.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.tdarby.comet.R
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.web.BrowserWebChromeClient
import com.tdarby.comet.web.BrowserWebViewClient
import java.io.File

/** System WebView (Chromium) implementation of [BrowserEngine]. */
class WebViewEngine(
    context: Context,
    private val callbacks: EngineCallbacks
) : BrowserEngine {

    private val webView = WebView(context)
    private val ctx = context.applicationContext

    override val view: View get() = webView

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Autoplay HTML5 video without a user gesture (key for streaming pages on a remote).
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Multiple-window support is on so onCreateWindow (PopupGuard) can intercept.
            setSupportMultipleWindows(true)
            // Allow programmatic zoom (driven from the menu/remote) but no on-screen pinch widgets.
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
        }
        applyPopupPolicy()
        // Third-party cookies are kept on deliberately: embedded video players and SSO logins on
        // streaming sites frequently require them. Trackers are handled by the ad/tracker blocker.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = BrowserWebViewClient(callbacks)
        webView.webChromeClient = BrowserWebChromeClient(callbacks)
        webView.setDownloadListener { url, userAgent, _, mimeType, _ ->
            callbacks.onDownloadRequested(url, userAgent, mimeType)
        }
        webView.addJavascriptInterface(BlobBridge(), "CometDownload")
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
    }

    /** JS bridge used by [fetchBlob] to hand a read blob back to native for saving. */
    private inner class BlobBridge {
        @JavascriptInterface
        fun onBlob(dataUrl: String, mime: String?, srcUrl: String) = saveBlob(dataUrl, mime, srcUrl)
    }

    override fun fetchBlob(url: String) {
        // Embed the URL as a safely-escaped JS string literal (prevents script injection via the URL).
        val u = org.json.JSONObject.quote(url)
        val js = "(function(){try{var x=new XMLHttpRequest();x.open('GET',$u,true);" +
            "x.responseType='blob';x.onload=function(){var r=new FileReader();r.onloadend=function(){" +
            "CometDownload.onBlob(r.result,x.getResponseHeader('content-type')||'',$u);};" +
            "r.readAsDataURL(x.response);};x.send();}catch(e){}})();"
        webView.evaluateJavascript(js, null)
    }

    // @Suppress("Recycle"): the stream is closed via out.use {} below; lint can't track it across `?: return`.
    @Suppress("Recycle")
    private fun saveBlob(dataUrl: String, mime: String?, srcUrl: String) {
        runCatching {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val name = URLUtil.guessFileName(srcUrl, null, mime?.ifBlank { null })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                val out = resolver.openOutputStream(uri) ?: return
                out.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).outputStream().use { it.write(bytes) }
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, R.string.download_started, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun loadUrl(url: String) = webView.loadUrl(url)
    override fun currentUrl(): String? = webView.url
    override fun currentTitle(): String? = webView.title
    override fun canGoBack(): Boolean = webView.canGoBack()
    override fun goBack() = webView.goBack()
    override fun canGoForward(): Boolean = webView.canGoForward()
    override fun goForward() = webView.goForward()
    override fun reload() = webView.reload()
    override fun stop() = webView.stopLoading()
    override fun verticalScrollOffset(): Int = webView.scrollY

    override fun zoomIn() { webView.zoomIn() }
    override fun zoomOut() { webView.zoomOut() }

    override fun hitTestAt(
        xCss: Float,
        yCss: Float,
        result: (href: String?, imageSrc: String?, anchorText: String?) -> Unit
    ) {
        val js = "(function(){try{var el=document.elementFromPoint($xCss,$yCss);if(!el)return '{}';" +
            "var a=el.closest&&el.closest('a[href]');var i=el.closest&&el.closest('img[src]');" +
            "return JSON.stringify({href:a?a.href:null,img:i?i.src:null," +
            "text:a?(a.textContent||'').trim().slice(0,80):null});}catch(e){return '{}';}})();"
        webView.evaluateJavascript(js) { raw ->
            val o = runCatching {
                val inner = org.json.JSONTokener(raw).nextValue()
                org.json.JSONObject(if (inner is String) inner else raw)
            }.getOrNull()
            fun field(k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
            result(field("href"), field("img"), field("text"))
        }
    }

    override fun mediaAction(action: MediaAction) {
        val op = when (action) {
            MediaAction.PLAY_PAUSE -> "if(v.paused)v.play();else v.pause();"
            MediaAction.STOP -> "v.pause();try{v.currentTime=0;}catch(e){}"
            MediaAction.REWIND -> "try{v.currentTime=Math.max(0,v.currentTime-10);}catch(e){}"
            MediaAction.FORWARD -> "try{v.currentTime=v.currentTime+10;}catch(e){}"
        }
        webView.evaluateJavascript(
            "(function(){var m=Array.from(document.querySelectorAll('video,audio'));" +
                "var v=m.find(function(e){return !e.paused&&!e.ended;})||m[0];if(!v)return;$op})();",
            null
        )
    }

    override fun setDesktopMode(enabled: Boolean) {
        webView.settings.userAgentString = if (enabled) DESKTOP_UA else null
        webView.settings.useWideViewPort = enabled
        webView.settings.loadWithOverviewMode = enabled
        webView.reload()
    }

    override fun setBlockingEnabled(enabled: Boolean) {
        AdBlocker.networkEnabled = enabled
    }

    override fun applyPopupPolicy() {
        // When popup blocking is on, JS cannot auto-open windows; otherwise it can.
        webView.settings.javaScriptCanOpenWindowsAutomatically = !AdBlocker.popupEnabled
    }

    override fun setSiteAllowlisted(host: String, allowlisted: Boolean) {
        // Allowlist is managed centrally in AdBlocker via SettingsStore.
    }

    override fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
    }

    override fun destroy() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    companion object {
        /** Desktop Chrome UA — many video players serve better streams to desktop clients. */
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
