package com.tdarby.comet.ui

import android.Manifest
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tdarby.comet.BuildConfig
import com.tdarby.comet.R
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.data.SettingsStore
import com.tdarby.comet.data.BrowsingIdentity
import com.tdarby.comet.data.BrowsingViewport
import com.tdarby.comet.data.ResolvedSiteBrowsingSettings
import com.tdarby.comet.data.SiteBrowsingPolicy
import com.tdarby.comet.data.SiteBrowsingSettings
import com.tdarby.comet.databinding.ActivityBrowserBinding
import com.tdarby.comet.databinding.DialogSettingsBinding
import com.tdarby.comet.engine.BrowserEngine
import com.tdarby.comet.engine.EngineCallbacks
import com.tdarby.comet.engine.WebViewEngine
import com.tdarby.comet.data.BookmarkTransfer
import com.tdarby.comet.data.BrowserStore
import com.tdarby.comet.data.TabsStore
import com.tdarby.comet.engine.MediaKeys
import com.tdarby.comet.engine.PageFailure
import com.tdarby.comet.engine.PageFailureKind
import com.tdarby.comet.input.CursorController
import com.tdarby.comet.input.RemoteExitPolicy
import com.tdarby.comet.input.RemoteKey
import com.tdarby.comet.permission.PermissionDecision
import com.tdarby.comet.permission.PermissionKey
import com.tdarby.comet.permission.PermissionPolicy
import com.tdarby.comet.permission.SitePermissionResource
import com.tdarby.comet.support.SupportDevice
import com.tdarby.comet.support.SupportReportBuilder
import com.tdarby.comet.support.SupportReportInput
import com.tdarby.comet.support.SupportViewport
import com.tdarby.comet.support.UserAgentBrand
import com.tdarby.comet.support.UserAgentClientHints
import com.tdarby.comet.downloads.DownloadReason
import com.tdarby.comet.downloads.DownloadReasons
import com.tdarby.comet.downloads.HiddenDownloads
import com.tdarby.comet.update.UpdateController
import com.tdarby.comet.web.TabManager
import com.tdarby.comet.web.TabMemoryPolicy
import com.tdarby.comet.util.SearchEngines
import com.tdarby.comet.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

open class BrowserActivity : AppCompatActivity() {

    protected open val isIncognito: Boolean = false

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var settings: SettingsStore
    private lateinit var store: BrowserStore
    private lateinit var tabsStore: TabsStore
    private lateinit var tabManager: TabManager
    private lateinit var cursor: CursorController

