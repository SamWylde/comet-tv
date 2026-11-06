package com.tdarby.comet.handoff

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tdarby.comet.R
import com.tdarby.comet.data.SiteItem
import com.tdarby.comet.ui.BookmarkFiles
import com.tdarby.comet.util.UrlUtils
import java.net.Inet4Address
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "Send from phone" dialog and the server behind it. The server runs only while the dialog is
 * open; [close] (dialog dismissed, activity stopped or destroyed) shuts it down. Nothing opens until
 * the user confirms on the TV. Nothing is written to disk, so it is safe in a private window: the
 * link opens in the activity that owns this controller.
 */
class HandoffController(
    private val activity: AppCompatActivity,
    private val searchTemplate: () -> String,
    /** Reads the bookmarks (called on the main thread). Null, with [onImportBookmarks] null, means no backup routes. */
    private val bookmarks: (() -> List<SiteItem>)? = null,
    /** Adds the bookmarks of a backup the user accepted on the TV: the items and the count dropped as malformed. */
    private val onImportBookmarks: ((List<SiteItem>, Int) -> Unit)? = null,
    private val onOpen: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false
    private var server: HandoffServer? = null
    private var dialog: AlertDialog? = null
    private var confirmDialog: AlertDialog? = null
    private val asking = AtomicBoolean(false)

    private var qrView: ImageView? = null
    private var statusView: TextView? = null
    private var addressView: TextView? = null
    private var codeView: TextView? = null

    fun show() {
        val lan = LanAddress.find(activity)
        if (lan == null) {
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.handoff_title)
                .setMessage(R.string.handoff_no_network)
                .setNegativeButton(R.string.action_close, null)
                .setOnDismissListener { close() }
                .show()
            return
        }
        val session = HandoffSession.generate()
        val router = HandoffRouter(session, backupHooks(), ::receive)
        val srv = HandoffServer(lan, session, router, object : HandoffServer.Listener {
            override fun onReady(port: Int) {
                main.post { if (!closed) showReady(lan, port, session) }
            }

            override fun onEnded(reason: HandoffServer.EndReason) {
                main.post { if (!closed) showEnded(reason) }
            }
        })
        server = srv
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.handoff_title)
            .setView(buildContent())
            .setNegativeButton(R.string.action_close, null)
            .setOnDismissListener { close() }
            .show()
        srv.start()
    }

    /** Stops the server and closes both dialogs. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        server?.stop()
        server = null
        main.removeCallbacksAndMessages(null)
        confirmDialog?.dismiss()
        confirmDialog = null
        dialog?.dismiss()
        dialog = null
    }

    private fun buildContent(): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val qr = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.WHITE)
            visibility = View.INVISIBLE
        }
        val status = TextView(activity).apply {
            textSize = 16f
            setText(R.string.handoff_starting)
        }
        val address = TextView(activity).apply {
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        val code = TextView(activity).apply {
            // Sized so "Code: 123456" fits on one line beside the QR code.
            textSize = 24f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding(0, dp(8), 0, 0)
        }
        qrView = qr
        statusView = status
        addressView = address
        codeView = code

        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, 0, 0)
            addView(status)
            addView(address)
            addView(code)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(qr, LinearLayout.LayoutParams(dp(200), dp(200)))
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun showReady(lan: Inet4Address, port: Int, session: HandoffSession) {
        val base = "http://${lan.hostAddress}:$port"
        qrView?.setImageBitmap(QrBitmap.create("$base/t/${session.token}", 400))
        qrView?.visibility = View.VISIBLE
        statusView?.setText(R.string.handoff_scan)
        addressView?.text = base
        codeView?.text = activity.getString(R.string.handoff_code, session.code)
    }

    private fun showEnded(reason: HandoffServer.EndReason) {
        qrView?.visibility = View.INVISIBLE
        addressView?.text = ""
        codeView?.text = ""
        statusView?.setText(
            when (reason) {
                HandoffServer.EndReason.EXPIRED -> R.string.handoff_ended_expired
                HandoffServer.EndReason.LOCKED -> R.string.handoff_ended_locked
                HandoffServer.EndReason.FAILED -> R.string.handoff_start_failed
            }
        )
    }

    /** Called on a server thread with text that already passed [HandoffInput]. */
    private fun receive(text: String): SendOutcome {
        if (closed) return SendOutcome.CLOSED
        if (!asking.compareAndSet(false, true)) return SendOutcome.BUSY
        main.post { confirm(text) }
        return SendOutcome.ACCEPTED
    }

    private fun backupHooks(): HandoffBackup? {
        val read = bookmarks ?: return null
        val import = onImportBookmarks ?: return null
        return HandoffBackup(
            bookmarks = { readOnMain(read) },
            fileName = { BookmarkFiles.newFileName() },
            exportedAt = { BookmarkFiles.isoDate() },
            receive = { items, skipped -> receiveBackup(items, skipped, import) }
        )
    }

    /** Runs [read] on the main thread (the store is touched there only) and waits briefly; null on a timeout. */
    private fun readOnMain(read: () -> List<SiteItem>): List<SiteItem>? {
        val task = FutureTask(Callable { read().toList() })
        main.post(task)
        return try {
            task.get(MAIN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(false)
            null
        }
    }

    /** Called on a server thread with a backup that already passed [BookmarkTransfer]. */
    private fun receiveBackup(
        items: List<SiteItem>,
        skipped: Int,
        import: (List<SiteItem>, Int) -> Unit
    ): SendOutcome {
        if (closed) return SendOutcome.CLOSED
        if (!asking.compareAndSet(false, true)) return SendOutcome.BUSY
        main.post { confirmImport(items, skipped, import) }
        return SendOutcome.ACCEPTED
    }

    private fun confirmImport(items: List<SiteItem>, skipped: Int, import: (List<SiteItem>, Int) -> Unit) {
        if (closed) return
        confirmDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.handoff_import_title)
            .setMessage(activity.getString(R.string.handoff_import_message, items.size))
            .setPositiveButton(R.string.handoff_import_action) { _, _ -> import(items, skipped) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                confirmDialog = null
                asking.set(false)
            }
            .show()
    }

    private fun confirm(text: String) {
        if (closed) return
        val target = UrlUtils.toUrlOrSearch(text, searchTemplate())
        if (!target.startsWith("http://", ignoreCase = true) && !target.startsWith("https://", ignoreCase = true)) {
            asking.set(false)
            return
        }
        val shown = if (target.length > MAX_SHOWN_CHARS) target.take(MAX_SHOWN_CHARS) + "…" else target
        confirmDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.handoff_confirm_title)
            .setMessage(activity.getString(R.string.handoff_confirm_message, shown))
            .setPositiveButton(R.string.handoff_open_new_tab) { _, _ -> onOpen(target) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                confirmDialog = null
                asking.set(false)
            }
            .show()
    }

    private companion object {
        const val MAX_SHOWN_CHARS = 300
        const val MAIN_READ_TIMEOUT_MS = 2_000L
    }
}
