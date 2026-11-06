package com.tdarby.comet.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.tdarby.comet.adblock.BlockedLog
import com.tdarby.comet.web.BrowserWebChromeClient
import com.tdarby.comet.web.BrowserWebViewClient
import com.tdarby.comet.adblock.PopupGuard
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import java.io.File
import java.util.UUID

/** System WebView (Chromium) implementation of [BrowserEngine]. */
class WebViewEngine(
    context: Context,
    private val callbacks: EngineCallbacks
) : BrowserEngine {

    private val webView = WebView(context)
    private val blockedLog = BlockedLog()
    private val ctx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var popupScript: ScriptHandler? = null
    private var mediaAgentScript: ScriptHandler? = null
    private val mediaHub = MediaFrameHub(
        clock = { SystemClock.uptimeMillis() },
        schedule = { delayMs, task ->
            val runnable = Runnable { task() }
            mainHandler.postDelayed(runnable, delayMs)
            val cancel: () -> Unit = { mainHandler.removeCallbacks(runnable) }
            cancel
        }
    )
    /** A new page makes every frame the hub knew obsolete. */
    private val pageCallbacks = object : EngineCallbacks by callbacks {
        override fun onPageStarted(url: String) {
            mediaHub.reset()
            callbacks.onPageStarted(url)
        }
    }
    private val scriptTransport = ScriptTransport { script, callback ->
        if (destroyed) callback(null) else webView.evaluateJavascript(script, callback)
    }
    private var mediaController = MediaController(scriptTransport) { SystemClock.uptimeMillis() }
    private val blobRequestLock = Any()
    private var pendingBlobRequest: PendingBlobRequest? = null
    private var appliedBrowsingMode: Triple<Boolean, Boolean, Boolean>? = null
    @Volatile private var destroyed = false
    private val defaultUserAgent = webView.settings.userAgentString
    private val supportsUserAgentMetadata =
        WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
    private val defaultUserAgentMetadata = if (supportsUserAgentMetadata) {
        WebSettingsCompat.getUserAgentMetadata(webView.settings)
    } else {
        null
    }

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
        webView.webViewClient = BrowserWebViewClient(pageCallbacks, blockedLog)
        webView.webChromeClient = BrowserWebChromeClient(callbacks, blockedLog)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            callbacks.onDownloadRequested(url, userAgent, contentDisposition, mimeType)
        }
        // Installed once, before any page loads: interfaces added later only reach later loads.
        webView.addJavascriptInterface(BlobBridge(), BLOB_BRIDGE_NAME)
        installMediaAgent()
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
    }

    /**
     * Puts a small media agent in every frame, cross-origin ones included, and listens to it. Both
     * must be registered before any page loads. Without WebView support the controller keeps only
     * the same-origin search of [ScriptTransport]. Everything the pages send is untrusted (see
     * [MediaWire] and [MediaFrameHub]); a listener must never throw, so a bad message is dropped.
     */
    private fun installMediaAgent() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) return
        runCatching {
            WebViewCompat.addWebMessageListener(webView, MEDIA_BRIDGE_NAME, setOf("*")) {
                    _, message, _, _, replyProxy ->
                try {
                    val text = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
                    mediaHub.onMessage(replyProxy, text) { ReplyProxyLink(replyProxy) }
                } catch (_: Exception) {
                    // Page data must never crash the browser.
                }
            }
            mediaAgentScript = WebViewCompat.addDocumentStartJavaScript(
                webView, MediaScripts.frameAgent(MEDIA_BRIDGE_NAME), setOf("*")
            )
            mediaController = MediaController(
                FallbackTransport(HubTransport(mediaHub), scriptTransport)
            ) { SystemClock.uptimeMillis() }
        }
    }

    private class ReplyProxyLink(private val proxy: JavaScriptReplyProxy) : FrameLink {
        override fun post(message: String): Boolean =
            try {
                proxy.postMessage(message)
                true
            } catch (_: Exception) {
                false
            }
    }

    private inner class PendingBlobRequest(val token: String, val sourceUrl: String) {
        val timeout = Runnable { failBlob(token) }
    }

    /**
     * The one blob JS bridge, installed in [configure] before any page loads (the WebView only
     * exposes interfaces to pages loaded after registration). Calls act on the current pending
     * request only when the token matches the one handed to the injected script.
     */
    private inner class BlobBridge {
        @JavascriptInterface
        fun complete(token: String, dataUrl: String, mime: String?) {
            val consumed = takeBlobRequest(token) ?: return
            val payload = BlobDownloadPolicy.validate(dataUrl)
            if (payload == null) {
                notifyDownloadResult(R.string.download_failed)
                return
            }
            saveBlob(dataUrl, payload, mime, consumed.sourceUrl)
        }

        @JavascriptInterface
        fun fail(token: String) = failBlob(token)
    }

    override fun fetchBlob(url: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { beginBlobRequest(url) }
            return
        }
        beginBlobRequest(url)
    }

    private fun beginBlobRequest(url: String) {
        if (destroyed) return
        cancelBlobRequest()
        val request = PendingBlobRequest(token = UUID.randomUUID().toString(), sourceUrl = url)
        synchronized(blobRequestLock) { pendingBlobRequest = request }
        mainHandler.postDelayed(request.timeout, BLOB_REQUEST_TIMEOUT_MS)

        // Embed the URL as a safely-escaped JS string literal (prevents script injection via the URL).
        val u = org.json.JSONObject.quote(url)
        val name = org.json.JSONObject.quote(BLOB_BRIDGE_NAME)
        val token = org.json.JSONObject.quote(request.token)
        // Evaluates to false when the bridge is missing, so the request fails right away.
        val js = "(function(n,t,u,limit){var b=window[n],done=false;" +
            "if(!b||typeof b.complete!=='function'||typeof b.fail!=='function')return false;" +
            "function fail(){if(done)return;done=true;try{b.fail(t);}catch(_){}}" +
            "try{var x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='blob';" +
            "x.onerror=x.onabort=x.ontimeout=fail;x.onload=function(){" +
            "if(done||!x.response||x.response.size>limit){fail();return;}" +
            "var r=new FileReader();r.onerror=r.onabort=fail;r.onload=function(){" +
            "if(done||typeof r.result!=='string'){fail();return;}done=true;" +
            "try{b.complete(t,r.result,x.getResponseHeader('content-type')||'');}catch(_){}};" +
            "r.readAsDataURL(x.response);};x.send();}catch(_){fail();}return true;})" +
            "($name,$token,$u,${BlobDownloadPolicy.MAX_DECODED_BYTES});"
        webView.evaluateJavascript(js) { result -> if (result == "false") failBlob(request.token) }
    }

    /** Claims the pending request when [token] matches it, so each request ends exactly once. */
    private fun takeBlobRequest(token: String): PendingBlobRequest? {
        val request = synchronized(blobRequestLock) {
            if (destroyed || pendingBlobRequest?.token != token) return@synchronized null
            pendingBlobRequest.also { pendingBlobRequest = null }
        } ?: return null
        mainHandler.removeCallbacks(request.timeout)
        return request
    }

    private fun failBlob(token: String) {
        if (takeBlobRequest(token) != null) notifyDownloadResult(R.string.download_failed)
    }

    private fun cancelBlobRequest() {
        val request = synchronized(blobRequestLock) {
            pendingBlobRequest.also { pendingBlobRequest = null }
        } ?: return
        mainHandler.removeCallbacks(request.timeout)
    }

    private fun notifyDownloadResult(messageRes: Int) {
        mainHandler.post { Toast.makeText(ctx, messageRes, Toast.LENGTH_SHORT).show() }
    }

    override fun captureThumbnail(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || webView.width <= 0 || webView.height <= 0) return null
        // A new tab shows Home over a blank page; a white picture of that tells the user nothing.
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.scale(width / webView.width.toFloat(), height / webView.height.toFloat())
                webView.draw(canvas)
            }
        }.getOrNull()
    }

    private fun saveBlob(
        dataUrl: String,
        payload: BlobDownloadPolicy.ValidatedPayload,
        mime: String?,
        srcUrl: String
    ) {
        val saved = runCatching { writeBlob(dataUrl, payload, mime, srcUrl) }.getOrDefault(false)
        notifyDownloadResult(if (saved) R.string.download_started else R.string.download_failed)
    }

    // @Suppress("Recycle"): the stream is closed via out.use {} below; lint can't track it.
    @Suppress("Recycle")
    private fun writeBlob(
        dataUrl: String,
        payload: BlobDownloadPolicy.ValidatedPayload,
        mime: String?,
        srcUrl: String
    ): Boolean {
        val bytes = Base64.decode(dataUrl.substring(payload.base64Start), Base64.DEFAULT)
        if (bytes.size != payload.decodedBytes) return false
        val name = URLUtil.guessFileName(srcUrl, null, mime?.ifBlank { null })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                val out = resolver.openOutputStream(uri) ?: error("No output stream for $uri")
                out.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                // Don't leave a pending, half-written row in the user's Downloads.
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).outputStream().use { it.write(bytes) }
        }
        return true
    }

    override fun loadUrl(url: String) {
        cancelBlobRequest()
        webView.loadUrl(url)
    }
    override fun currentUrl(): String? = webView.url
    override fun currentTitle(): String? = webView.title
    override fun canGoBack(): Boolean = webView.canGoBack()
    override fun goBack() {
        cancelBlobRequest()
        webView.goBack()
    }
    override fun canGoForward(): Boolean = webView.canGoForward()
    override fun goForward() {
        cancelBlobRequest()
        webView.goForward()
    }
    override fun reload() {
        cancelBlobRequest()
        webView.reload()
    }
    override fun stop() {
        cancelBlobRequest()
        webView.stopLoading()
    }
    override fun verticalScrollOffset(): Int = webView.scrollY

    override fun zoomIn() { webView.zoomIn() }
    override fun zoomOut() { webView.zoomOut() }

    override fun setTextZoom(percent: Int) {
        if (webView.settings.textZoom != percent) webView.settings.textZoom = percent
    }

    override fun recentBlocked(): List<BlockedLog.Entry> = blockedLog.entries()

    override fun hitTestAt(
        xPx: Float,
        yPx: Float,
        result: (href: String?, imageSrc: String?, anchorText: String?) -> Unit
    ) {
        @Suppress("DEPRECATION")
        val scale = webView.scale.takeIf { it > 0f } ?: webView.resources.displayMetrics.density
        val xCss = xPx / scale
        val yCss = yPx / scale
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
        mediaCommand(
            when (action) {
                MediaAction.PLAY_PAUSE -> MediaCommand.TogglePlay
                MediaAction.PLAY -> MediaCommand.Play
                MediaAction.PAUSE -> MediaCommand.Pause
                MediaAction.STOP -> MediaCommand.Stop
                MediaAction.REWIND -> MediaCommand.Seek(-REMOTE_SEEK_SECONDS)
                MediaAction.FORWARD -> MediaCommand.Seek(REMOTE_SEEK_SECONDS)
            }
        ) {}
    }

    override fun mediaCommand(command: MediaCommand, result: (MediaOutcome) -> Unit) {
        if (destroyed) result(MediaOutcome.NO_VIDEO) else mediaController.run(command, result)
    }

    override fun setDesktopMode(enabled: Boolean) =
        setBrowsingMode(enabled, enabled, enabled)

    override fun setBrowsingMode(
        desktopIdentity: Boolean,
        useWideViewPort: Boolean,
        loadWithOverviewMode: Boolean
    ) {
        // Unchanged values must not rewrite settings or reload the page (settings Apply and tab
        // switches call this for every engine). The first call on a new engine always applies.
        val mode = Triple(desktopIdentity, useWideViewPort, loadWithOverviewMode)
        if (mode == appliedBrowsingMode) return
        appliedBrowsingMode = mode
        webView.settings.userAgentString = UserAgentPolicy.userAgent(defaultUserAgent, desktopIdentity)
        if (supportsUserAgentMetadata) {
            val override = UserAgentPolicy.metadataOverride(defaultUserAgent, desktopIdentity)
            val metadata = if (override == null) {
                // Restore the provider's original Android/mobile Client Hints exactly.
                defaultUserAgentMetadata
            } else {
                UserAgentMetadata.Builder(requireNotNull(defaultUserAgentMetadata))
                    .setBrandVersionList(
                        defaultUserAgentMetadata.brandVersionList.map { brandVersion ->
                            UserAgentMetadata.BrandVersion.Builder(brandVersion)
                                .setBrand(UserAgentPolicy.brand(brandVersion.brand, desktopIdentity))
                                .build()
                        }
                    )
                    .setPlatform(override.platform)
                    .setPlatformVersion(override.platformVersion)
                    .setArchitecture(override.architecture)
                    .setModel(override.model)
                    .setMobile(override.mobile)
                    .setBitness(override.bitness)
                    .setWow64(override.wow64)
                    .build()
            }
            WebSettingsCompat.setUserAgentMetadata(webView.settings, requireNotNull(metadata))
        }
        webView.settings.useWideViewPort = useWideViewPort
        webView.settings.loadWithOverviewMode = loadWithOverviewMode
        // Engine creation configures this before its first URL. Reloading about:blank here forces
        // an unnecessary Chromium navigation for every new/restored tab.
        if (!webView.url.isNullOrBlank() && webView.url != "about:blank") {
            cancelBlobRequest()
            webView.reload()
        }
    }

    override fun setBlockingEnabled(enabled: Boolean) {
        AdBlocker.networkEnabled = enabled
    }

    override fun applyPopupPolicy() {
        // When popup blocking is on, JS cannot auto-open windows; otherwise it can.
        webView.settings.javaScriptCanOpenWindowsAutomatically =
            !AdBlocker.popupBlockingActive(AdBlocker.hostOf(webView.url))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Re-added on every call so the script's embedded allowlist stays current.
            popupScript?.remove()
            popupScript = null
            if (AdBlocker.popupEnabled) {
                popupScript = WebViewCompat.addDocumentStartJavaScript(
                    webView, PopupGuard.scriptSkippingHosts(AdBlocker.popupScriptSkipHosts()), setOf("*")
                )
            }
        }
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
        cancelBlobRequest()
        destroyed = true
        mediaHub.reset()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            popupScript?.remove()
            mediaAgentScript?.remove()
        }
        popupScript = null
        mediaAgentScript = null
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    companion object {
        private const val BLOB_REQUEST_TIMEOUT_MS = 2 * 60 * 1000L
        private const val BLOB_BRIDGE_NAME = "CometBlob"
        /** Neutral on purpose: pages see this object in every frame. */
        private const val MEDIA_BRIDGE_NAME = "__frameMedia"
        private const val REMOTE_SEEK_SECONDS = 10.0
    }
}
