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
import com.tdarby.comet.adblock.PopupGuard
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
    private val ctx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var popupScript: ScriptHandler? = null
    private val blobRequestLock = Any()
    private var pendingBlobRequest: PendingBlobRequest? = null
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
        webView.webViewClient = BrowserWebViewClient(callbacks)
        webView.webChromeClient = BrowserWebChromeClient(callbacks)
        webView.setDownloadListener { url, userAgent, _, mimeType, _ ->
            callbacks.onDownloadRequested(url, userAgent, mimeType)
        }
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
    }

    private inner class PendingBlobRequest(
        val interfaceName: String,
        val token: String,
        val sourceUrl: String
    ) {
        val bridge = BlobBridge(this)
        val timeout = Runnable { failBlob(this) }
    }

    /** Single-request JS bridge used by [fetchBlob], never installed for the WebView lifetime. */
    private inner class BlobBridge(private val request: PendingBlobRequest) {
        @JavascriptInterface
        fun complete(token: String, dataUrl: String, mime: String?) {
            val consumed = takeBlobRequest(request, token) ?: return
            removeBlobBridge(consumed)
            val payload = BlobDownloadPolicy.validate(dataUrl) ?: return
            saveBlob(dataUrl, payload, mime, consumed.sourceUrl)
        }

        @JavascriptInterface
        fun fail(token: String) {
            if (token == request.token) failBlob(request)
        }
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
        val request = PendingBlobRequest(
            interfaceName = "CometBlob_${UUID.randomUUID().toString().replace("-", "")}",
            token = UUID.randomUUID().toString(),
            sourceUrl = url
        )
        synchronized(blobRequestLock) { pendingBlobRequest = request }
        webView.addJavascriptInterface(request.bridge, request.interfaceName)
        mainHandler.postDelayed(request.timeout, BLOB_REQUEST_TIMEOUT_MS)

        // Embed the URL as a safely-escaped JS string literal (prevents script injection via the URL).
        val u = org.json.JSONObject.quote(url)
        val name = org.json.JSONObject.quote(request.interfaceName)
        val token = org.json.JSONObject.quote(request.token)
        val js = "(function(n,t,u,limit){var b=window[n],done=false;" +
            "function fail(){if(done)return;done=true;try{b.fail(t);}catch(_){}}" +
            "try{var x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='blob';" +
            "x.onerror=x.onabort=x.ontimeout=fail;x.onload=function(){" +
            "if(done||!x.response||x.response.size>limit){fail();return;}" +
            "var r=new FileReader();r.onerror=r.onabort=fail;r.onload=function(){" +
            "if(done||typeof r.result!=='string'){fail();return;}done=true;" +
            "try{b.complete(t,r.result,x.getResponseHeader('content-type')||'');}catch(_){}};" +
            "r.readAsDataURL(x.response);};x.send();}catch(_){fail();}})" +
            "($name,$token,$u,${BlobDownloadPolicy.MAX_DECODED_BYTES});"
        webView.evaluateJavascript(js, null)
    }

    private fun takeBlobRequest(request: PendingBlobRequest, token: String): PendingBlobRequest? =
        synchronized(blobRequestLock) {
            if (destroyed || pendingBlobRequest !== request || token != request.token) return@synchronized null
            pendingBlobRequest = null
            request
        }

    private fun failBlob(request: PendingBlobRequest) {
        val consumed = takeBlobRequest(request, request.token) ?: return
        removeBlobBridge(consumed)
    }

    private fun cancelBlobRequest() {
        val request = synchronized(blobRequestLock) {
            pendingBlobRequest.also { pendingBlobRequest = null }
        } ?: return
        removeBlobBridge(request)
    }

    private fun removeBlobBridge(request: PendingBlobRequest) {
        mainHandler.removeCallbacks(request.timeout)
        val remove = Runnable { if (!destroyed) webView.removeJavascriptInterface(request.interfaceName) }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else mainHandler.post(remove)
    }

    override fun captureThumbnail(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || webView.width <= 0 || webView.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.scale(width / webView.width.toFloat(), height / webView.height.toFloat())
                webView.draw(canvas)
            }
        }.getOrNull()
    }

    // @Suppress("Recycle"): the stream is closed via out.use {} below; lint can't track it across `?: return`.
    @Suppress("Recycle")
    private fun saveBlob(
        dataUrl: String,
        payload: BlobDownloadPolicy.ValidatedPayload,
        mime: String?,
        srcUrl: String
    ) {
        runCatching {
            val bytes = Base64.decode(dataUrl.substring(payload.base64Start), Base64.DEFAULT)
            if (bytes.size != payload.decodedBytes) return
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
        webView.evaluateJavascript(MediaScripts.forAction(action), null)
    }

    override fun setDesktopMode(enabled: Boolean) =
        setBrowsingMode(enabled, enabled, enabled)

    override fun setBrowsingMode(
        desktopIdentity: Boolean,
        useWideViewPort: Boolean,
        loadWithOverviewMode: Boolean
    ) {
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
        webView.settings.javaScriptCanOpenWindowsAutomatically = !AdBlocker.popupEnabled
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (AdBlocker.popupEnabled && popupScript == null) {
                popupScript = WebViewCompat.addDocumentStartJavaScript(webView, PopupGuard.JS, setOf("*"))
            } else if (!AdBlocker.popupEnabled) {
                popupScript?.remove()
                popupScript = null
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            popupScript?.remove()
        }
        popupScript = null
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    companion object {
        private const val BLOB_REQUEST_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
