package com.tdarby.comet.ui

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
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
import com.tdarby.comet.R
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.data.SettingsStore
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
import com.tdarby.comet.update.ReleaseManifest
import com.tdarby.comet.update.UpdateChecker
import com.tdarby.comet.web.TabManager
import com.tdarby.comet.util.SearchEngines
import com.tdarby.comet.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class BrowserActivity : AppCompatActivity() {

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

    /** The active tab's engine. A getter keeps every call site engine-agnostic and tab-aware. */
    private val engine: BrowserEngine get() = tabManager.activeEngine

    private var desktopMode = false
    private var searchTemplate = SettingsStore.DEFAULT_SEARCH
    private var directNav = false
    private var cursorSpeedSetting = SettingsStore.DEFAULT_CURSOR_SPEED
    private var hintShownAlready = false

    private var isFullscreen = false
    private var overlayFullscreen = false
    private var exitFullscreen: (() -> Unit)? = null
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
                val savedTabs = savedTabsStore.load()
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
                e.setDesktopMode(desktopMode)
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
        checkForUpdates(manual = false)
        maybeShowFirstRunHint()
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
        if (::tabManager.isInitialized && ::tabsStore.isInitialized && tabManager.hasTabs) {
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
        HomeContent.recent(store.history, HOME_TILE_LIMIT).forEach {
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
        val actions = listOf(
            getString(R.string.settings_title) to ::showSettings,
            getString(R.string.menu_tabs) to ::showTabSwitcher,
            getString(R.string.menu_home) to ::showHome,
            getString(R.string.menu_bookmarks) to ::showBookmarks,
            getString(R.string.menu_history) to ::showHistory,
            getString(R.string.menu_downloads) to ::showDownloads,
            getString(if (bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark) to
                ::toggleBookmark,
            getString(R.string.menu_new_tab) to ::openNewTab,
            getString(R.string.menu_close_tab) to { tabManager.closeActive() },
            getString(R.string.menu_voice) to ::launchVoiceSearch,
            getString(R.string.menu_zoom_in) to { engine.zoomIn() },
            getString(R.string.menu_zoom_out) to { engine.zoomOut() },
            getString(R.string.menu_update) to { checkForUpdates(manual = true) }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_menu)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun openNewTab() {
        tabManager.newTab(HOME_URL)
        binding.urlBar.setText("")
        showHome()
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
                val desktopChanged = newDesktop != desktopMode
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
                if (host != null) {
                    if (allowChecked) AdBlocker.setAllowlist(AdBlocker.currentAllowlist() + host)
                    else AdBlocker.setAllowlist(AdBlocker.currentAllowlist() - host)
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
                }

                if (desktopChanged) tabManager.forEachEngine { it.setDesktopMode(newDesktop) }
                else engine.reload()
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
            store.recordVisit(engine.currentTitle() ?: url, url)
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
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(R.string.permission_title)
                .setMessage(getString(R.string.permission_message, request.resources.joinToString()))
                .setPositiveButton(R.string.permission_allow) { _, _ ->
                    // Grant to the page only after the matching Android runtime permission is held,
                    // otherwise getUserMedia would "succeed" but produce nothing.
                    val osPerms = request.resources.mapNotNull {
                        when (it) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                            else -> null
                        }
                    }
                    ensureOsPermissions(osPerms) { ok ->
                        if (ok) request.grant(request.resources) else request.deny()
                    }
                }
                .setNegativeButton(R.string.permission_deny) { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }

        override fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(R.string.permission_title)
                .setMessage(getString(R.string.permission_location, origin))
                .setPositiveButton(R.string.permission_allow) { _, _ ->
                    ensureOsPermissions(
                        listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    ) {
                        // Users can grant only COARSE on Android 12+; either is enough for the page.
                        val granted = ContextCompat.checkSelfPermission(
                            this@BrowserActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                            this@BrowserActivity, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        callback.invoke(origin, granted, false)
                    }
                }
                .setNegativeButton(R.string.permission_deny) { _, _ -> callback.invoke(origin, false, false) }
                .setOnCancelListener { callback.invoke(origin, false, false) }
                .show()
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

        override fun onEnterFullscreen(fullscreenView: View, onExit: () -> Unit) {
            cursor.stopMove()
            isFullscreen = true
            exitFullscreen = onExit
            // WebView hands us a detached custom view to overlay full-screen.
            overlayFullscreen = fullscreenView.parent == null
            if (overlayFullscreen) {
                binding.fullscreenContainer.addView(
                    fullscreenView,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.browserChrome.visibility = View.GONE
            } else {
                binding.topBar.visibility = View.GONE
                binding.progress.visibility = View.GONE
            }
            fullscreenView.isFocusable = true
            fullscreenView.isFocusableInTouchMode = true
            fullscreenView.requestFocus()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setSystemBars(visible = false)
        }

        override fun onExitFullscreen() {
            isFullscreen = false
            exitFullscreen = null
            if (overlayFullscreen) {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.browserChrome.visibility = View.VISIBLE
            } else {
                binding.topBar.visibility = View.VISIBLE
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
