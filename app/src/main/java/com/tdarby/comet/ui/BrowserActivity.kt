package com.tdarby.comet.ui

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
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
import com.tdarby.comet.engine.EngineFactory
import com.tdarby.comet.data.BrowserStore
import com.tdarby.comet.data.TabsStore
import com.tdarby.comet.engine.EngineType
import com.tdarby.comet.engine.MediaAction
import com.tdarby.comet.input.CursorController
import com.tdarby.comet.update.ReleaseManifest
import com.tdarby.comet.update.UpdateChecker
import com.tdarby.comet.web.TabManager
import com.tdarby.comet.util.SearchEngines
import com.tdarby.comet.util.UrlUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    /** The active tab's engine. A getter keeps every call site engine-agnostic and tab-aware. */
    private val engine: BrowserEngine get() = tabManager.activeEngine

    private lateinit var activeEngineType: EngineType
    private var desktopMode = false
    private var searchTemplate = SettingsStore.DEFAULT_SEARCH

    private var isFullscreen = false
    private var overlayFullscreen = false
    private var exitFullscreen: (() -> Unit)? = null
    private var centerLongHandled = false
    private var currentHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        store = BrowserStore(this)
        tabsStore = TabsStore(this)
        // Blocklist is loaded once in CometApp.onCreate (FilterListRepository.loadInitial).
        // Small one-shot reads at startup so engine + blocking match the user's saved choices.
        runBlocking {
            activeEngineType = settings.engineTypeNow()
            desktopMode = settings.desktopModeNow()
            searchTemplate = settings.searchTemplateNow()
            AdBlocker.networkEnabled = settings.blockNetworkNow()
            AdBlocker.cosmeticEnabled = settings.blockCosmeticNow()
            AdBlocker.popupEnabled = settings.blockPopupsNow()
            AdBlocker.redirectBlockEnabled = settings.blockRedirectsNow()
            AdBlocker.setAllowlist(settings.allowlistNow())
        }

        cursor = CursorController(binding.engineContainer) {
            if (::tabManager.isInitialized && tabManager.hasTabs) tabManager.activeEngine.view else null
        }
        tabManager = TabManager(
            container = binding.engineContainer,
            homeUrl = HOME_URL,
            create = { cb -> EngineFactory.create(this, activeEngineType, cb) },
            init = { e ->
                e.setDesktopMode(desktopMode)
                e.setBlockingEnabled(AdBlocker.networkEnabled)
                e.applyPopupPolicy()
            },
            ui = callbacks,
            onChanged = ::onTabsChanged
        )

        wireChrome()
        registerBackHandling()
        setupCursorFocus()

        openInitialTabs()
        checkForUpdates(manual = false)
    }

    /** Open a link passed via VIEW intent, else restore the saved session, else a home tab. */
    private fun openInitialTabs() {
        val intentUrl = intentUrl(intent)
        when {
            intentUrl != null -> tabManager.newTab(intentUrl)
            else -> tabManager.restore(tabsStore.load(), tabsStore.activeIndex) // empty -> home tab
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
        intentUrl(intent)?.let { tabManager.newTab(it) }
    }

    private fun persistTabs() {
        if (::tabManager.isInitialized && tabManager.hasTabs) {
            tabsStore.save(tabManager.snapshot(), tabManager.activePosition())
        }
    }

    private fun switchEngine(type: EngineType) {
        activeEngineType = type
        tabManager.recreateAll() // rebuilds every tab's engine with the new type
    }

    private fun setupCursorFocus() {
        // Keep the caret at the end of the URL when the bar gains focus, so a single RIGHT moves on
        // to the toolbar buttons (⋮ menu) instead of walking the caret through the whole URL.
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.urlBar.setSelection(binding.urlBar.text?.length ?: 0)
        }
        // Start focused on the address bar so the user can immediately type a URL. The cursor is
        // turned on explicitly when DOWN drops into the page (see dispatchKeyEvent), and off again
        // by BACK / long-press-OK / UP-at-top — no reliance on container focus events.
        binding.urlBar.post { binding.urlBar.requestFocus() }
    }

    private fun setCursor(active: Boolean) {
        cursor.setActive(active)
        if (active) binding.engineContainer.requestFocus() else binding.urlBar.requestFocus()
    }

    private fun wireChrome() = with(binding) {
        btnBack.setOnClickListener { if (engine.canGoBack()) engine.goBack() }
        btnForward.setOnClickListener { if (engine.canGoForward()) engine.goForward() }
        btnReload.setOnClickListener { engine.reload() }
        btnGo.setOnClickListener { navigateFromBar() }
        btnCursor.setOnClickListener { setCursor(!cursor.active) }
        btnMenu.setOnClickListener { showMenu() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateFromBar(); true
            } else false
        }
    }

    private fun navigateFromBar() {
        val target = UrlUtils.toUrlOrSearch(binding.urlBar.text.toString(), searchTemplate)
        engine.loadUrl(target)
        binding.urlBar.clearFocus()
        binding.engineContainer.requestFocus()
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        val url = engine.currentUrl()
        val bookmarked = url != null && store.isBookmarked(url)
        popup.menu.findItem(R.id.menu_bookmark).setTitle(
            if (bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark
        )
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> openNewTab()
                R.id.menu_close_tab -> tabManager.closeActive()
                R.id.menu_bookmark -> toggleBookmark()
                R.id.menu_bookmarks -> showBookmarks()
                R.id.menu_history -> showHistory()
                R.id.menu_home -> engine.loadUrl(HOME_URL)
                R.id.menu_voice -> launchVoiceSearch()
                R.id.menu_zoom_in -> engine.zoomIn()
                R.id.menu_zoom_out -> engine.zoomOut()
                R.id.menu_update -> checkForUpdates(manual = true)
                R.id.menu_settings -> showSettings()
            }
            true
        }
        popup.show()
    }

    private fun openNewTab() {
        tabManager.newTab(HOME_URL)
        binding.urlBar.setText("")
        binding.urlBar.requestFocus()
    }

    private fun onTabsChanged() {
        refreshTabStrip()
        persistTabs()
    }

    /** Rebuild the tab strip; shown only when more than one tab is open. */
    private fun refreshTabStrip() {
        val strip = binding.tabStrip
        val count = tabManager.count
        binding.tabStripScroll.visibility = if (count > 1) View.VISIBLE else View.GONE
        strip.removeAllViews()
        if (count <= 1) return
        val active = tabManager.activePosition()
        tabManager.titles().forEachIndexed { index, title ->
            val button = Button(this).apply {
                text = title.take(24)
                isAllCaps = false
                if (index == active) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { tabManager.select(index) }
                setOnLongClickListener { tabManager.closeTab(index); true }
            }
            strip.addView(button)
        }
        val addButton = Button(this).apply {
            text = "+"
            setOnClickListener { openNewTab() }
        }
        strip.addView(addButton)
    }

    private fun toggleBookmark() {
        val url = engine.currentUrl() ?: return
        if (store.isBookmarked(url)) {
            store.removeBookmark(url)
            toast(R.string.bookmark_removed)
        } else {
            store.addBookmark(engine.currentTitle() ?: url, url)
            toast(R.string.bookmark_added)
        }
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
        runCatching {
            val fileName = URLUtil.guessFileName(url, null, mimeType)
            val request = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.download_started)
        }
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
        val geckoAvailable = EngineFactory.isAvailable(EngineType.GECKO)
        dlg.engineGecko.isEnabled = geckoAvailable
        if (!geckoAvailable) dlg.engineGecko.text = getString(R.string.engine_unavailable)
        dlg.engineGroup.check(
            if (activeEngineType == EngineType.GECKO) R.id.engine_gecko else R.id.engine_webview
        )
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

        val host = currentHost
        dlg.switchAllowSite.isEnabled = host != null
        dlg.switchAllowSite.isChecked = AdBlocker.isAllowlisted(host)
        dlg.allowSiteLabel.text =
            if (host != null) getString(R.string.settings_allow_site_host, host)
            else getString(R.string.settings_allow_site)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dlg.root)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val chosen =
                    if (dlg.engineGroup.checkedRadioButtonId == R.id.engine_gecko && geckoAvailable)
                        EngineType.GECKO else EngineType.WEBVIEW
                val newDesktop = dlg.switchDesktop.isChecked
                val desktopChanged = newDesktop != desktopMode
                desktopMode = newDesktop
                searchTemplate = SearchEngines.templateAt(dlg.spinnerSearch.selectedItemPosition)

                AdBlocker.networkEnabled = dlg.switchBlockNetwork.isChecked
                AdBlocker.cosmeticEnabled = dlg.switchBlockCosmetic.isChecked
                AdBlocker.popupEnabled = dlg.switchBlockPopups.isChecked
                AdBlocker.redirectBlockEnabled = dlg.switchBlockRedirects.isChecked
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
                    settings.setEngineType(chosen)
                    settings.setDesktopMode(newDesktop)
                    settings.setSearchTemplate(searchTemplate)
                    settings.setBlockNetwork(AdBlocker.networkEnabled)
                    settings.setBlockCosmetic(AdBlocker.cosmeticEnabled)
                    settings.setBlockPopups(AdBlocker.popupEnabled)
                    settings.setBlockRedirects(AdBlocker.redirectBlockEnabled)
                    if (host != null) settings.setSiteAllowlisted(host, allowChecked)
                }

                when {
                    chosen != activeEngineType -> switchEngine(chosen) // rebuilds tabs w/ new engine
                    desktopChanged -> tabManager.forEachEngine { it.setDesktopMode(newDesktop) }
                    else -> engine.reload()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * In cursor mode the D-pad drives the on-screen pointer instead of moving focus.
     * OK (center) taps; a long-press of OK toggles the cursor off to reach the toolbar.
     * Handled in dispatchKeyEvent so it works regardless of which inner view holds focus.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // In fullscreen video, let the player handle D-pad/OK natively (the cursor overlay is
        // hidden behind the fullscreen view, so dispatching synthetic touches would be useless).
        if (isFullscreen) return super.dispatchKeyEvent(event)

        // Remote media keys drive the page's <video>/<audio> regardless of cursor/toolbar focus.
        mediaActionFor(event.keyCode)?.let { action ->
            if (event.action == KeyEvent.ACTION_DOWN && tabManager.hasTabs) engine.mediaAction(action)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) launchVoiceSearch()
            return true
        }

        // Toolbar mode (cursor off): D-pad does normal focus navigation, with two tweaks so the
        // page and the ⋮ menu stay reachable from the address bar (an EditText that otherwise traps
        // LEFT/RIGHT for caret movement — the "walk through every character" problem).
        if (!cursor.active) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // DOWN from anywhere in the top bar drops into the page and shows the cursor again.
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && binding.topBar.hasFocus()) {
                    setCursor(true)
                    return true
                }
                if (binding.urlBar.hasFocus()) {
                    val et = binding.urlBar
                    val len = et.text?.length ?: 0
                    // At the end of the URL, RIGHT jumps to the toolbar buttons (Go / cursor / ⋮);
                    // at the start, LEFT jumps to reload/forward/back — no caret-walking required.
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && et.selectionEnd >= len) {
                        binding.btnGo.requestFocus(); return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && et.selectionStart <= 0) {
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
                    } else {
                        cursor.move(0, -1, event.repeatCount)
                    }
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
                    cursor.move(dx, dy, event.repeatCount)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN ->
                        if (event.repeatCount == 1) { // first auto-repeat ≈ long press
                            centerLongHandled = true
                            setCursor(false)
                        }
                    KeyEvent.ACTION_UP ->
                        if (centerLongHandled) centerLongHandled = false else cursor.click()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
                when {
                    isFullscreen -> exitFullscreen?.invoke()
                    // In page-cursor mode, BACK first returns to the toolbar/address bar (reliable,
                    // single press — unlike long-press OK, which needs key auto-repeat some remotes
                    // don't emit). Press BACK again to actually go back in history.
                    cursor.active -> setCursor(false)
                    engine.canGoBack() -> engine.goBack()
                    tabManager.count > 1 -> tabManager.closeActive()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private val callbacks = object : EngineCallbacks {
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
                .setPositiveButton(R.string.permission_allow) { _, _ -> request.grant(request.resources) }
                .setNegativeButton(R.string.permission_deny) { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }

        override fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(R.string.permission_title)
                .setMessage(getString(R.string.permission_location, origin))
                .setPositiveButton(R.string.permission_allow) { _, _ -> callback.invoke(origin, true, false) }
                .setNegativeButton(R.string.permission_deny) { _, _ -> callback.invoke(origin, false, false) }
                .setOnCancelListener { callback.invoke(origin, false, false) }
                .show()
        }

        override fun onEnterFullscreen(fullscreenView: View, onExit: () -> Unit) {
            isFullscreen = true
            exitFullscreen = onExit
            // WebView hands us a detached custom view (overlay it); GeckoView renders fullscreen
            // inside its own already-attached view (just collapse the chrome around it).
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
        tabManager.onResume()
    }

    override fun onPause() {
        persistTabs()
        tabManager.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://www.google.com/"
    }
}
