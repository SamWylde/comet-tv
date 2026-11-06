package com.tdarby.comet.ui

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tdarby.comet.R
import com.tdarby.comet.data.PlaybackRecord
import com.tdarby.comet.data.PlaybackResumePolicy
import com.tdarby.comet.data.PlaybackResumeStore
import com.tdarby.comet.data.ResumeAction
import com.tdarby.comet.engine.BrowserEngine
import com.tdarby.comet.engine.PlaybackSample
import com.tdarby.comet.engine.PlaybackScripts

/**
 * Watches the visible page's main video, keeps its continue-watching record up to date and offers
 * to resume when the same video comes back. It polls only while a normal page is shown and the
 * screen is in front, and stops once a page has shown no video for a while. Never used for
 * private tabs: the Activity doesn't create it there.
 */
class PlaybackResumeController(
    private val activity: AppCompatActivity,
    private val store: PlaybackResumeStore,
    private val enabled: () -> Boolean,
    /** True when a web page (not home, not the error panel) is what the user sees. */
    private val pageShown: () -> Boolean,
    private val engine: () -> BrowserEngine?
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var inForeground = true

    private var pageKey: String? = null
    private var resolved = false
    private var emptyStreak = 0
    private var lastSavedAt = 0L
    private var wasPaused = true
    private var last: Last? = null

    private var offerDialog: AlertDialog? = null
    private var offerKeepsRecord = false

    private data class Last(val url: String, val title: String, val sample: PlaybackSample)

    private val tick = Runnable { poll() }
    // Missing the prompt is not a choice: keep the spot (it is replaced once the video plays past 30 s).
    private val autoDismiss = Runnable { dismissOffer(keepRecord = true) }

    /** Start (or continue) polling; safe to call from any hook as often as needed. */
    fun ensurePolling() {
        syncPage()
        if (running || !inForeground || !enabled() || !pageShown() || pageKey == null) return
        running = true
        handler.postDelayed(tick, FIRST_DELAY_MS)
    }

    /** A page finished loading: it may have a video now, so poll again even if it had none before. */
    fun onPageFinished() {
        emptyStreak = 0
        ensurePolling()
    }

    fun onPause() {
        inForeground = false
        handler.removeCallbacks(tick)
        running = false
        // Best-effort live reading first; the cached reading covers a page that stops answering.
        val e = engine()
        val url = pageKey
        if (enabled() && pageShown() && e != null && url != null) {
            (e.view as? WebView)?.evaluateJavascript(PlaybackScripts.PROBE) { raw ->
                val now = engine()
                if (now?.currentUrl()?.let(PlaybackResumePolicy::key) == url) {
                    PlaybackScripts.parse(raw)?.takeIf { it.ready }?.let {
                        persist(url, now.currentTitle().orEmpty(), it, force = true)
                    }
                }
            }
        }
        flushCached()
        dismissOffer(keepRecord = true)
    }

    fun onResume() {
        inForeground = true
        ensurePolling()
    }

    /** The user cleared browsing data: forget everything held in memory too. */
    fun reset() {
        dismissOffer(keepRecord = true)
        last = null
        pageKey = null
        resolved = false
        lastSavedAt = 0L
    }

    fun destroy() {
        handler.removeCallbacks(tick)
        running = false
        dismissOffer(keepRecord = true)
    }

    /** Detect a changed page (new URL, tab switch, back to home): save the old one, start over for the new. */
    private fun syncPage() {
        val url = engine()?.currentUrl()?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?.let(PlaybackResumePolicy::key)
        if (url == pageKey) return
        flushCached()
        dismissOffer(keepRecord = true)
        last = null
        pageKey = url
        resolved = false
        emptyStreak = 0
        lastSavedAt = 0L
        wasPaused = true
    }

    private fun poll() {
        running = false
        if (!inForeground || !enabled() || !pageShown()) return
        syncPage()
        val e = engine() ?: return
        val url = pageKey ?: return
        val webView = e.view as? WebView ?: return
        webView.evaluateJavascript(PlaybackScripts.PROBE) { raw -> onProbe(url, PlaybackScripts.parse(raw)) }
        if (emptyStreak >= MAX_EMPTY_POLLS) return
        running = true
        handler.postDelayed(tick, if (resolved) POLL_MS else QUICK_POLL_MS)
    }

    private fun onProbe(url: String, sample: PlaybackSample?) {
        val e = engine() ?: return
        if (url != pageKey || e.currentUrl()?.let(PlaybackResumePolicy::key) != url) return
        if (sample == null || sample.videoCount == 0) {
            emptyStreak++
            return
        }
        emptyStreak = 0
        if (!sample.ready || sample.live) return
        val title = e.currentTitle().orEmpty()

        if (!resolved) {
            resolved = true
            val record = store.find(url)
            if (record != null && PlaybackResumePolicy.shouldOffer(record, sample.duration, sample.position)) {
                offer(record)
                return
            }
        }
        if (offerDialog != null) return
        last = Last(url, title, sample)
        val becamePaused = sample.paused && !wasPaused
        wasPaused = sample.paused
        persist(url, title, sample, force = becamePaused)
    }

    private fun flushCached() {
        val l = last ?: return
        last = null
        persist(l.url, l.title, l.sample, force = true)
    }

    private fun persist(url: String, title: String, sample: PlaybackSample, force: Boolean) {
        if (!enabled()) return
        when (PlaybackResumePolicy.action(sample.position, sample.duration, sample.live)) {
            ResumeAction.SAVE -> {
                val now = System.currentTimeMillis()
                if (!PlaybackResumePolicy.saveDue(lastSavedAt, now, force)) return
                lastSavedAt = now
                store.save(PlaybackRecord(url, title.ifBlank { url }, sample.position, sample.duration, now))
            }
            ResumeAction.DROP -> store.drop(url)
            ResumeAction.IGNORE -> Unit
        }
    }

    private fun offer(record: PlaybackRecord) {
        if (activity.isFinishing || activity.isDestroyed) return
        var resumed = false
        offerKeepsRecord = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.resume_prompt, PlaybackResumePolicy.clock(record.position)))
            .setPositiveButton(R.string.resume_action) { _, _ ->
                resumed = true
                (engine()?.view as? WebView)?.evaluateJavascript(
                    PlaybackScripts.seek(record.position, record.duration), null
                )
            }
            .setNegativeButton(R.string.start_over_action, null)
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacks(autoDismiss)
            if (offerDialog === dialog) offerDialog = null
            // Resume and the timeout keep the record (it keeps updating); Start over and BACK forget it.
            if (!resumed && !offerKeepsRecord) store.drop(record.url)
        }
        offerDialog = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        handler.postDelayed(autoDismiss, OFFER_TIMEOUT_MS)
    }

    private fun dismissOffer(keepRecord: Boolean) {
        val d = offerDialog ?: return
        offerKeepsRecord = keepRecord
        d.dismiss()
    }

    private companion object {
        const val FIRST_DELAY_MS = 2_000L
        const val QUICK_POLL_MS = 2_000L
        const val POLL_MS = 5_000L
        const val MAX_EMPTY_POLLS = 8
        const val OFFER_TIMEOUT_MS = 8_000L
    }
}
