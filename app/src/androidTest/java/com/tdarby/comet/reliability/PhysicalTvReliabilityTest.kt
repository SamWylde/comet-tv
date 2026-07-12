package com.tdarby.comet.reliability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tdarby.comet.R
import com.tdarby.comet.data.SettingsStore
import com.tdarby.comet.ui.BrowserActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in reliability probes intended for a physical Android/Google TV and its real remote stack.
 *
 * Every test is skipped unless the instrumentation invocation includes `-e physicalTv true` and
 * the device advertises FEATURE_LEANBACK. Network-independent cases use a loopback HTTP server so
 * popup/redirect behavior is deterministic. See docs/PHYSICAL_TV_RELIABILITY.md for commands.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalTvReliabilityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var originalSettings: SettingsStore.Snapshot
    private var settingsCaptured = false

    @Before
    fun requireExplicitPhysicalTvOptIn() {
        val optedIn = arguments.getString(ARG_PHYSICAL_TV)?.toBooleanStrictOrNull() == true
        assumeTrue("Pass -e $ARG_PHYSICAL_TV true on a dedicated physical TV", optedIn)
        assumeTrue(
            "Reliability suite requires an Android TV / Google TV device",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        )
        originalSettings = runBlocking { SettingsStore(context).snapshot() }
        settingsCaptured = true
    }

    @After
    fun restoreUserSettings() {
        if (!settingsCaptured) return
        runBlocking {
            SettingsStore(context).apply {
                setDesktopMode(originalSettings.desktopMode)
                setBlockNetwork(originalSettings.blockNetwork)
                setBlockCosmetic(originalSettings.blockCosmetic)
                setBlockPopups(originalSettings.blockPopups)
                setBlockRedirects(originalSettings.blockRedirects)
                setSearchTemplate(originalSettings.searchTemplate)
                setCursorSpeed(originalSettings.cursorSpeed)
                setDirectNav(originalSettings.directNav)
                setFirstRunHintShown(originalSettings.firstRunHintShown)
            }
        }
    }

    @Test
    fun fiveButtonRemote_canEnterAndLeavePageWithoutClosingActivity() {
        prepareSettings(blockRedirects = false)
        LoopbackSite().use { site ->
            launch(site.url("/remote")).use { scenario ->
                awaitBrowserReady(scenario)

                // Address field -> adjacent chrome -> address field.
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals(R.id.btn_reload, scenario.focusedViewId())
                press(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("RIGHT should return to the address field") {
                    scenario.focusedViewId() == R.id.url_bar
                }

                // BACK while editing must leave the field, not finish Comet.
                press(KeyEvent.KEYCODE_BACK)
                await("BACK should focus Menu") { scenario.focusedViewId() == R.id.btn_menu }
                scenario.assertAlive()

                // Exercise horizontal chrome navigation, then DOWN into the page/cursor.
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                press(KeyEvent.KEYCODE_DPAD_RIGHT)
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("DOWN should focus a descendant of the engine container") {
                    scenario.viewHasFocus(R.id.engine_container)
                }

                // These are the only navigation keys guaranteed on the target remotes.
                press(KeyEvent.KEYCODE_DPAD_UP)
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                press(KeyEvent.KEYCODE_DPAD_RIGHT)
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                press(KeyEvent.KEYCODE_ENTER)
                scenario.assertAlive()

                // A single BACK from page/cursor mode returns to chrome and cannot exit the app.
                press(KeyEvent.KEYCODE_BACK)
                await("BACK from the page should return to the address field") {
                    scenario.focusedViewId() == R.id.url_bar
                }
                scenario.assertAlive()
            }
        }
    }

    @Test
    fun rootBack_requiresFourPressesBeforeActivityCloses() {
        prepareSettings(blockRedirects = false)
        LoopbackSite().use { site ->
            launch(site.url("/remote")).use { scenario ->
                awaitBrowserReady(scenario)
                scenario.onActivity { it.findViewById<View>(R.id.btn_menu).requestFocus() }
                instrumentation.waitForIdleSync()

                repeat(3) {
                    press(KeyEvent.KEYCODE_BACK)
                    scenario.assertAlive()
                }
                press(KeyEvent.KEYCODE_BACK)
                await("Fourth root-level BACK should close Comet") {
                    scenario.state == Lifecycle.State.DESTROYED
                }
            }
        }
    }

    @Test
    fun enterOnUrlBar_opensTvKeyboardWithoutLeavingComet() {
        prepareSettings(blockRedirects = false)
        LoopbackSite().use { site ->
            launch(site.url("/remote")).use { scenario ->
                awaitBrowserReady(scenario)
                scenario.onActivity { it.findViewById<View>(R.id.url_bar).requestFocus() }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("ENTER on the URL bar did not make the TV keyboard visible") {
                    scenario.imeVisible()
                }
                scenario.assertAlive()
            }
        }
    }

    @Test
    fun hostilePage_programmaticPopupAndCrossSiteRedirectStayBlocked() {
        prepareSettings(blockRedirects = true)
        LoopbackSite().use { site ->
            launch(site.url("/hostile")).use { scenario ->
                awaitBrowserReady(scenario)
                press(KeyEvent.KEYCODE_BACK) // Leave URL editing so navigation callbacks update it.
                await("Hostile fixture should finish its initial load") {
                    scenario.addressHost() == LOOPBACK_PRIMARY
                }

                SystemClock.sleep(HOSTILE_SETTLE_MS)
                instrumentation.waitForIdleSync()

                assertEquals("Strict redirect must keep the original top-level host", LOOPBACK_PRIMARY,
                    scenario.addressHost())
                assertEquals("Programmatic popup must not make a network request", 0,
                    site.hitCount("/popup"))
                assertEquals("Cross-site redirect must be rejected before a network request", 0,
                    site.hitCount("/redirect"))
                assertEquals("Blocked popup must not create a second tab", View.GONE,
                    scenario.viewVisibility(R.id.tab_strip_scroll))
                scenario.assertAlive()
            }
        }
    }

    @Test
    fun externalHostileUrl_observeAllowedTopLevelHosts() {
        val url = arguments.getString(ARG_HOSTILE_URL).orEmpty()
        assumeTrue("Optional real-site probe requires -e $ARG_HOSTILE_URL https://...", url.isNotBlank())
        prepareSettings(blockRedirects = arguments.boolean(ARG_STRICT_REDIRECTS, true))
        val initialHost = requireNotNull(Uri.parse(url).host) { "hostileUrl must have a host" }
        val allowed = arguments.getString(ARG_ALLOWED_HOSTS)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
            .ifEmpty { setOf(initialHost) }
        val observeSeconds = arguments.positiveInt(ARG_HOSTILE_OBSERVE_SECONDS, 30)

        launch(url).use { scenario ->
            awaitBrowserReady(scenario)
            press(KeyEvent.KEYCODE_BACK)
            val deadline = SystemClock.uptimeMillis() + observeSeconds * 1_000L
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.assertAlive()
                val host = scenario.addressHost()
                if (host != null) {
                    assertTrue("Unexpected top-level host $host; allowed=$allowed", host in allowed)
                }
                SystemClock.sleep(PROBE_INTERVAL_MS)
            }
        }
    }

    @Test
    fun videoFullscreenSoak_activityAndFullscreenRemainStable() {
        val url = arguments.getString(ARG_VIDEO_URL).orEmpty()
        assumeTrue("Video soak requires -e $ARG_VIDEO_URL https://...", url.isNotBlank())
        prepareSettings(blockRedirects = arguments.boolean(ARG_STRICT_REDIRECTS, false))
        val soakMinutes = arguments.positiveInt(ARG_SOAK_MINUTES, 30)
        val probeSeconds = arguments.positiveInt(ARG_SOAK_PROBE_SECONDS, 15)
        val requireFullscreen = arguments.boolean(ARG_REQUIRE_FULLSCREEN, true)
        val startKeys = arguments.getString(ARG_VIDEO_START_KEYS)
            ?.split(',')
            ?.mapNotNull { REMOTE_KEY_NAMES[it.trim().uppercase()] }
            .orEmpty()

        launch(url).use { scenario ->
            awaitBrowserReady(scenario, timeoutMs = PAGE_READY_TIMEOUT_MS)
            startKeys.forEach {
                press(it)
                SystemClock.sleep(REMOTE_STEP_DELAY_MS)
            }
            if (requireFullscreen) {
                await("Video never entered fullscreen", timeoutMs = FULLSCREEN_TIMEOUT_MS) {
                    scenario.viewVisibility(R.id.fullscreen_container) == View.VISIBLE
                }
            }

            val deadline = SystemClock.uptimeMillis() + soakMinutes * 60_000L
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.assertAlive()
                if (requireFullscreen) {
                    assertEquals("Fullscreen unexpectedly exited during soak", View.VISIBLE,
                        scenario.viewVisibility(R.id.fullscreen_container))
                }
                SystemClock.sleep(probeSeconds * 1_000L)
            }
        }
    }

    private fun prepareSettings(blockRedirects: Boolean) = runBlocking {
        SettingsStore(context).apply {
            setBlockPopups(true)
            setBlockRedirects(blockRedirects)
            setDirectNav(false)
            setFirstRunHintShown(true)
        }
    }

    private fun launch(url: String): ActivityScenario<BrowserActivity> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), context, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun awaitBrowserReady(
        scenario: ActivityScenario<BrowserActivity>,
        timeoutMs: Long = BROWSER_READY_TIMEOUT_MS
    ) = await("Browser startup overlay did not clear", timeoutMs) {
        scenario.viewVisibility(R.id.startup_overlay) == View.GONE
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(REMOTE_STEP_DELAY_MS)
    }

    private fun await(message: String, timeoutMs: Long = ASSERT_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(message, condition())
    }

    private fun ActivityScenario<BrowserActivity>.focusedViewId(): Int = read { it.currentFocus?.id ?: View.NO_ID }
    private fun ActivityScenario<BrowserActivity>.viewHasFocus(id: Int): Boolean =
        read { it.findViewById<View>(id).hasFocus() }
    private fun ActivityScenario<BrowserActivity>.viewVisibility(id: Int): Int =
        read { it.findViewById<View>(id).visibility }
    private fun ActivityScenario<BrowserActivity>.addressHost(): String? = read {
        Uri.parse(it.findViewById<android.widget.TextView>(R.id.url_bar).text.toString()).host
    }
    private fun ActivityScenario<BrowserActivity>.imeVisible(): Boolean = read {
        ViewCompat.getRootWindowInsets(it.findViewById(R.id.url_bar))
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    private fun ActivityScenario<BrowserActivity>.assertAlive() {
        assertFalse("Activity entered DESTROYED state", state == Lifecycle.State.DESTROYED)
        assertFalse("Activity is finishing or destroyed", read { it.isFinishing || it.isDestroyed })
    }

    private fun <T> ActivityScenario<BrowserActivity>.read(block: (Activity) -> T): T {
        val result = AtomicReference<T>()
        onActivity { result.set(block(it)) }
        return result.get()
    }

    private fun android.os.Bundle.boolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    private fun android.os.Bundle.positiveInt(key: String, default: Int): Int =
        getString(key)?.toIntOrNull()?.takeIf { it > 0 } ?: default

    /** Tiny deterministic origin used to verify WebView behavior without depending on the internet. */
    private class LoopbackSite : Closeable {
        private val hits = ConcurrentHashMap<String, Int>()
        private val executor = Executors.newCachedThreadPool()
        private val server = ServerSocket(0, 20, InetAddress.getByName("0.0.0.0"))
        private val acceptThread = Thread({ acceptLoop() }, "comet-tv-test-http").apply {
            isDaemon = true
            start()
        }

        fun url(path: String): String = "http://$LOOPBACK_PRIMARY:${server.localPort}$path"
        fun hitCount(path: String): Int = hits[path] ?: 0

        private fun acceptLoop() {
            while (!server.isClosed) {
                runCatching { server.accept() }
                    .onSuccess { socket -> executor.execute { handle(socket) } }
                    .onFailure { if (!server.isClosed) throw it }
            }
        }

        private fun handle(socket: Socket) = socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine().orEmpty()
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            hits.merge(path, 1, Int::plus)
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val body = when (path) {
                "/hostile" -> hostileHtml(server.localPort)
                "/remote" -> REMOTE_HTML
                else -> "<!doctype html><title>$path</title><p>$path reached</p>"
            }.toByteArray(StandardCharsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
            acceptThread.interrupt()
        }

        private companion object {
            const val REMOTE_HTML = """
                <!doctype html><meta name="viewport" content="width=device-width">
                <title>Remote fixture</title>
                <style>body{font:32px sans-serif}button,select{font-size:32px;margin:24px;padding:24px}</style>
                <button autofocus id="first">First action</button>
                <select id="select"><option>One</option><option>Two</option></select>
                <button id="last">Last action</button>
            """

            fun hostileHtml(port: Int) = """
                <!doctype html><title>Hostile fixture</title><h1>Hostile fixture</h1>
                <script>
                  setTimeout(function(){ window.open('http://$LOOPBACK_SECONDARY:$port/popup', '_blank'); }, 250);
                  setTimeout(function(){ location.assign('http://$LOOPBACK_SECONDARY:$port/redirect'); }, 750);
                </script>
            """.trimIndent()
        }
    }

    private companion object {
        const val ARG_PHYSICAL_TV = "physicalTv"
        const val ARG_HOSTILE_URL = "hostileUrl"
        const val ARG_ALLOWED_HOSTS = "allowedHosts"
        const val ARG_HOSTILE_OBSERVE_SECONDS = "hostileObserveSeconds"
        const val ARG_VIDEO_URL = "videoUrl"
        const val ARG_VIDEO_START_KEYS = "videoStartKeys"
        const val ARG_SOAK_MINUTES = "soakMinutes"
        const val ARG_SOAK_PROBE_SECONDS = "soakProbeSeconds"
        const val ARG_REQUIRE_FULLSCREEN = "requireFullscreen"
        const val ARG_STRICT_REDIRECTS = "strictRedirects"
        const val LOOPBACK_PRIMARY = "127.0.0.1"
        const val LOOPBACK_SECONDARY = "127.0.0.2"
        const val REMOTE_STEP_DELAY_MS = 200L
        const val POLL_INTERVAL_MS = 100L
        const val PROBE_INTERVAL_MS = 500L
        const val ASSERT_TIMEOUT_MS = 5_000L
        const val BROWSER_READY_TIMEOUT_MS = 20_000L
        const val PAGE_READY_TIMEOUT_MS = 45_000L
        const val FULLSCREEN_TIMEOUT_MS = 60_000L
        const val HOSTILE_SETTLE_MS = 2_500L

        val REMOTE_KEY_NAMES = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
            "CENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
            "BACK" to KeyEvent.KEYCODE_BACK
        )
    }
}
