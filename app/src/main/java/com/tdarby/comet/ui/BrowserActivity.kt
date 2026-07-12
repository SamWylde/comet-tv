package com.tdarby.comet.ui

import android.Manifest
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
import com.tdarby.comet.data.BrowserStore
import com.tdarby.comet.data.TabsStore
import com.tdarby.comet.engine.MediaAction
import com.tdarby.comet.engine.PageFailure
import com.tdarby.comet.engine.PageFailureKind
import com.tdarby.comet.input.CursorController
import com.tdarby.comet.input.RemoteExitPolicy
import com.tdarby.comet.input.RemoteKey
import com.tdarby.comet.permission.PermissionDecision
import com.tdarby.comet.permission.PermissionKey
import com.tdarby.comet.permission.PermissionPolicy
import com.tdarby.comet.permission.SitePermissionResource
import com.tdarby.comet.support.SupportReportBuilder
import com.tdarby.comet.support.SupportReportInput
import com.tdarby.comet.support.SupportViewport
import com.tdarby.comet.support.UserAgentBrand
import com.tdarby.comet.support.UserAgentClientHints
import com.tdarby.comet.update.ReleaseManifest
import com.tdarby.comet.update.UpdateChecker
import com.tdarby.comet.web.TabManager
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
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        binding.startupOverlay.requestFocus()

        // DataStore and JSON session/history reads must not hold up Android's first frame. The
        // branded loading surface is drawn immediately while this small snapshot is read on IO.
        lifecycleScope.launch {
            val startup = withContext(Dispatchers.IO) {
                val browserStore = BrowserStore(applicationContext)
                val savedTabsStore = TabsStore(applicationContext)
                val savedTabs = if (isIncognito) emptyList() else savedTabsStore.load()
                StartupData(
                    settings = settings.snapshot(),
                    browserStore = browserStore,
                    tabsStore = savedTabsStore,
                    savedTabs = savedTabs,
                    activeTab = savedTabsStore.activeIndex
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
            init = { e ->
                applyBrowsingSettings(e, null)
                e.setBlockingEnabled(AdBlocker.networkEnabled)
                e.applyPopupPolicy()
            },
            ui = callbacks,
            onChanged = ::onTabsChanged
        )

        wireChrome()
        wireHome()
        wireErrorPanel()
        registerBackHandling()
        setupCursorFocus()

        openInitialTabs(startup.savedTabs, startup.activeTab)
        binding.startupOverlay.visibility = View.GONE
        syncHomeVisibility(requestFocus = true)
        binding.root.post { reportFullyDrawn() }
        if (!isIncognito) {
            checkForUpdates(manual = false)
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
        val intentUrl = pendingIntentUrl ?: intentUrl(intent)
        pendingIntentUrl = null
        when {
            intentUrl != null -> tabManager.newTab(intentUrl)
            else -> tabManager.restore(savedTabs, activeTab) // empty -> home tab
        }
    }

    /** Extract an http(s) URL from a VIEW intent (opening links from other apps). */
    private fun intentUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.dataString ?: return null
        return data.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentUrl(intent)?.let {
            if (::tabManager.isInitialized) tabManager.newTab(it) else pendingIntentUrl = it
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
            suggestions.setCandidates(store.history.map { it.url } + store.bookmarks.map { it.url })
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
        cursor.setActive(false)
        binding.homePanel.visibility = View.GONE
        binding.errorPanel.visibility = View.VISIBLE
        val titleRes = when (failure.kind) {
            PageFailureKind.OFFLINE -> R.string.error_offline_title
            PageFailureKind.DNS -> R.string.error_dns_title
            PageFailureKind.TIMEOUT -> R.string.error_timeout_title
            PageFailureKind.CONNECTION -> R.string.error_connection_title
            PageFailureKind.HTTP -> R.string.error_http_title
            PageFailureKind.SSL -> R.string.error_ssl_title
            PageFailureKind.RENDERER_CRASH -> R.string.error_renderer_title
        }
        binding.errorTitle.setText(titleRes)
        val detail = when {
            failure.httpStatus != null -> "HTTP ${failure.httpStatus}: ${failure.detail}"
            failure.detail.isNotBlank() -> failure.detail
            else -> getString(titleRes)
        }
        binding.errorMessage.text = getString(R.string.error_message_with_url, detail, failure.url)
        binding.errorRetry.requestFocus()
    }

    private fun hideErrorPanel() {
        currentFailure = null
        binding.errorPanel.visibility = View.GONE
    }

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
        binding.homeBookmarkGrid.removeAllViews()
        binding.homeRecentGrid.removeAllViews()
        store.bookmarks.take(HOME_TILE_LIMIT).forEach {
            addHomeTile(binding.homeBookmarkGrid, HomeTile(it.title, it.url), true)
        }
        val recentSites = if (isIncognito) emptyList() else HomeContent.recent(store.history, HOME_TILE_LIMIT)
        recentSites.forEach {
            addHomeTile(binding.homeRecentGrid, it, false)
        }
        if (binding.homeBookmarkGrid.childCount == 0) addHomeEmpty(binding.homeBookmarkGrid)
        if (binding.homeRecentGrid.childCount == 0) addHomeEmpty(binding.homeRecentGrid)
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
            width = dp(260)
            height = dp(96)
            setMargins(dp(6), dp(6), dp(6), dp(6))
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
                add(3, getString(R.string.menu_history) to ::showHistory)
                add(getString(if (bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark) to
                    ::toggleBookmark)
                add(getString(R.string.menu_new_private_tab) to ::openPrivateTab)
                add(getString(R.string.menu_clear_browsing_data) to ::confirmClearBrowsingData)
                add(getString(R.string.menu_about) to ::showDiagnostics)
                add(getString(R.string.menu_update) to { checkForUpdates(manual = true) })
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
                    currentUrl = engine.currentUrl()
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
                    rebuildTabSwitcher()
                }
            }
            card.addView(image)
            card.addView(title)
            card.addView(url)
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
        openButtons.getOrNull(tabManager.activePosition())?.requestFocus()
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
        val items = store.bookmarks
        if (items.isEmpty()) {
            toast(R.string.bookmarks_empty); return
        }
        val labels = items.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmarks)
            .setItems(labels) { _, which -> engine.loadUrl(items[which].url) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun showHistory() {
        val items = store.history
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

    private fun enqueueDownload(url: String, userAgent: String?, mimeType: String?) {
        // DownloadManager can't fetch blob: URLs — read them in the page instead.
        if (url.startsWith("blob:")) {
            if (tabManager.hasTabs) engine.fetchBlob(url)
            return
        }
        runCatching {
            val fileName = URLUtil.guessFileName(url, null, mimeType)
            val request = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                // Propagate cookies so session/login-gated downloads succeed.
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.download_started)
        }
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
                while (c.moveToNext() && rows.size < 40) {
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
                    rows.add(DownloadRow(c.getLong(iId), title, "$title — $state", status, c.getString(iMime)))
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
        actions += getString(R.string.dl_delete) to { dm.remove(row.id); Unit }
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun openDownload(dm: DownloadManager, row: DownloadRow) {
        val uri = runCatching { dm.getUriForDownloadedFile(row.id) }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, row.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure { toast(R.string.external_failed) }
    }

    private fun checkForUpdates(manual: Boolean) {
        lifecycleScope.launch {
            val checker = UpdateChecker(this@BrowserActivity)
            val manifest = checker.check()
            when {
                manifest == null -> if (manual) toast(R.string.update_check_failed)
                !checker.isNewer(manifest) -> if (manual) toast(R.string.update_none)
                else -> promptUpdate(checker, manifest)
            }
        }
    }

    private fun promptUpdate(checker: UpdateChecker, manifest: ReleaseManifest) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available, manifest.versionName))
            .setMessage(manifest.notes.orEmpty())
            .setPositiveButton(R.string.update_install) { _, _ ->
                toast(R.string.update_downloading)
                lifecycleScope.launch {
                    val apk = checker.download(manifest)
                    if (apk != null) checker.install(apk) else toast(R.string.update_failed)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
                    else AdBlocker.setAllowlist(AdBlocker.currentAllowlist() - host)
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
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        dlg.switchDesktop.requestFocus()
    }

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
        mediaActionFor(event.keyCode)?.let { action ->
            if (event.action == KeyEvent.ACTION_DOWN && tabManager.hasTabs) engine.mediaAction(action)
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
        if (!isFullscreen && cursor.active && tabManager.hasTabs &&
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

    private fun mediaActionFor(keyCode: Int): MediaAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_STOP -> MediaAction.STOP
        KeyEvent.KEYCODE_MEDIA_REWIND -> MediaAction.REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> MediaAction.FORWARD
        else -> null
    }

    /** Long-press OK: show a context menu for a link/image under the cursor, else exit to toolbar. */
    private fun onCursorLongPress() {
        if (!tabManager.hasTabs) { setCursor(false); return }
        engine.hitTestAt(cursor.cssX(), cursor.cssY()) { href, img, _ ->
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
            actions += getString(R.string.ctx_download_image) to { enqueueDownload(img, null, null) }
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

        override fun onDownloadRequested(url: String, userAgent: String?, mimeType: String?) {
            enqueueDownload(url, userAgent, mimeType)
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
    }

    override fun onPause() {
        if (::cursor.isInitialized) cursor.stopMove()
        persistTabs()
        if (::tabManager.isInitialized) tabManager.onPause()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::tabManager.isInitialized && level >= TRIM_MEMORY_RUNNING_LOW) {
            tabManager.releaseInactiveEngines()
            if (binding.tabSwitcherOverlay.visibility == View.VISIBLE) rebuildTabSwitcher()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::tabManager.isInitialized) tabManager.releaseInactiveEngines()
    }

    override fun onDestroy() {
        cancelPermissionPrompt(deny = true)
        if (isIncognito) clearBrowsingData()
        if (::tabManager.isInitialized) tabManager.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "about:blank"
        private const val HOME_TILE_LIMIT = 8
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