    /** Pending `<input type=file>` callback while the system picker is open. */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileChooserCallback ?: return@registerForActivityResult
            fileChooserCallback = null
            cb.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    private val voiceSearchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            // The result can reach a recreated Activity while startup is still reading its state.
            if (::tabManager.isInitialized) runVoiceSearch(spoken) else pendingVoiceSearch = spoken
        }

    private var pendingVoiceSearch: String? = null

    private fun runVoiceSearch(spoken: String) {
        engine.loadUrl(UrlUtils.toUrlOrSearch(spoken, searchTemplate))
    }

    /** Completed when the OS runtime-permission prompt returns (for web permission requests). */
    private var onOsPermissionResult: ((granted: Boolean) -> Unit)? = null
    private val permissionPolicy = PermissionPolicy()
    private var permissionDialog: AlertDialog? = null
    private var activePermissionRequest: PermissionRequest? = null
    private var activeGeolocationOrigin: String? = null
    private var activeGeolocationCallback: GeolocationPermissions.Callback? = null

    private val osPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.isNotEmpty() && result.values.all { it }
            onOsPermissionResult?.invoke(granted)
            onOsPermissionResult = null
        }

    /** Ensure the app holds [perms] (request the missing ones), then report the outcome. */
    private fun ensureOsPermissions(perms: List<String>, onResult: (Boolean) -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { onResult(true); return }
        onOsPermissionResult = onResult
        osPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun cancelPermissionPrompt(deny: Boolean) {
        permissionDialog?.setOnCancelListener(null)
        permissionDialog?.dismiss()
        permissionDialog = null
        activePermissionRequest?.let { if (deny) it.deny() }
        activePermissionRequest = null
        val geoOrigin = activeGeolocationOrigin
        val geoCallback = activeGeolocationCallback
        if (deny && geoOrigin != null && geoCallback != null) geoCallback.invoke(geoOrigin, false, false)
        activeGeolocationOrigin = null
        activeGeolocationCallback = null
        onOsPermissionResult = null
    }

    private fun resourceLabel(resource: SitePermissionResource): String = when (resource) {
        SitePermissionResource.CAMERA -> getString(R.string.permission_camera)
        SitePermissionResource.MICROPHONE -> getString(R.string.permission_microphone)
        SitePermissionResource.PROTECTED_MEDIA -> getString(R.string.permission_protected_media)
        SitePermissionResource.LOCATION -> getString(R.string.permission_location_name)
    }

    private fun osPermissionsFor(resources: Collection<String>): List<String> = resources.mapNotNull {
        when (it) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            else -> null
        }
    }.distinct()

    private fun finishPermissionRequest(
        request: PermissionRequest,
        origin: String,
        decisions: Map<PermissionKey, PermissionDecision>
    ) {
        val resolution = permissionPolicy.resolve(origin, request.resources.toList(), decisions)
        val granted = resolution.grantedWebViewResources.toTypedArray()
        if (granted.isEmpty()) {
            if (activePermissionRequest === request) activePermissionRequest = null
            request.deny()
            return
        }
        ensureOsPermissions(osPermissionsFor(granted.asList())) { osGranted ->
            if (activePermissionRequest !== request) return@ensureOsPermissions
            activePermissionRequest = null
            if (osGranted) request.grant(granted) else request.deny()
        }
    }

    private fun showPermissionChoice(
        origin: String,
        resources: Collection<SitePermissionResource>,
        onDecision: (PermissionDecision) -> Unit
    ) {
        permissionDialog = AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(getString(
                R.string.permission_message_origin,
                origin,
                resources.joinToString { resourceLabel(it) }
            ))
            .setPositiveButton(R.string.permission_allow_once) { _, _ ->
                permissionDialog = null
                onDecision(PermissionDecision.ALLOW_ONCE)
            }
            .setNeutralButton(R.string.permission_allow_session) { _, _ ->
                permissionDialog = null
                onDecision(PermissionDecision.ALLOW_SESSION)
            }
            .setNegativeButton(R.string.permission_deny) { _, _ ->
                permissionDialog = null
                onDecision(PermissionDecision.DENY)
            }
            .setOnCancelListener {
                permissionDialog = null
                onDecision(PermissionDecision.DENY)
            }
            .show()
    }

    private fun finishGeolocationRequest(
        origin: String,
        callback: GeolocationPermissions.Callback,
        decision: PermissionDecision
    ) {
        val key = permissionPolicy.key(origin, SitePermissionResource.LOCATION)
        if (key == null || !permissionPolicy.applyDecision(key, decision)) {
            if (activeGeolocationCallback === callback) {
                activeGeolocationOrigin = null
                activeGeolocationCallback = null
            }
            callback.invoke(origin, false, false)
            return
        }
        ensureOsPermissions(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) {
            if (activeGeolocationCallback !== callback || activeGeolocationOrigin != origin) {
                return@ensureOsPermissions
            }
            activeGeolocationOrigin = null
            activeGeolocationCallback = null
            // Android 12+ may grant approximate location while denying precise location.
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            callback.invoke(origin, granted, false)
        }
    }

    /** The active tab's engine. A getter keeps every call site engine-agnostic and tab-aware. */
    private val engine: BrowserEngine get() = tabManager.activeEngine

    private var desktopMode = false
    private var siteBrowsingSettings: Map<String, SiteBrowsingSettings> = emptyMap()
    private var activeBrowsingSettings = SiteBrowsingPolicy.resolve(null, false)
    private var searchTemplate = SettingsStore.DEFAULT_SEARCH
    private var directNav = false
    private var cursorSpeedSetting = SettingsStore.DEFAULT_CURSOR_SPEED
    private var hintShownAlready = false

    private var isFullscreen = false
    private val updateController by lazy { UpdateController(this) { isFullscreen } }
    private val hiddenDownloads by lazy { HiddenDownloads(this) }
    private var exitFullscreen: (() -> Unit)? = null
    private var fullscreenView: View? = null
    private var fullscreenOriginalParent: ViewGroup? = null
    private var fullscreenOriginalIndex = -1
    private var fullscreenOriginalLayoutParams: ViewGroup.LayoutParams? = null
    private var centerLongHandled = false
    private var currentHost: String? = null
    private var lastBlockToast = 0L
    private val exitPolicy = RemoteExitPolicy()
    private var exitState = RemoteExitPolicy.State()
    private var pendingIntentUrl: String? = null
    private var addressBarActive = false
    private var suppressBackCallbackUntil = 0L
    private var homeVisible = false
    private var currentFailure: PageFailure? = null

    private data class StartupData(
        val settings: SettingsStore.Snapshot,
        val browserStore: BrowserStore,
        val tabsStore: TabsStore,
        val savedTabs: List<TabManager.TabSnapshot>,
        val activeTab: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Take an incoming link once: from the launch intent on a fresh start, or after a recreation
        // from the saved state, which holds it only if startup hadn't opened it yet.
        pendingIntentUrl = if (savedInstanceState == null) intentUrl(intent)
        else savedInstanceState.getString(KEY_PENDING_LINK)
        consumeIntentLink()
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        binding.startupOverlay.requestFocus()

        val privateSession = if (isIncognito) restorePrivateSession(savedInstanceState) else null

        // DataStore and JSON session/history reads must not hold up Android's first frame. The
        // branded loading surface is drawn immediately while this small snapshot is read on IO.
        lifecycleScope.launch {
            val startup = withContext(Dispatchers.IO) {
                val browserStore = BrowserStore(applicationContext)
                val savedTabsStore = TabsStore(applicationContext)
                val savedTabs = if (isIncognito) privateSession?.tabs.orEmpty() else savedTabsStore.load()
                StartupData(
                    settings = settings.snapshot(),
                    browserStore = browserStore,
                    tabsStore = savedTabsStore,
                    savedTabs = savedTabs,
                    activeTab = privateSession?.activeIndex ?: savedTabsStore.activeIndex
                )
            }
            initializeBrowser(startup)
        }
    }

    private fun initializeBrowser(startup: StartupData) {
        store = startup.browserStore
        tabsStore = startup.tabsStore
        with(startup.settings) {
            this@BrowserActivity.desktopMode = desktopMode
            this@BrowserActivity.searchTemplate = searchTemplate
            this@BrowserActivity.cursorSpeedSetting = cursorSpeed
            this@BrowserActivity.directNav = directNav
            this@BrowserActivity.hintShownAlready = firstRunHintShown
            this@BrowserActivity.siteBrowsingSettings = siteBrowsingSettings
            AdBlocker.networkEnabled = blockNetwork
            AdBlocker.cosmeticEnabled = blockCosmetic
            AdBlocker.popupEnabled = blockPopups
            AdBlocker.redirectBlockEnabled = blockRedirects
            AdBlocker.setAllowlist(allowlist)
        }

        cursor = CursorController(binding.engineContainer) {
            if (::tabManager.isInitialized && tabManager.hasTabs) tabManager.activeEngine.view else null
        }
        cursor.speedFactor = speedFactor(cursorSpeedSetting)
        tabManager = TabManager(
            container = binding.engineContainer,
            homeUrl = HOME_URL,
            create = { cb -> WebViewEngine(this, cb) },
            init = { e, url ->
                applyBrowsingSettings(e, AdBlocker.hostOf(url))
                e.setBlockingEnabled(AdBlocker.networkEnabled)
                e.applyPopupPolicy()
            },
            ui = callbacks,
            onChanged = ::onTabsChanged,
            liveEngineBudget = TabMemoryPolicy.budgetFor(this),
            rememberClosedTabs = !isIncognito
        )

        wireChrome()
        wireHome()
        wireErrorPanel()
        registerBackHandling()
        setupCursorFocus()

        openInitialTabs(startup.savedTabs, startup.activeTab)
        pendingVoiceSearch?.let { pendingVoiceSearch = null; runVoiceSearch(it) }
        binding.startupOverlay.visibility = View.GONE
        syncHomeVisibility(requestFocus = true)
        binding.root.post { reportFullyDrawn() }
        if (!isIncognito) {
            updateController.check(manual = false)
            maybeShowFirstRunHint()
        }
    }

    /** One-time overlay explaining the remote controls (cursor / OK / BACK / RIGHT / long-press). */
    private fun maybeShowFirstRunHint() {
        if (hintShownAlready) return
        hintShownAlready = true
        AlertDialog.Builder(this)
            .setTitle(R.string.hint_title)
            .setMessage(R.string.hint_body)
            .setPositiveButton(R.string.hint_got_it, null)
            .setOnDismissListener { lifecycleScope.launch { settings.setFirstRunHintShown(true) } }
            .show()
    }

    /** Open a link passed via VIEW intent, else restore the saved session, else a home tab. */
    private fun openInitialTabs(savedTabs: List<TabManager.TabSnapshot>, activeTab: Int) {
        val intentUrl = pendingIntentUrl
        pendingIntentUrl = null
        if (savedTabs.isNotEmpty() || intentUrl == null) {
            tabManager.restore(savedTabs, activeTab) // empty -> home tab
        }
        if (intentUrl != null) tabManager.newTab(intentUrl)
    }

    /** Extract an http(s) URL from a VIEW intent (opening links from other apps). */
    private fun intentUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.dataString ?: return null
        return data.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Drop the link from the Activity's intent so it opens once, not again on recreation. */
    private fun consumeIntentLink() {
        setIntent(Intent(intent).apply { data = null })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intentUrl(intent)
        consumeIntentLink()
        url?.let {
            if (::tabManager.isInitialized) {
                if (isFullscreen) exitFullscreen?.invoke()
                tabManager.newTab(it)
            } else {
                pendingIntentUrl = it
            }
        }
    }

    private fun persistTabs() {
        if (!isIncognito && ::tabManager.isInitialized && ::tabsStore.isInitialized && tabManager.hasTabs) {
            tabsStore.save(tabManager.snapshot(), tabManager.activePosition())
        }
    }

    private fun setupCursorFocus() {
        binding.urlBar.onRemoteBack = ::leaveAddressBar
        binding.urlBar.onRemoteHorizontal = { keyCode ->
            addressBarActive = false
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) binding.btnReload.requestFocus()
            else binding.btnGo.requestFocus()
        }
        // Autocomplete the address bar from history + bookmarks (big win for on-screen-keyboard typing).
        val suggestions = UrlSuggestionAdapter(this)
        binding.urlBar.setAdapter(suggestions)
        binding.urlBar.setOnItemClickListener { _, _, _, _ -> navigateFromBar() }
        fun refreshSuggestions() =
            suggestions.setCandidates(
                store.bookmarks.map { SuggestionCandidate(it.url, it.title, true) } +
                    store.history.map { SuggestionCandidate(it.url, it.title, false) }
            )
        // Refresh on every keystroke (not just focus) — the bar is already focused at launch, so a
        // focus-only refresh would miss history added during the session.
        binding.urlBar.doOnTextChanged { _, _, _, _ -> refreshSuggestions() }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                addressBarActive = true
                // Caret to end so a single RIGHT moves on to the toolbar (⋮ menu) instead of
                // walking through the URL.
                binding.urlBar.setSelection(binding.urlBar.text?.length ?: 0)
                refreshSuggestions()
            } else if (addressBarActive) {
                // Some TV builds consume BACK inside the text stack and only expose the resulting
                // focus loss. Treat any unrequested loss as "leave address bar".
                leaveAddressBar()
            }
        }
        // Start focused on the address bar so the user can immediately type a URL. The cursor is
        // turned on explicitly when DOWN drops into the page (see dispatchKeyEvent), and off again
        // by BACK / long-press-OK / UP-at-top — no reliance on container focus events.
    }

    private fun setCursor(active: Boolean) {
        if (!active) centerLongHandled = false
        cursor.setActive(active)
        if (active && tabManager.hasTabs) {
            addressBarActive = false
            engine.view.requestFocus()
        } else {
            focusAddressBar()
        }
    }

    /** Map the 1..5 speed slider to a cursor movement multiplier (3 = normal). */
    private fun speedFactor(i: Int): Float = when (i) {
        1 -> 0.5f; 2 -> 0.75f; 4 -> 1.5f; 5 -> 2.0f; else -> 1.0f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun wireChrome() = with(binding) {
        btnBack.setOnClickListener { if (engine.canGoBack()) engine.goBack() }
        btnForward.setOnClickListener { if (engine.canGoForward()) engine.goForward() }
        btnReload.setOnClickListener { engine.reload() }
        btnGo.setOnClickListener { navigateFromBar() }
        btnCursor.setOnClickListener { setCursor(!cursor.active) }
        btnVoice.setOnClickListener { launchVoiceSearch() }
        btnZoomIn.setOnClickListener { engine.zoomIn() }
        btnZoomOut.setOnClickListener { engine.zoomOut() }
        btnMenu.setOnClickListener { showMenu() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateFromBar(); true
            } else false
        }
    }

    private fun wireHome() = with(binding) {
        homeSearchGo.setOnClickListener { navigateFromHome() }
        homeVoice.setOnClickListener { launchVoiceSearch() }
        homeSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateFromHome(); true
            } else false
        }
        bindTvKeyboard(homeSearch)
        bindTvKeyboard(urlBar)
    }

    /** TV text fields do not consistently summon an IME from DPAD_CENTER without an explicit request. */
    private fun bindTvKeyboard(editor: EditText) {
        editor.setOnClickListener { showTvKeyboard(editor) }
        editor.setOnKeyListener { _, keyCode, event ->
            val enter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!enter) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) showTvKeyboard(editor)
            true
        }
    }

    private fun showTvKeyboard(editor: EditText) {
        addressBarActive = editor === binding.urlBar
        editor.requestFocus()
        editor.selectAll()
        editor.post {
            if (!editor.isAttachedToWindow) return@post
            editor.requestFocus()
            val input = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.restartInput(editor)
            input.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            WindowInsetsControllerCompat(window, editor).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun wireErrorPanel() = with(binding) {
        errorRetry.setOnClickListener {
            val failure = currentFailure ?: return@setOnClickListener
            hideErrorPanel()
            engine.loadUrl(failure.url)
        }
        errorBack.setOnClickListener {
            hideErrorPanel()
            if (engine.canGoBack()) engine.goBack() else showHome()
        }
        errorHome.setOnClickListener { showHome() }
    }

    private fun navigateFromHome() {
        val query = binding.homeSearch.text?.toString().orEmpty().trim()
        if (query.isEmpty()) return
        hideHome()
        engine.loadUrl(UrlUtils.toUrlOrSearch(query, searchTemplate))
        if (directNav) engine.view.requestFocus() else setCursor(true)
    }

    private fun showHome() {
        if (tabManager.activeUrl() != HOME_URL) engine.loadUrl(HOME_URL)
        homeVisible = true
        currentFailure = null
        cursor.setActive(false)
        binding.errorPanel.visibility = View.GONE
        binding.homePanel.visibility = View.VISIBLE
        binding.urlBar.setText("")
        refreshHome()
        binding.homeSearch.requestFocus()
    }

    private fun hideHome() {
        homeVisible = false
        binding.homePanel.visibility = View.GONE
    }

    private fun showErrorPanel(failure: PageFailure) {
        currentFailure = failure
        homeVisible = false
        if (binding.errorPanel.visibility != View.VISIBLE) cursorWasOnBeforeError = cursor.active
        cursor.setActive(false)
        binding.homePanel.visibility = View.GONE
        binding.errorPanel.visibility = View.VISIBLE
        val titleRes = when (failure.kind) {
            PageFailureKind.OFFLINE -> R.string.error_offline_title
            PageFailureKind.DNS -> R.string.error_dns_title
            PageFailureKind.TIMEOUT -> R.string.error_timeout_title
            PageFailureKind.CONNECTION -> R.string.error_connection_title
            PageFailureKind.SSL -> R.string.error_ssl_title
            PageFailureKind.RENDERER_CRASH -> R.string.error_renderer_title
        }
        binding.errorTitle.setText(titleRes)
        val detail = failure.detail.ifBlank { getString(titleRes) }
        binding.errorMessage.text = getString(R.string.error_message_with_url, detail, failure.url)
        binding.errorRetry.requestFocus()
    }

    private fun hideErrorPanel() {
        currentFailure = null
        val wasShowing = binding.errorPanel.visibility == View.VISIBLE
        binding.errorPanel.visibility = View.GONE
        if (wasShowing && cursorWasOnBeforeError && !homeVisible) cursor.setActive(true)
        cursorWasOnBeforeError = false
    }

    private var cursorWasOnBeforeError = false
    private var renderedHome: RenderedHome? = null
    private var homeLayoutPending = false

    private data class RenderedHome(val bookmarks: List<HomeTile>, val recent: List<HomeTile>, val columns: Int)

    private fun syncHomeVisibility(requestFocus: Boolean = false) {
        val isHome = tabManager.activeUrl().isBlank() || tabManager.activeUrl() == HOME_URL
        if (isHome) {
            homeVisible = true
            cursor.setActive(false)
            binding.homePanel.visibility = View.VISIBLE
            refreshHome()
            if (requestFocus) binding.homeSearch.requestFocus()
        } else {
            hideHome()
            if (requestFocus) focusAddressBar()
        }
    }

    private fun refreshHome() {
        val bookmarkGrid = binding.homeBookmarkGrid
        val recentGrid = binding.homeRecentGrid
        val gridWidth = bookmarkGrid.width
        if (gridWidth <= 0) {
            if (!homeLayoutPending) {
                homeLayoutPending = true
                // Rebuild after this layout pass, not inside it: tiles added from a layout callback
                // leave the grids measured as empty, so the tiles are clipped out of view.
                bookmarkGrid.doOnLayout {
                    bookmarkGrid.post {
                        homeLayoutPending = false
                        if (homeVisible) refreshHome()
                    }
                }
            }
            return
        }
        val columns = HomeContent.columns(gridWidth, dp(HOME_TILE_WIDTH_DP + 2 * HOME_TILE_MARGIN_DP))
        val bookmarkTiles = store.bookmarks.take(HOME_TILE_LIMIT).map { HomeTile(it.title, it.url) }
        val recentTiles = if (isIncognito) emptyList() else HomeContent.recent(store.history, HOME_TILE_LIMIT)
        val next = RenderedHome(bookmarkTiles, recentTiles, columns)
        if (next == renderedHome) return
        renderedHome = next

        val focusedGrid = listOf(bookmarkGrid, recentGrid).firstOrNull { it.hasFocus() }
        val focusedIndex = focusedGrid?.let { grid -> (0 until grid.childCount).firstOrNull { grid.getChildAt(it).hasFocus() } }
        bookmarkGrid.removeAllViews()
        recentGrid.removeAllViews()
        bookmarkGrid.columnCount = columns
        recentGrid.columnCount = columns
        bookmarkTiles.forEach { addHomeTile(bookmarkGrid, it, true) }
        recentTiles.forEach { addHomeTile(recentGrid, it, false) }
        if (bookmarkGrid.childCount == 0) addHomeEmpty(bookmarkGrid)
        if (recentGrid.childCount == 0) addHomeEmpty(recentGrid)
        if (focusedGrid != null && focusedIndex != null) {
            focusedGrid.getChildAt(focusedIndex.coerceAtMost(focusedGrid.childCount - 1))?.requestFocus()
        }
    }

    private fun addHomeTile(grid: GridLayout, tile: HomeTile, bookmark: Boolean) {
        val host = runCatching { tile.url.toUri().host.orEmpty() }.getOrDefault("")
        val button = Button(this).apply {
            text = tile.title.take(34) + if (host.isNotBlank()) "\n$host" else ""
            isAllCaps = false
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setTextColor(Color.WHITE)
            textSize = 17f
            maxLines = 2
            setBackgroundResource(R.drawable.tv_tab_background)
            contentDescription = (if (bookmark) "Bookmark: " else "Recent site: ") +
                "${tile.title}, $host"
            setOnClickListener {
                hideHome()
                engine.loadUrl(tile.url)
                if (directNav) engine.view.requestFocus() else setCursor(true)
            }
        }
        grid.addView(button, GridLayout.LayoutParams().apply {
            width = dp(HOME_TILE_WIDTH_DP)
            height = dp(96)
            setMargins(dp(HOME_TILE_MARGIN_DP), dp(6), dp(HOME_TILE_MARGIN_DP), dp(6))
        })
    }

    private fun addHomeEmpty(grid: GridLayout) {
        grid.addView(TextView(this).apply {
            text = getString(R.string.home_empty)
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 17f
            setPadding(dp(8), dp(14), dp(8), dp(14))
        })
    }

    private fun navigateFromBar() {
        val input = binding.urlBar.text.toString().trim()
        if (input.isEmpty()) return
        val target = UrlUtils.toUrlOrSearch(input, searchTemplate)
        hideHome()
        hideErrorPanel()
        engine.loadUrl(target)
        addressBarActive = false
        binding.urlBar.clearFocus()
        if (directNav) {
            cursor.setActive(false)
            engine.view.requestFocus()
        } else {
            setCursor(true)
        }
    }

    private fun showMenu() {
        val url = engine.currentUrl()
        val bookmarked = url != null && store.isBookmarked(url)
        val actions = buildList {
            if (!isIncognito) add(getString(R.string.settings_title) to ::showSettings)
            addAll(listOf(
            getString(R.string.menu_tabs) to ::showTabSwitcher,
            getString(R.string.menu_home) to ::showHome,
            getString(R.string.menu_bookmarks) to ::showBookmarks,
            getString(R.string.menu_downloads) to ::showDownloads,
            getString(R.string.menu_new_tab) to ::openNewTab,
            getString(R.string.menu_close_tab) to { tabManager.closeActive() },
            getString(R.string.menu_voice) to ::launchVoiceSearch,
            getString(R.string.menu_zoom_in) to { engine.zoomIn() },
            getString(R.string.menu_zoom_out) to { engine.zoomOut() }
            ))
            if (!isIncognito) {
                if (tabManager.hasClosedTab()) {
                    add(getString(R.string.menu_reopen_closed_tab) to { tabManager.reopenClosedTab() })
                }
                add(3, getString(R.string.menu_history) to ::showHistory)
                add(getString(if (bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark) to
                    ::toggleBookmark)
                add(getString(R.string.menu_new_private_tab) to ::openPrivateTab)
                add(getString(R.string.menu_clear_browsing_data) to ::confirmClearBrowsingData)
                add(getString(R.string.menu_about) to ::showDiagnostics)
                updateController.pendingVersionName?.let {
                    add(getString(R.string.menu_update_pending, it) to { updateController.showPending() })
                }
                add(getString(R.string.menu_update) to { updateController.check(manual = true) })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (isIncognito) R.string.incognito_title else R.string.action_menu)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun openNewTab() {
        tabManager.newTab(HOME_URL)
        binding.urlBar.setText("")
        showHome()
    }

    private fun openPrivateTab() {
        startActivity(Intent(this, IncognitoBrowserActivity::class.java))
    }

    private fun applyBrowsingSettings(browser: BrowserEngine, host: String?) {
        val resolved = SiteBrowsingPolicy.resolveForHost(siteBrowsingSettings, host, desktopMode)
        browser.setBrowsingMode(
            desktopIdentity = resolved.desktopMode,
            useWideViewPort = resolved.useWideViewPort,
            loadWithOverviewMode = resolved.loadWithOverviewMode
        )
        if (::tabManager.isInitialized && tabManager.hasTabs && browser === tabManager.activeEngine) {
            activeBrowsingSettings = resolved
        }
    }

    private fun showDiagnostics() {
        val webView = engine.view as? WebView ?: return
        webView.evaluateJavascript(
            "(function(){return JSON.stringify({fullscreenEnabled:!!document.fullscreenEnabled});})()"
        ) { raw ->
            val fullscreenEnabled = runCatching {
                val json = org.json.JSONTokener(raw).nextValue() as? String
                org.json.JSONObject(json.orEmpty()).optBoolean("fullscreenEnabled")
            }.getOrNull()
            val webViewPackage = WebViewCompat.getCurrentWebViewPackage(this)
            val hints = if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                runCatching { WebSettingsCompat.getUserAgentMetadata(webView.settings) }.getOrNull()
            } else null
            val report = SupportReportBuilder.build(
                SupportReportInput(
                    cometVersionName = BuildConfig.VERSION_NAME,
                    cometVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    webViewPackage = webViewPackage?.packageName,
                    webViewVersion = webViewPackage?.versionName,
                    androidVersion = Build.VERSION.RELEASE,
                    androidSdk = Build.VERSION.SDK_INT,
                    deviceManufacturer = Build.MANUFACTURER,
                    deviceModel = Build.MODEL,
                    activeIdentity = activeBrowsingSettings.identity.name.replace('_', '-'),
                    viewport = SupportViewport(
                        mode = activeBrowsingSettings.viewport.name.replace('_', '-'),
                        useWideViewPort = webView.settings.useWideViewPort,
                        loadWithOverviewMode = webView.settings.loadWithOverviewMode
                    ),
                    userAgent = webView.settings.userAgentString,
                    clientHints = hints?.let { metadata ->
                        UserAgentClientHints(
                            brands = metadata.brandVersionList.map {
                                UserAgentBrand(it.brand, it.majorVersion)
                            },
                            platform = metadata.platform,
                            platformVersion = metadata.platformVersion,
                            architecture = metadata.architecture,
                            model = metadata.model,
                            mobile = metadata.isMobile,
                            bitness = metadata.bitness,
                            wow64 = metadata.isWow64
                        )
                    },
                    fullscreenEnabled = fullscreenEnabled,
                    currentUrl = engine.currentUrl(),
                    device = deviceFacts()
                )
            )
            val text = report.toCopyText()
            AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setMessage(text)
                .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Comet support report", text))
                    toast(R.string.diagnostics_copied)
                }
                .setNegativeButton(R.string.action_close, null)
                .show()
        }
    }

    private fun deviceFacts(): SupportDevice {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return SupportDevice(
            is64BitProcess = android.os.Process.is64Bit(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            memoryClassMb = am.memoryClass,
            largeMemoryClassMb = am.largeMemoryClass,
            lowRamDevice = am.isLowRamDevice,
            totalRamMb = mem.totalMem / (1024 * 1024)
        )
    }

    private fun confirmClearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setPositiveButton(R.string.clear_data_action) { _, _ -> clearBrowsingData() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun clearBrowsingData(onComplete: (() -> Unit)? = null) {
        cancelPermissionPrompt(deny = true)
        permissionPolicy.clearSessionGrants()
        if (::tabManager.isInitialized) {
            tabManager.forEachEngine { browser ->
                (browser.view as? WebView)?.apply {
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                }
            }
        }
        WebStorage.getInstance().deleteAllData()
        GeolocationPermissions.getInstance().clearAll()
        WebViewDatabase.getInstance(this).apply {
            clearFormData()
            clearHttpAuthUsernamePassword()
            clearUsernamePassword()
        }
        WebView.clearClientCertPreferences(null)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            if (!isIncognito) {
                store.clearHistory()
                tabManager.destroyAll()
                tabManager.newTab(HOME_URL)
                binding.urlBar.setText("")
                showHome()
                toast(R.string.clear_data_done)
            }
            onComplete?.invoke()
        }
    }

    private fun onTabsChanged() {
        currentFailure?.takeIf { it.url != tabManager.activeUrl() }?.let { hideErrorPanel() }
        refreshTabStrip()
        persistTabs()
        syncHomeVisibility()
        if (binding.tabSwitcherOverlay.visibility == View.VISIBLE) rebuildTabSwitcher()
    }

    /** Rebuild the tab strip; shown only when more than one tab is open. */
    private fun refreshTabStrip() {
        val strip = binding.tabStrip
        val count = tabManager.count
        val focusedIndex = (0 until strip.childCount).firstOrNull { strip.getChildAt(it).hasFocus() }
        binding.tabStripScroll.visibility = if (count > 1) View.VISIBLE else View.GONE
        strip.removeAllViews()
        if (count <= 1) return
        val active = tabManager.activePosition()
        val titles = tabManager.titles()
        val displayedIndices = if (count <= MAX_COMPACT_TABS) {
            titles.indices.toList()
        } else {
            ((0 until MAX_COMPACT_TABS - 2) + active + (count - 1)).distinct().sorted()
        }
        displayedIndices.forEach { tabIndex ->
            val title = titles[tabIndex]
            val button = Button(this).apply {
                tag = tabIndex
                text = title.take(24)
                isAllCaps = false
                minWidth = resources.displayMetrics.density.times(120).toInt()
                minHeight = resources.displayMetrics.density.times(52).toInt()
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.tv_tab_background)
                isSelected = tabIndex == active
                contentDescription = getString(
                    R.string.tab_description,
                    Integer.valueOf(tabIndex + 1),
                    Integer.valueOf(count),
                    title
                )
                if (isSelected) setTypeface(typeface, Typeface.BOLD)
                if (tabManager.isSuspended(tabIndex)) {
                    alpha = 0.6f
                    contentDescription = contentDescription.toString() + ". " +
                        getString(R.string.tab_reloads_when_opened)
                }
                setOnClickListener { tabManager.select(tabIndex) }
                setOnLongClickListener { tabManager.closeTab(tabIndex); true }
            }
            strip.addView(button)
        }
        val addButton = Button(this).apply {
            text = "+"
            minHeight = resources.displayMetrics.density.times(52).toInt()
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.tv_tab_background)
            contentDescription = getString(R.string.menu_new_tab)
            setOnClickListener { openNewTab() }
        }
        strip.addView(addButton)
        focusedIndex?.coerceAtMost(strip.childCount - 1)?.let { strip.getChildAt(it).requestFocus() }
    }

    private fun showTabSwitcher() {
        hideSoftKeyboard()
        cursor.setActive(false)
        binding.tabSwitcherOverlay.visibility = View.VISIBLE
        rebuildTabSwitcher()
    }

    private fun dismissTabSwitcher(focusMenu: Boolean = true) {
        binding.tabSwitcherOverlay.visibility = View.GONE
        binding.tabSwitcherList.removeAllViews()
        if (focusMenu) binding.btnMenu.requestFocus()
    }

    private fun rebuildTabSwitcher() {
        val list = binding.tabSwitcherList
        // Remember which card/action had focus so a background tab update doesn't move it.
        val oldCards = (list.childCount - 1).coerceAtLeast(0)
        var focusedCard: Int? = null
        var focusedClose = false
        var focusedNewTab = false
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (!child.hasFocus()) continue
            if (i >= oldCards) focusedNewTab = true else {
                focusedCard = i
                focusedClose = (child as? ViewGroup)?.getChildAt(4)?.hasFocus() == true
            }
        }
        list.removeAllViews()
        val previews = tabManager.previews(TAB_THUMB_WIDTH, TAB_THUMB_HEIGHT)
        val openButtons = mutableListOf<Button>()
        val closeButtons = mutableListOf<Button?>()

        previews.forEach { preview ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundResource(R.drawable.tv_tab_background)
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(288), dp(162))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF20283A.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                preview.thumbnail?.let(::setImageBitmap)
                if (preview.suspended) alpha = 0.5f
            }
            val title = TextView(this).apply {
                text = preview.title.take(36)
                setTextColor(Color.WHITE)
                textSize = 18f
                maxLines = 1
                setPadding(0, dp(10), 0, dp(2))
            }
            val url = TextView(this).apply {
                text = runCatching { preview.url.toUri().host }.getOrNull() ?: preview.url.take(38)
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 14f
                maxLines = 1
            }
            val reloadNote = TextView(this).apply {
                text = getString(R.string.tab_reloads_when_opened)
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 14f
                maxLines = 1
                visibility = if (preview.suspended) View.VISIBLE else View.INVISIBLE
            }
            val open = Button(this).apply {
                id = View.generateViewId()
                text = if (preview.active) getString(R.string.tab_open) + " •" else getString(R.string.tab_open)
                isAllCaps = false
                contentDescription = getString(R.string.tab_description, preview.index + 1, previews.size, preview.title)
                setOnClickListener {
                    tabManager.select(preview.index)
                    dismissTabSwitcher(focusMenu = false)
                    syncHomeVisibility(requestFocus = true)
                }
            }
            val close = Button(this).apply {
                id = View.generateViewId()
                text = getString(R.string.menu_close_tab)
                isAllCaps = false
                contentDescription = getString(R.string.menu_close_tab) + ": " + preview.title
                setOnClickListener {
                    tabManager.closeTab(preview.index)
                }
            }
            card.addView(image)
            card.addView(title)
            card.addView(url)
            card.addView(reloadNote)
            card.addView(open, LinearLayout.LayoutParams(dp(288), dp(52)).apply { topMargin = dp(10) })
            card.addView(close, LinearLayout.LayoutParams(dp(288), dp(52)).apply { topMargin = dp(6) })
            list.addView(card, LinearLayout.LayoutParams(dp(308), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(18)
            })
            openButtons += open
            closeButtons += close
        }

        val newTab = Button(this).apply {
            id = View.generateViewId()
            text = "+\n" + getString(R.string.menu_new_tab)
            isAllCaps = false
            textSize = 20f
            setBackgroundResource(R.drawable.tv_tab_background)
            setOnClickListener {
                openNewTab()
                dismissTabSwitcher(focusMenu = false)
            }
        }
        list.addView(newTab, LinearLayout.LayoutParams(dp(240), dp(220)))
        openButtons += newTab
        closeButtons += null

        openButtons.forEachIndexed { index, button ->
            button.nextFocusLeftId = openButtons[(index - 1).coerceAtLeast(0)].id
            button.nextFocusRightId = openButtons[(index + 1).coerceAtMost(openButtons.lastIndex)].id
            button.nextFocusDownId = closeButtons[index]?.id ?: button.id
            button.nextFocusUpId = button.id
            closeButtons[index]?.let { close ->
                close.nextFocusUpId = button.id
                close.nextFocusDownId = close.id
                close.nextFocusLeftId = closeButtons.getOrNull(index - 1)?.id ?: close.id
                close.nextFocusRightId = closeButtons.getOrNull(index + 1)?.id ?: close.id
            }
        }
        val target = when {
            focusedNewTab -> newTab
            focusedCard == null -> openButtons.getOrNull(tabManager.activePosition())
            previews.size < oldCards -> openButtons.getOrNull(focusedCard.coerceAtMost(previews.size - 1))
            focusedClose -> closeButtons.getOrNull(focusedCard) ?: openButtons.getOrNull(focusedCard)
            else -> openButtons.getOrNull(focusedCard)
        }
        target?.requestFocus()
    }

    private fun toggleBookmark() {
        val url = engine.currentUrl() ?: return
        if (url == HOME_URL) return
        if (store.isBookmarked(url)) {
            store.removeBookmark(url)
            toast(R.string.bookmark_removed)
        } else {
            store.addBookmark(engine.currentTitle() ?: url, url)
            toast(R.string.bookmark_added)
        }
        if (homeVisible) refreshHome()
    }

    private fun showBookmarks() {
        val items = store.bookmarks.toList()
        if (items.isEmpty() && isIncognito) {
            toast(R.string.bookmarks_empty); return
        }
        val builder = AlertDialog.Builder(this).setTitle(R.string.menu_bookmarks)
        if (items.isEmpty()) builder.setMessage(R.string.bookmarks_empty)
        else builder.setItems(items.map { it.title }.toTypedArray()) { _, which -> showBookmarkActions(items[which].url) }
        if (!isIncognito) {
            builder.setNeutralButton(R.string.bookmarks_export) { _, _ -> exportBookmarks() }
            builder.setPositiveButton(R.string.bookmarks_import) { _, _ -> startBookmarkImport() }
        }
        builder.setNegativeButton(R.string.action_close, null).show()
    }

    /** Open, rename, reorder or delete one bookmark. Moving keeps this dialog open so a bookmark can be moved several places. */
    private fun showBookmarkActions(url: String) {
        val index = store.bookmarks.indexOfFirst { it.url == url }
        if (index < 0) { showBookmarks(); return }
        val bookmark = store.bookmarks[index]
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.bookmark_open) to { engine.loadUrl(url) })
            add(getString(R.string.bookmark_rename) to { showRenameBookmark(bookmark) })
            if (index > 0) add(getString(R.string.bookmark_move_up) to {
                store.moveBookmark(url, -1); onBookmarksChanged(); showBookmarkActions(url)
            })
            if (index < store.bookmarks.lastIndex) add(getString(R.string.bookmark_move_down) to {
                store.moveBookmark(url, 1); onBookmarksChanged(); showBookmarkActions(url)
            })
            add(getString(R.string.bookmark_delete) to {
                store.removeBookmark(url); onBookmarksChanged(); toast(R.string.bookmark_removed); showBookmarks()
            })
        }
        AlertDialog.Builder(this)
            .setTitle(bookmark.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_back) { _, _ -> showBookmarks() }
            .setOnCancelListener { showBookmarks() }
            .show()
    }

    private fun onBookmarksChanged() {
        if (homeVisible) refreshHome()
    }

    private fun showRenameBookmark(bookmark: com.tdarby.comet.data.SiteItem) {
        val input = EditText(this).apply {
            setText(bookmark.title)
            setSingleLine()
            selectAll()
        }
        bindTvKeyboard(input)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmark_rename)
            .setView(box)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                store.renameBookmark(bookmark.url, input.text.toString())
                onBookmarksChanged()
                showBookmarkActions(bookmark.url)
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showBookmarkActions(bookmark.url) }
            .setOnCancelListener { showBookmarkActions(bookmark.url) }
            .show()
    }

    private val bookmarkImportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { importBookmarksFrom(it) }
        }

    private fun longToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun exportBookmarks() {
        if (isIncognito) return
        val list = store.bookmarks.toList()
        if (list.isEmpty()) { toast(R.string.bookmarks_empty); return }
        val write = {
            val name = BookmarkFiles.newFileName()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    BookmarkFiles.save(this@BrowserActivity, name, BookmarkTransfer.export(list, BookmarkFiles.isoDate()))
                }
                if (ok) longToast(getString(R.string.bookmarks_exported, list.size, name))
                else toast(R.string.bookmarks_export_failed)
            }
            Unit
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ensureOsPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                if (granted) write() else toast(R.string.download_failed_storage)
            }
        } else write()
    }

    /** Uses the system file picker when the TV has one; otherwise lists Comet exports found in Downloads. */
    private fun startBookmarkImport() {
        if (isIncognito) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            bookmarkImportLauncher.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            showBookmarkExportFiles()
        }
    }

    private fun showBookmarkExportFiles() {
        val list = {
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) { BookmarkFiles.listExports(this@BrowserActivity) }
                if (files.isEmpty()) { toast(R.string.bookmarks_import_none); return@launch }
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle(R.string.bookmarks_import)
                    .setItems(files.map { it.name }.toTypedArray()) { _, which -> importBookmarksFrom(files[which].uri) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            Unit
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ensureOsPermissions(listOf(Manifest.permission.READ_EXTERNAL_STORAGE)) { granted ->
                if (granted) list() else toast(R.string.download_failed_storage)
            }
        } else list()
    }

    private fun importBookmarksFrom(uri: Uri) {
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { BookmarkFiles.read(this@BrowserActivity, uri) }
            if (read == null) { toast(R.string.bookmarks_import_failed); return@launch }
            when (val parsed = BookmarkTransfer.parse(read.text, read.byteLength)) {
                is BookmarkTransfer.Parsed.Rejected -> toast(
                    if (parsed.reason == BookmarkTransfer.Failure.TOO_LARGE) R.string.bookmarks_import_too_large
                    else R.string.bookmarks_import_invalid
                )
                is BookmarkTransfer.Parsed.Ok -> {
                    val merge = store.importBookmarks(parsed.items)
                    onBookmarksChanged()
                    val skipped = parsed.skipped + merge.overLimit
                    longToast(
                        getString(R.string.bookmarks_import_result, merge.added, merge.alreadySaved) +
                            if (skipped > 0) getString(R.string.bookmarks_import_skipped, skipped) else ""
                    )
                }
            }
        }
    }

    private fun showHistory() {
        val items = store.history.toList()
        if (items.isEmpty()) {
            toast(R.string.history_empty); return
        }
        val labels = items.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_history)
            .setItems(labels) { _, which -> engine.loadUrl(items[which].url) }
            .setNeutralButton(R.string.history_clear) { _, _ -> store.clearHistory() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // Saving to the public Downloads folder needs the storage permission on API 28 only
        // (DownloadManager enqueue and the pre-Q blob save); newer versions need none.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ensureOsPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                if (granted) startDownload(url, userAgent, contentDisposition, mimeType)
                else toast(R.string.download_failed_storage)
            }
            return
        }
        startDownload(url, userAgent, contentDisposition, mimeType)
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // DownloadManager can't fetch blob: URLs — read them in the page instead.
        if (url.startsWith("blob:")) {
            if (tabManager.hasTabs) engine.fetchBlob(url)
            return
        }
        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(url.toUri()).apply {
                setTitle(fileName)
                setMimeType(mimeType)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                // Propagate cookies so session/login-gated downloads succeed.
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.download_started)
        }.onFailure { toast(R.string.download_failed) }
    }

    private data class DownloadRow(
        val id: Long, val title: String, val label: String, val status: Int, val mime: String?
    )

    /** In-app downloads list (snapshot): tap an item for open/install/cancel/delete actions. */
    private fun showDownloads() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val rows = mutableListOf<DownloadRow>()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val iMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val iCur = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTot = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                while (c.moveToNext() && rows.size < 40) {
                    if (hiddenDownloads.isHidden(c.getLong(iId))) continue
                    val status = c.getInt(iStatus)
                    val title = c.getString(iTitle)?.ifBlank { null } ?: "download"
                    val state = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.dl_done)
                        DownloadManager.STATUS_FAILED -> getString(R.string.dl_failed)
                        DownloadManager.STATUS_PAUSED -> getString(R.string.dl_paused)
                        DownloadManager.STATUS_PENDING -> getString(R.string.dl_pending)
                        else -> {
                            val tot = c.getLong(iTot); val cur = c.getLong(iCur)
                            if (tot > 0) getString(R.string.dl_running_pct, (cur * 100 / tot).toInt())
                            else getString(R.string.dl_running)
                        }
                    }
                    val why = downloadReasonText(DownloadReasons.classify(status, c.getInt(iReason)))
                    rows.add(DownloadRow(
                        c.getLong(iId), title, "$title — $state" + (why?.let { " ($it)" } ?: ""), status, c.getString(iMime)
                    ))
                }
            }
        }
        if (rows.isEmpty()) { toast(R.string.dl_empty); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_downloads)
            .setItems(rows.map { it.label }.toTypedArray()) { _, which -> showDownloadActions(rows[which]) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun showDownloadActions(row: DownloadRow) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (row.status == DownloadManager.STATUS_SUCCESSFUL) {
            actions += getString(R.string.dl_open) to { openDownload(dm, row) }
        }
        if (row.status in setOf(
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED
            )
        ) {
            actions += getString(R.string.dl_cancel) to { dm.remove(row.id); Unit }
        }
        val running = row.status in setOf(
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED
        )
        if (!running) {
            actions += getString(R.string.dl_remove_from_list) to { hiddenDownloads.hide(row.id) }
        }
        actions += getString(R.string.dl_delete_file) to { confirmDeleteDownload(dm, row) }
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun confirmDeleteDownload(dm: DownloadManager, row: DownloadRow) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dl_delete_confirm, row.title))
            .setPositiveButton(R.string.dl_delete_file) { _, _ -> dm.remove(row.id); hiddenDownloads.forget(row.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun downloadReasonText(reason: DownloadReason): String? = when (reason) {
        DownloadReason.NONE -> null
        DownloadReason.NO_SPACE -> getString(R.string.dl_reason_no_space)
        DownloadReason.NETWORK_DATA -> getString(R.string.dl_reason_network_data)
        DownloadReason.CANNOT_RESUME -> getString(R.string.dl_reason_cannot_resume)
        DownloadReason.STORAGE_MISSING -> getString(R.string.dl_reason_storage_missing)
        DownloadReason.FILE_EXISTS -> getString(R.string.dl_reason_file_exists)
        DownloadReason.FILE_ERROR -> getString(R.string.dl_reason_file_error)
        DownloadReason.TOO_MANY_REDIRECTS -> getString(R.string.dl_reason_redirects)
        DownloadReason.SERVER_ERROR -> getString(R.string.dl_reason_server_error)
        DownloadReason.NOT_FOUND -> getString(R.string.dl_reason_not_found)
        DownloadReason.ACCESS_DENIED -> getString(R.string.dl_reason_access_denied)
        DownloadReason.WAITING_NETWORK -> getString(R.string.dl_reason_waiting_network)
        DownloadReason.WAITING_WIFI -> getString(R.string.dl_reason_waiting_wifi)
        DownloadReason.WAITING_RETRY -> getString(R.string.dl_reason_waiting_retry)
        DownloadReason.UNKNOWN -> getString(R.string.dl_reason_unknown)
    }

    private fun openDownload(dm: DownloadManager, row: DownloadRow) {
        val uri = runCatching { dm.getUriForDownloadedFile(row.id) }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, row.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure { toast(R.string.external_failed) }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun showSettings() {
        val dlg = DialogSettingsBinding.inflate(layoutInflater)
        dlg.switchDesktop.isChecked = desktopMode

        val siteModeLabels = arrayOf(
            getString(R.string.settings_mode_default),
            getString(R.string.settings_mode_mobile_tv),
            getString(R.string.settings_mode_desktop)
        )
        val siteModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, siteModeLabels)
        siteModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dlg.spinnerSiteIdentity.adapter = siteModeAdapter
        dlg.spinnerSiteViewport.adapter = siteModeAdapter

        val searchAdapter = ArrayAdapter.createFromResource(
            this, R.array.search_engine_labels, android.R.layout.simple_spinner_item
        )
        searchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dlg.spinnerSearch.adapter = searchAdapter
        dlg.spinnerSearch.setSelection(SearchEngines.indexOf(searchTemplate))

        dlg.switchBlockNetwork.isChecked = AdBlocker.networkEnabled
        dlg.switchBlockCosmetic.isChecked = AdBlocker.cosmeticEnabled
        dlg.switchBlockPopups.isChecked = AdBlocker.popupEnabled
        dlg.switchBlockRedirects.isChecked = AdBlocker.redirectBlockEnabled
        dlg.switchDirectNav.isChecked = directNav
        dlg.seekCursorSpeed.progress = (cursorSpeedSetting - 1).coerceIn(0, 4)
        dlg.switchDesktop.contentDescription = getString(R.string.settings_desktop)
        dlg.spinnerSearch.contentDescription = getString(R.string.settings_search)
        dlg.switchBlockNetwork.contentDescription = getString(R.string.settings_block_network)
        dlg.switchBlockCosmetic.contentDescription = getString(R.string.settings_block_cosmetic)
        dlg.switchBlockPopups.contentDescription = getString(R.string.settings_block_popups)
        dlg.switchBlockRedirects.contentDescription = getString(R.string.settings_block_redirects)
        dlg.switchDirectNav.contentDescription = getString(R.string.settings_direct_nav)
        dlg.seekCursorSpeed.contentDescription = getString(R.string.settings_cursor_speed)

        val host = currentHost
        val storedSiteSettings = SiteBrowsingPolicy.settingsForHost(siteBrowsingSettings, host)
            ?: SiteBrowsingSettings()
        dlg.spinnerSiteIdentity.setSelection(storedSiteSettings.identity.ordinal)
        dlg.spinnerSiteViewport.setSelection(storedSiteSettings.viewport.ordinal)
        dlg.spinnerSiteIdentity.isEnabled = host != null
        dlg.spinnerSiteViewport.isEnabled = host != null
        dlg.siteBrowsingLabel.text = if (host != null) {
            getString(R.string.settings_site_browsing_host, host)
        } else getString(R.string.settings_site_browsing)
        dlg.spinnerSiteIdentity.contentDescription = getString(R.string.settings_site_identity)
        dlg.spinnerSiteViewport.contentDescription = getString(R.string.settings_site_viewport)
        dlg.switchAllowSite.isEnabled = host != null
        dlg.switchAllowSite.isChecked = AdBlocker.isAllowlisted(host)
        dlg.allowSiteLabel.text =
            if (host != null) getString(R.string.settings_allow_site_host, host)
            else getString(R.string.settings_allow_site)
        dlg.switchAllowSite.contentDescription = dlg.allowSiteLabel.text

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dlg.root)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val blockingBefore = blockingState()
                val newDesktop = dlg.switchDesktop.isChecked
                desktopMode = newDesktop
                searchTemplate = SearchEngines.templateAt(dlg.spinnerSearch.selectedItemPosition)

                AdBlocker.networkEnabled = dlg.switchBlockNetwork.isChecked
                AdBlocker.cosmeticEnabled = dlg.switchBlockCosmetic.isChecked
                AdBlocker.popupEnabled = dlg.switchBlockPopups.isChecked
                AdBlocker.redirectBlockEnabled = dlg.switchBlockRedirects.isChecked
                directNav = dlg.switchDirectNav.isChecked
                cursorSpeedSetting = dlg.seekCursorSpeed.progress + 1
                cursor.speedFactor = speedFactor(cursorSpeedSetting)
                tabManager.forEachEngine {
                    it.setBlockingEnabled(AdBlocker.networkEnabled)
                    it.applyPopupPolicy()
                }

                val allowChecked = dlg.switchAllowSite.isChecked
                val newSiteSettings = SiteBrowsingSettings(
                    identity = BrowsingIdentity.entries[dlg.spinnerSiteIdentity.selectedItemPosition],
                    viewport = BrowsingViewport.entries[dlg.spinnerSiteViewport.selectedItemPosition]
                )
                if (host != null) {
                    if (allowChecked) AdBlocker.setAllowlist(AdBlocker.currentAllowlist() + host)
                    else {
                        val covering = AdBlocker.coveringEntries(AdBlocker.currentAllowlist(), host)
                        AdBlocker.setAllowlist(AdBlocker.currentAllowlist() - covering)
                        covering.filter { it != host }.minByOrNull { it.length }?.let { parent ->
                            Toast.makeText(
                                this, getString(R.string.allowlist_parent_removed, parent), Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    tabManager.forEachEngine { it.applyPopupPolicy() }
                    siteBrowsingSettings = siteBrowsingSettings.toMutableMap().apply {
                        if (newSiteSettings.isDefault) remove(host) else put(host, newSiteSettings)
                    }
                }

                lifecycleScope.launch {
                    settings.setDesktopMode(newDesktop)
                    settings.setSearchTemplate(searchTemplate)
                    settings.setBlockNetwork(AdBlocker.networkEnabled)
                    settings.setBlockCosmetic(AdBlocker.cosmeticEnabled)
                    settings.setBlockPopups(AdBlocker.popupEnabled)
                    settings.setBlockRedirects(AdBlocker.redirectBlockEnabled)
                    settings.setDirectNav(directNav)
                    settings.setCursorSpeed(cursorSpeedSetting)
                    if (host != null) settings.setSiteAllowlisted(host, allowChecked)
                    if (host != null) settings.setSiteBrowsingSettings(host, newSiteSettings)
                }

                tabManager.forEachEngine { browser ->
                    applyBrowsingSettings(browser, AdBlocker.hostOf(browser.currentUrl()))
                }
                // The popup guard and cosmetic CSS already injected stay in a loaded page, so a
                // blocking or allowlist change needs a reload; other settings don't reload.
                if (blockingState() != blockingBefore) {
                    // Reload the page in front now; other loaded tabs reload when next selected.
                    val reloadable = { url: String? -> !url.isNullOrBlank() && url != HOME_URL }
                    if (reloadable(engine.currentUrl())) engine.reload()
                    tabManager.markStale(reloadable)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        dlg.switchDesktop.requestFocus()
    }

    private fun blockingState() = listOf(
        AdBlocker.networkEnabled, AdBlocker.cosmeticEnabled, AdBlocker.popupEnabled,
        AdBlocker.redirectBlockEnabled, AdBlocker.currentAllowlist()
    )

    /**
     * In cursor mode the D-pad drives the on-screen pointer instead of moving focus.
     * OK (center) taps; a long-press of OK toggles the cursor off to reach the toolbar.
     * Handled in dispatchKeyEvent so it works regardless of which inner view holds focus.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::tabManager.isInitialized || !tabManager.hasTabs) return super.dispatchKeyEvent(event)
        // EditText consumes BACK before OnBackPressedDispatcher on some Android TV builds. Catch it
        // here so URL -> chrome is deterministic on five-button remotes.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && binding.urlBar.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) leaveAddressBar()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in REMOTE_DIRECTION_KEYS) {
            resetExitSequence()
        }

        // Remote media keys drive the page's <video>/<audio> regardless of cursor/toolbar focus.
        if (MediaKeys.isMediaKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && tabManager.hasTabs) {
                MediaKeys.actionFor(event.keyCode, event.repeatCount)?.let { engine.mediaAction(it) }
            }
            return true
        }
        // In fullscreen video, let the now-focused player view handle D-pad/OK natively.
        if (isFullscreen) return super.dispatchKeyEvent(event)
        if (binding.tabSwitcherOverlay.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) launchVoiceSearch()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }

        // Toolbar mode (cursor off): D-pad does normal focus navigation, with two tweaks so the
        // page and the ⋮ menu stay reachable from the address bar (an EditText that otherwise traps
        // LEFT/RIGHT for caret movement — the "walk through every character" problem).
        if (!cursor.active) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // DOWN from anywhere in the top bar drops into the page and shows the cursor again.
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && binding.topBar.hasFocus()) {
                    if (binding.urlBar.hasFocus() && binding.urlBar.isPopupShowing) {
                        return super.dispatchKeyEvent(event)
                    }
                    if (homeVisible) {
                        binding.homeSearch.requestFocus()
                    } else if (binding.errorPanel.visibility == View.VISIBLE) {
                        binding.errorRetry.requestFocus()
                    } else if (binding.tabStripScroll.visibility == View.VISIBLE && binding.tabStrip.childCount > 0) {
                        addressBarActive = false
                        val activeTab = tabManager.activePosition()
                        val child = (0 until binding.tabStrip.childCount)
                            .map(binding.tabStrip::getChildAt)
                            .firstOrNull { it.tag == activeTab }
                            ?: binding.tabStrip.getChildAt(0)
                        child.requestFocus()
                    } else if (directNav) {
                        addressBarActive = false
                        engine.view.requestFocus()
                    } else {
                        setCursor(true)
                    }
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && binding.tabStrip.hasFocus()) {
                    if (directNav) engine.view.requestFocus() else setCursor(true)
                    return true
                }
                if (binding.urlBar.hasFocus()) {
                    // A five-button TV remote must always escape the text field in one press.
                    // Text caret movement happens inside the on-screen keyboard after OK is pressed.
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        addressBarActive = false
                        binding.btnGo.requestFocus(); return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        addressBarActive = false
                        binding.btnReload.requestFocus(); return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // At the very top of the page, one more UP leaves the page and focuses the
                    // address bar — an always-available way back to the URL (besides long-press OK).
                    if (cursor.atTopEdge() && engine.verticalScrollOffset() <= 0) {
                        setCursor(false)
                    } else if (event.repeatCount == 0) {
                        cursor.startMove(0, -1)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    cursor.stopMove()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val (dx, dy) = when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
                        else -> 0 to 1
                    }
                    if (event.repeatCount == 0) cursor.startMove(dx, dy)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    cursor.stopMove()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        cursor.stopMove()
                        if (event.repeatCount == 0) centerLongHandled = false
                        if (event.repeatCount == 1) { // first auto-repeat ≈ long press
                            centerLongHandled = true
                            onCursorLongPress()
                        }
                    }
                    KeyEvent.ACTION_UP ->
                        if (centerLongHandled) centerLongHandled = false else cursor.click()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Analog stick / D-pad-hat from a gamepad moves the on-screen cursor. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::cursor.isInitialized && ::tabManager.isInitialized &&
            !isFullscreen && cursor.active && tabManager.hasTabs &&
            event.action == MotionEvent.ACTION_MOVE &&
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        ) {
            var ax = event.getAxisValue(MotionEvent.AXIS_X)
            var ay = event.getAxisValue(MotionEvent.AXIS_Y)
            if (abs(ax) < AXIS_DEADZONE) ax = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            if (abs(ay) < AXIS_DEADZONE) ay = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (abs(ax) > AXIS_DEADZONE || abs(ay) > AXIS_DEADZONE) {
                cursor.nudge(if (abs(ax) > AXIS_DEADZONE) ax else 0f, if (abs(ay) > AXIS_DEADZONE) ay else 0f)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Long-press OK: show a context menu for a link/image under the cursor, else exit to toolbar. */
    private fun onCursorLongPress() {
        if (!tabManager.hasTabs) { setCursor(false); return }
        engine.hitTestAt(cursor.pxX(), cursor.pxY()) { href, img, _ ->
            runOnUiThread {
                if (href == null && img == null) setCursor(false) else showLinkContextMenu(href, img)
            }
        }
    }

    private fun showLinkContextMenu(href: String?, img: String?) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (href != null) {
            actions += getString(R.string.ctx_open_new_tab) to { tabManager.newTab(href) }
            actions += getString(R.string.ctx_copy_link) to { copyToClipboard(href) }
        }
        if (img != null) {
            actions += getString(R.string.ctx_open_image) to { engine.loadUrl(img) }
            actions += getString(R.string.ctx_download_image) to { enqueueDownload(img, null, null, null) }
        }
        if (actions.isEmpty()) { setCursor(false); return }
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        toast(R.string.ctx_copied)
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        runCatching { voiceSearchLauncher.launch(intent) }
            .onFailure { toast(R.string.voice_unavailable) }
    }

    private fun registerBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (SystemClock.uptimeMillis() < suppressBackCallbackUntil) {
                    binding.btnMenu.postDelayed(
                        { binding.btnMenu.requestFocus() },
                        URL_BACK_FOCUS_DELAY_MS
                    )
                    return
                }
                when {
                    binding.tabSwitcherOverlay.visibility == View.VISIBLE -> {
                        resetExitSequence(); dismissTabSwitcher()
                    }
                    isFullscreen -> {
                        resetExitSequence(); exitFullscreen?.invoke()
                    }
                    binding.errorPanel.visibility == View.VISIBLE -> {
                        resetExitSequence()
                        hideErrorPanel()
                        if (engine.canGoBack()) engine.goBack() else showHome()
                    }
                    homeVisible && binding.homePanel.hasFocus() -> {
                        resetExitSequence(); binding.btnMenu.requestFocus()
                    }
                    // In page-cursor mode, BACK first returns to the toolbar/address bar (reliable,
                    // single press — unlike long-press OK, which needs key auto-repeat some remotes
                    // don't emit). Press BACK again to actually go back in history.
                    cursor.active -> {
                        resetExitSequence(); setCursor(false)
                    }
                    directNav && (engine.view.hasFocus() || binding.engineContainer.hasFocus()) ->
                        run { resetExitSequence(); focusAddressBar() }
                    // BACK exits address entry into browser chrome; it must never terminate Comet.
                    addressBarActive || binding.urlBar.hasFocus() -> leaveAddressBar()
                    engine.canGoBack() -> {
                        resetExitSequence(); engine.goBack()
                    }
                    tabManager.count > 1 -> {
                        resetExitSequence(); tabManager.closeActive()
                    }
                    else -> {
                        val transition = exitPolicy.reduce(
                            exitState,
                            RemoteKey.BACK,
                            SystemClock.uptimeMillis(),
                            exitEligible = true
                        )
                        exitState = transition.state
                        when (val effect = transition.effect) {
                            RemoteExitPolicy.Effect.Exit -> {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                            is RemoteExitPolicy.Effect.Warn -> toast(
                                if (effect.remainingBackPresses == 1) R.string.press_back_once_to_exit
                                else R.string.press_back_twice_to_exit
                            )
                            else -> Unit
                        }
                    }
                }
            }
        })
    }

    private fun hideSoftKeyboard() {
        val input = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        input.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    private fun leaveAddressBar() {
        addressBarActive = false
        suppressBackCallbackUntil = SystemClock.uptimeMillis() + BACK_CALLBACK_SUPPRESSION_MS
        hideSoftKeyboard()
        binding.urlBar.dismissDropDown()
        binding.urlBar.clearFocus()
        // The TV IME may restore EditText focus as it finishes handling BACK. Move focus after that
        // transaction completes so the menu highlight reliably wins.
        binding.btnMenu.postDelayed({ binding.btnMenu.requestFocus() }, URL_BACK_FOCUS_DELAY_MS)
        resetExitSequence()
    }

    private fun focusAddressBar() {
        addressBarActive = true
        binding.urlBar.requestFocus()
    }

    private fun resetExitSequence() {
        exitState = RemoteExitPolicy.State()
    }

    private val callbacks = object : EngineCallbacks {
        override fun onPageStarted(url: String) {
            hideErrorPanel()
            if (url != HOME_URL) hideHome()
        }

        override fun onPageFailure(failure: PageFailure) {
            showErrorPanel(failure)
        }

        override fun onRendererRecovered(url: String) {
            if (isFullscreen) exitFullscreen?.invoke()
            showErrorPanel(
                PageFailure(PageFailureKind.RENDERER_CRASH, url, getString(R.string.error_renderer_title))
            )
        }
        override fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
            binding.btnBack.isEnabled = canGoBack
            binding.btnBack.isFocusable = canGoBack
            binding.btnBack.alpha = if (canGoBack) 1f else 0.35f
            binding.btnForward.isEnabled = canGoForward
            binding.btnForward.isFocusable = canGoForward
            binding.btnForward.alpha = if (canGoForward) 1f else 0.35f
        }

        override fun onProgressChanged(progress: Int) {
            binding.progress.progress = progress
            binding.progress.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onUrlChanged(url: String) {
            currentHost = AdBlocker.hostOf(url)
            val resolved = SiteBrowsingPolicy.resolveForHost(
                siteBrowsingSettings,
                currentHost,
                desktopMode
            )
            if (resolved != activeBrowsingSettings) {
                activeBrowsingSettings = resolved
                engine.setBrowsingMode(
                    resolved.desktopMode,
                    resolved.useWideViewPort,
                    resolved.loadWithOverviewMode
                )
            }
            if (!binding.urlBar.hasFocus() && url != "about:blank") {
                binding.urlBar.setText(url)
            }
        }

        override fun onOpenInCurrent(url: String) {
            engine.loadUrl(url)
        }

        override fun onOpenInNewTab(url: String) {
            tabManager.newTab(url)
        }

        override fun onPopupBlocked(targetUrl: String?) {
            // Brief, throttled feedback so the user can see the blocker is working.
            val now = System.currentTimeMillis()
            if (now - lastBlockToast > BLOCK_TOAST_THROTTLE_MS) {
                lastBlockToast = now
                toast(R.string.blocked_redirect)
            }
        }

        override fun onPageFinished(url: String) {
            if (!isIncognito) store.recordVisit(engine.currentTitle() ?: url, url)
        }

        override fun onDownloadRequested(
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?
        ) {
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }

        override fun onShowFileChooser(
            filePathCallback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null) // cancel a stale one
            fileChooserCallback = filePathCallback
            return runCatching { fileChooserLauncher.launch(params.createIntent()); true }
                .getOrElse { fileChooserCallback = null; false }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            cancelPermissionPrompt(deny = true)
            activePermissionRequest = request
            val origin = request.origin.toString()
            val initial = permissionPolicy.resolve(origin, request.resources.toList())
            if (!initial.hasPendingDecisions) {
                finishPermissionRequest(request, origin, emptyMap())
                return
            }
            showPermissionChoice(origin, initial.pendingDecisions.map { it.resource }) { decision ->
                if (activePermissionRequest !== request) return@showPermissionChoice
                val decisions = initial.pendingDecisions.associateWith { decision }
                finishPermissionRequest(request, origin, decisions)
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (activePermissionRequest === request) cancelPermissionPrompt(deny = false)
        }

        override fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            cancelPermissionPrompt(deny = true)
            val key = permissionPolicy.key(origin, SitePermissionResource.LOCATION)
            if (key == null) {
                callback.invoke(origin, false, false)
                return
            }
            activeGeolocationOrigin = origin
            activeGeolocationCallback = callback
            if (permissionPolicy.isGrantedForSession(key)) {
                finishGeolocationRequest(origin, callback, PermissionDecision.ALLOW_ONCE)
                return
            }
            showPermissionChoice(origin, listOf(SitePermissionResource.LOCATION)) { decision ->
                if (activeGeolocationCallback !== callback) return@showPermissionChoice
                finishGeolocationRequest(origin, callback, decision)
            }
        }

        override fun onGeolocationPromptCanceled() {
            if (activeGeolocationCallback != null) cancelPermissionPrompt(deny = false)
        }

        override fun onSslError(handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showErrorPanel(
                PageFailure(
                    PageFailureKind.SSL,
                    error.url.orEmpty(),
                    getString(R.string.ssl_message, error.url ?: "")
                )
            )
        }

        override fun onHttpAuthRequest(handler: HttpAuthHandler, host: String, realm: String?) {
            val user = EditText(this@BrowserActivity).apply { hint = getString(R.string.auth_user) }
            val pass = EditText(this@BrowserActivity).apply {
                hint = getString(R.string.auth_pass)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val box = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(user); addView(pass)
            }
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(getString(R.string.auth_title, host))
                .setView(box)
                .setPositiveButton(R.string.permission_allow) { _, _ ->
                    handler.proceed(user.text.toString(), pass.text.toString())
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        override fun onExternalUrl(url: String): Boolean {
            val launch = runCatching {
                if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                else Intent(Intent.ACTION_VIEW, url.toUri())
            }.getOrNull() ?: return true
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(R.string.external_title)
                .setMessage(getString(R.string.external_message, url.take(120)))
                .setPositiveButton(R.string.external_open) { _, _ ->
                    runCatching { startActivity(launch) }.onFailure { toast(R.string.external_failed) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return true
        }

        @Suppress("DEPRECATION") // Legacy flag complements Insets on older/vendor TV builds.
        override fun onEnterFullscreen(fullscreenView: View, onExit: () -> Unit) {
            cursor.stopMove()
            isFullscreen = true
            exitFullscreen = onExit
            // Providers normally hand us a detached view, but some keep it attached. Always move
            // it into our dedicated overlay so it cannot remain clipped inside the page WebView.
            val providerParent = fullscreenView.parent
            if (providerParent != null && providerParent !is ViewGroup) {
                // An attached view we cannot safely detach would crash addView below. Reject this
                // provider-specific request cleanly and let WebView resume inline rendering.
                onExit()
                return
            }
            this@BrowserActivity.fullscreenView = fullscreenView.also { view ->
                fullscreenOriginalParent = providerParent
                fullscreenOriginalIndex = fullscreenOriginalParent?.indexOfChild(view) ?: -1
                fullscreenOriginalLayoutParams = view.layoutParams
            }
            fullscreenOriginalParent?.removeView(fullscreenView)
            binding.fullscreenContainer.removeAllViews()
            binding.fullscreenContainer.addView(
                fullscreenView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.fullscreenContainer.visibility = View.VISIBLE
            binding.browserChrome.visibility = View.GONE
            fullscreenView.isFocusable = true
            fullscreenView.isFocusableInTouchMode = true
            fullscreenView.requestFocus()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setSystemBars(visible = false)
        }

        @Suppress("DEPRECATION") // Clear the compatibility flag added on entry.
        override fun onExitFullscreen() {
            isFullscreen = false
            exitFullscreen = null
            if (!isIncognito) binding.root.post { updateController.onFullscreenEnded() }
            val view = fullscreenView
            binding.fullscreenContainer.removeView(view)
            val originalParent = fullscreenOriginalParent
            if (view?.parent == null && originalParent != null) {
                // If the provider callback did not reclaim its attached view, restore ownership.
                runCatching {
                    val index = fullscreenOriginalIndex.coerceIn(0, originalParent.childCount)
                    originalParent.addView(view, index, fullscreenOriginalLayoutParams)
                }
            }
            fullscreenView = null
            fullscreenOriginalParent = null
            fullscreenOriginalIndex = -1
            fullscreenOriginalLayoutParams = null
            binding.fullscreenContainer.visibility = View.GONE
            binding.browserChrome.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setSystemBars(visible = true)
            if (tabManager.hasTabs) engine.view.requestFocus()
        }
    }

    private fun setSystemBars(visible: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, visible)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tabManager.isInitialized) tabManager.onResume()
        if (!isIncognito) updateController.onResume()
    }

    override fun onPause() {
        if (::cursor.isInitialized) cursor.stopMove()
        persistTabs()
        if (::tabManager.isInitialized) tabManager.onPause()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::tabManager.isInitialized) {
            // Android 14+ no longer sends the RUNNING_* levels, so UI_HIDDEN (trim to the budget) and
            // BACKGROUND and above (release every background engine) must do the work there.
            if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW ||
                level == TRIM_MEMORY_RUNNING_CRITICAL
            ) {
                tabManager.releaseInactiveEngines()
            } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
                tabManager.trimToBudget()
            }
            if (binding.tabSwitcherOverlay.visibility == View.VISIBLE) rebuildTabSwitcher()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::tabManager.isInitialized) tabManager.releaseInactiveEngines()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A link that arrived during startup and hasn't been opened yet must survive recreation.
        pendingIntentUrl?.let { outState.putString(KEY_PENDING_LINK, it) }
        if (!isIncognito || !::tabManager.isInitialized || !tabManager.hasTabs) return
        // Only a configuration change in the same process may bring these tabs back (see
        // restorePrivateSession); the token tells it apart from a restore after process death.
        val tabs = tabManager.snapshot()
        outState.putString(KEY_PRIVATE_TOKEN, PROCESS_TOKEN)
        outState.putStringArrayList(KEY_PRIVATE_URLS, ArrayList(tabs.map { it.url }))
        outState.putStringArrayList(KEY_PRIVATE_TITLES, ArrayList(tabs.map { it.title }))
        outState.putInt(KEY_PRIVATE_ACTIVE, tabManager.activePosition())
    }

    private fun restorePrivateSession(state: Bundle?): com.tdarby.comet.data.TabSessionPolicy.Session? {
        if (state?.getString(KEY_PRIVATE_TOKEN) != PROCESS_TOKEN) return null
        val urls = state.getStringArrayList(KEY_PRIVATE_URLS) ?: return null
        val titles = state.getStringArrayList(KEY_PRIVATE_TITLES) ?: return null
        if (urls.size != titles.size) return null
        return com.tdarby.comet.data.TabSessionPolicy.bound(
            urls.indices.map { TabManager.TabSnapshot(urls[it], titles[it]) },
            state.getInt(KEY_PRIVATE_ACTIVE)
        )
    }

    override fun onDestroy() {
        cancelPermissionPrompt(deny = true)
        // A configuration change recreates the Activity in the same process; wiping then would
        // end the private session mid-use.
        if (isIncognito && !isChangingConfigurations) clearBrowsingData()
        if (::tabManager.isInitialized) tabManager.destroyAll()
        super.onDestroy()
    }

    companion object {
        /** Fresh per process: a saved private bundle is only trusted when it carries this value. */
        private val PROCESS_TOKEN = java.util.UUID.randomUUID().toString()
        private const val KEY_PRIVATE_TOKEN = "private_token"
        private const val KEY_PRIVATE_URLS = "private_urls"
        private const val KEY_PRIVATE_TITLES = "private_titles"
        private const val KEY_PRIVATE_ACTIVE = "private_active"
        private const val KEY_PENDING_LINK = "pending_link"
        private const val HOME_URL = "about:blank"
        private const val HOME_TILE_LIMIT = 8
        private const val HOME_TILE_WIDTH_DP = 260
        private const val HOME_TILE_MARGIN_DP = 6
        private const val MAX_COMPACT_TABS = 8
        private const val TAB_THUMB_WIDTH = 288
        private const val TAB_THUMB_HEIGHT = 162
        private const val AXIS_DEADZONE = 0.18f
        private const val BLOCK_TOAST_THROTTLE_MS = 2500L
        private const val URL_BACK_FOCUS_DELAY_MS = 300L
        private const val BACK_CALLBACK_SUPPRESSION_MS = 600L
        private val REMOTE_DIRECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER
        )
    }
}
