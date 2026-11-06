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
import com.tdarby.comet.util.UrlUtils
import java.net.Inet4Address
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
        val router = HandoffRouter(session, ::receive)
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
    }
}
