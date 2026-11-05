package com.tdarby.comet.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.tdarby.comet.R
import com.tdarby.comet.adblock.BlockLayer
import com.tdarby.comet.adblock.BlockedLog
import com.tdarby.comet.data.BrowsingIdentity
import com.tdarby.comet.data.BrowsingViewport
import com.tdarby.comet.data.SiteBrowsingSettings
import com.tdarby.comet.data.SiteRemoteMode
import com.tdarby.comet.databinding.DialogSiteControlsBinding

/**
 * One panel for the site on the current page: browsing identity and viewport, text size, remote
 * mode, ads, temporary blocking pauses, what was blocked, and recovery actions. Changes to the
 * controls are applied with Apply; the action buttons act at once.
 *
 * In a private tab the saved controls and Reset are hidden, because private browsing never writes
 * to disk. Pauses are kept in memory only, so they stay.
 */
class SiteControlsDialog(
    private val context: Context,
    private val host: String,
    private val incognito: Boolean,
    private val stored: SiteBrowsingSettings,
    private val allowAds: Boolean,
    private val paused: Set<BlockLayer>,
    private val blocked: List<BlockedLog.Entry>,
    private val listener: Listener
) {
    /** What the panel's controls say when Apply is pressed. */
    data class Result(
        val settings: SiteBrowsingSettings,
        val allowAds: Boolean,
        val paused: Set<BlockLayer>
    )

    interface Listener {
        fun onApply(result: Result)
        fun onReload()
        fun onReset()
        fun onDiagnostics()
    }

    fun show() {
        val ui = DialogSiteControlsBinding.inflate(LayoutInflater.from(context))
        ui.siteHost.text = host

        fun <T> adapter(labels: List<T>) =
            ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

        val modeLabels = listOf(
            context.getString(R.string.settings_mode_default),
            context.getString(R.string.settings_mode_mobile_tv),
            context.getString(R.string.settings_mode_desktop)
        )
        ui.spinnerSiteIdentity.adapter = adapter(modeLabels)
        ui.spinnerSiteViewport.adapter = adapter(modeLabels)
        ui.spinnerSiteIdentity.setSelection(stored.identity.ordinal)
        ui.spinnerSiteViewport.setSelection(stored.viewport.ordinal)
        ui.spinnerSiteIdentity.contentDescription = context.getString(R.string.settings_site_identity)
        ui.spinnerSiteViewport.contentDescription = context.getString(R.string.settings_site_viewport)

        // A stored size outside the presets (a hand-edited value) stays selectable.
        val textSizes = (SiteBrowsingSettings.TEXT_ZOOM_PRESETS + stored.textZoom).distinct().sorted()
        ui.spinnerSiteTextSize.adapter = adapter(textSizes.map { percent ->
            context.getString(
                if (percent == SiteBrowsingSettings.DEFAULT_TEXT_ZOOM) R.string.site_text_size_default
                else R.string.site_text_size_value,
                percent
            )
        })
        ui.spinnerSiteTextSize.setSelection(textSizes.indexOf(stored.textZoom))
        ui.spinnerSiteTextSize.contentDescription = context.getString(R.string.site_text_size)

        val remoteLabels = listOf(
            context.getString(R.string.site_remote_global),
            context.getString(R.string.site_remote_cursor),
            context.getString(R.string.site_remote_direct)
        )
        ui.spinnerSiteRemote.adapter = adapter(remoteLabels)
        ui.spinnerSiteRemote.setSelection(stored.remoteMode.ordinal)
        ui.spinnerSiteRemote.contentDescription = context.getString(R.string.site_remote_mode)

        ui.siteAllowLabel.text = context.getString(R.string.settings_allow_site_host, host)
        ui.switchSiteAllow.isChecked = allowAds
        ui.switchSiteAllow.contentDescription = ui.siteAllowLabel.text

        ui.switchPauseNetwork.isChecked = BlockLayer.NETWORK in paused
        ui.switchPauseCosmetic.isChecked = BlockLayer.COSMETIC in paused
        ui.switchPausePopups.isChecked = BlockLayer.POPUP in paused
        ui.switchPauseRedirects.isChecked = BlockLayer.REDIRECT in paused
        ui.switchPauseNetwork.contentDescription = context.getString(R.string.site_pause_network)
        ui.switchPauseCosmetic.contentDescription = context.getString(R.string.site_pause_cosmetic)
        ui.switchPausePopups.contentDescription = context.getString(R.string.site_pause_popups)
        ui.switchPauseRedirects.contentDescription = context.getString(R.string.site_pause_redirects)

        ui.siteBlockedList.text = blockedText()

        if (incognito) {
            ui.siteSavedGroup.visibility = View.GONE
            ui.siteActionReset.visibility = View.GONE
            ui.siteActionDiagnostics.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.menu_this_site)
            .setView(ui.root)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val settings = if (incognito) stored else SiteBrowsingSettings(
                    identity = BrowsingIdentity.entries[ui.spinnerSiteIdentity.selectedItemPosition],
                    viewport = BrowsingViewport.entries[ui.spinnerSiteViewport.selectedItemPosition],
                    textZoom = textSizes[ui.spinnerSiteTextSize.selectedItemPosition],
                    remoteMode = SiteRemoteMode.entries[ui.spinnerSiteRemote.selectedItemPosition]
                )
                listener.onApply(
                    Result(
                        settings = settings,
                        allowAds = if (incognito) allowAds else ui.switchSiteAllow.isChecked,
                        paused = buildSet {
                            if (ui.switchPauseNetwork.isChecked) add(BlockLayer.NETWORK)
                            if (ui.switchPauseCosmetic.isChecked) add(BlockLayer.COSMETIC)
                            if (ui.switchPausePopups.isChecked) add(BlockLayer.POPUP)
                            if (ui.switchPauseRedirects.isChecked) add(BlockLayer.REDIRECT)
                        }
                    )
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        ui.siteActionReload.setOnClickListener { dialog.dismiss(); listener.onReload() }
        ui.siteActionReset.setOnClickListener { dialog.dismiss(); listener.onReset() }
        ui.siteActionDiagnostics.setOnClickListener { dialog.dismiss(); listener.onDiagnostics() }

        dialog.show()
        (if (incognito) ui.switchPauseNetwork else ui.spinnerSiteIdentity).requestFocus()
    }

    private fun blockedText(): String {
        if (blocked.isEmpty()) return context.getString(R.string.site_blocked_empty)
        return blocked.joinToString("\n") { entry ->
            val layer = context.getString(
                when (entry.layer) {
                    BlockLayer.NETWORK -> R.string.site_blocked_layer_network
                    BlockLayer.COSMETIC -> R.string.site_blocked_layer_cosmetic
                    BlockLayer.POPUP -> R.string.site_blocked_layer_popup
                    BlockLayer.REDIRECT -> R.string.site_blocked_layer_redirect
                }
            )
            if (entry.count > 1) context.getString(R.string.site_blocked_line_count, entry.host, layer, entry.count)
            else context.getString(R.string.site_blocked_line, entry.host, layer)
        }
    }
}
