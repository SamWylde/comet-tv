package com.tdarby.comet.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdarby.comet.BuildConfig
import com.tdarby.comet.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs the guided update in one dialog whose text follows [UpdateFlow]. Automatic checks never
 * interrupt a fullscreen video: the update is held and shown when fullscreen ends (or from the menu).
 */
class UpdateController(
    private val activity: AppCompatActivity,
    private val isFullscreen: () -> Boolean
) {
    private val checker = UpdateChecker(activity)
    private var state: UpdateState = UpdateState.Idle
    private var manifest: ReleaseManifest? = null
    private var verifiedApk: File? = null
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private var held = false

    /** Version name of an update found during fullscreen video and not shown yet (for a menu entry). */
    val pendingVersionName: String? get() = if (held) manifest?.versionName else null

    fun check(manual: Boolean) {
        val busy = state is UpdateState.Downloading || state == UpdateState.Verifying ||
            state == UpdateState.Ready || state == UpdateState.NeedsPermission || state == UpdateState.Installing
        if (busy) { if (manual) show(); return }
        dispatch(UpdateEvent.CheckStarted)
        if (manual) show()
        job = activity.lifecycleScope.launch {
            val found = checker.check()
            when {
                found == null -> {
                    dispatch(UpdateEvent.CheckFailed)
                    if (!manual) state = UpdateState.Idle // a silent check that fails says nothing
                }
                !checker.isNewer(found) -> {
                    dispatch(UpdateEvent.NoUpdate)
                    if (!manual) state = UpdateState.Idle
                }
                else -> {
                    manifest = found
                    dispatch(UpdateEvent.UpdateFound)
                    if (manual || !isFullscreen()) show() else held = true
                }
            }
        }
    }

    /** Call when fullscreen video ends: shows an update that was held back. */
    fun onFullscreenEnded() {
        if (held) show()
    }

    /** Show the dialog for a held update (menu entry). */
    fun showPending() = show()

    fun onResume() {
        val target = manifest ?: return
        val before = state
        val canInstall = activity.packageManager.canRequestPackageInstalls()
        dispatch(UpdateEvent.Resumed(canInstall, BuildConfig.VERSION_CODE, target.versionCode))
        if (before == UpdateState.NeedsPermission && state == UpdateState.Verifying) verifyThenInstall()
    }

    private fun dispatch(event: UpdateEvent) {
        state = UpdateFlow.next(state, event)
        render()
    }

    private fun show() {
        held = false
        if (dialog?.isShowing == true) { render(); return }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.menu_update)
            .setMessage(" ")
            .setPositiveButton(" ", null)
            .setNegativeButton(" ", null)
            .setOnDismissListener {
                dialog = null
                if (state is UpdateState.Downloading) cancelDownload()
            }
            .show()
        render()
    }

    private fun render() {
        val d = dialog?.takeIf { it.isShowing } ?: return
        val s = state
        val name = manifest?.versionName.orEmpty()
        val notes = manifest?.notes.orEmpty()
        var primary: Pair<Int, () -> Unit>? = null
        var secondary: Pair<Int, () -> Unit> = R.string.action_close to { d.dismiss() }
        val message: String = when (s) {
            UpdateState.Idle -> { d.dismiss(); return }
            UpdateState.Checking -> activity.getString(R.string.update_state_checking)
            UpdateState.UpToDate -> activity.getString(R.string.update_none)
            UpdateState.Available -> {
                primary = R.string.update_install to ::startDownload
                secondary = R.string.action_cancel to { d.dismiss() }
                activity.getString(R.string.update_available, name) + if (notes.isNotBlank()) "\n\n$notes" else ""
            }
            is UpdateState.Downloading -> {
                secondary = R.string.action_cancel to ::cancelDownload
                activity.getString(R.string.update_state_downloading, s.percent)
            }
            UpdateState.Verifying -> activity.getString(R.string.update_state_verifying)
            UpdateState.Ready -> {
                primary = R.string.update_install to ::install
                activity.getString(R.string.update_state_ready, name)
            }
            UpdateState.NeedsPermission -> {
                primary = R.string.update_open_settings to ::openInstallSettings
                activity.getString(R.string.update_state_permission)
            }
            UpdateState.Installing -> activity.getString(R.string.update_state_installing)
            UpdateState.NotInstalled -> {
                primary = R.string.update_retry to ::retryInstall
                activity.getString(R.string.update_state_not_installed)
            }
            is UpdateState.Failed -> {
                primary = R.string.update_retry to when (s.error) {
                    UpdateError.CHECK_FAILED -> ({ check(manual = true) })
                    UpdateError.INSTALLER_UNAVAILABLE -> ::install
                    else -> ::startDownload
                }
                activity.getString(
                    when (s.error) {
                        UpdateError.CHECK_FAILED -> R.string.update_check_failed_detail
                        UpdateError.NO_NETWORK -> R.string.update_error_no_network
                        UpdateError.DOWNLOAD_FAILED -> R.string.update_failed
                        UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum
                        UpdateError.INSTALLER_UNAVAILABLE -> R.string.update_install_failed
                    }
                )
            }
        }
        d.setMessage(message)
        bind(d, AlertDialog.BUTTON_POSITIVE, primary)
        bind(d, AlertDialog.BUTTON_NEGATIVE, secondary)
    }

    private fun bind(d: AlertDialog, which: Int, action: Pair<Int, () -> Unit>?) {
        val button = d.getButton(which) ?: return
        if (action == null) { button.visibility = View.GONE; return }
        button.visibility = View.VISIBLE
        button.text = activity.getString(action.first)
        button.setOnClickListener { action.second() } // replaces the default dismiss-on-click
    }

    private fun startDownload() {
        val m = manifest ?: return
        verifiedApk = null
        dispatch(UpdateEvent.DownloadStarted)
        job = activity.lifecycleScope.launch {
            val result = checker.download(m) { percent ->
                activity.runOnUiThread { dispatch(UpdateEvent.Progress(percent)) }
            }
            when (result) {
                is UpdateChecker.DownloadResult.Failed -> dispatch(UpdateEvent.DownloadFailed(result.noNetwork))
                is UpdateChecker.DownloadResult.Done -> {
                    dispatch(UpdateEvent.DownloadFinished)
                    verifyThenInstall()
                }
            }
        }
    }

    private fun cancelDownload() {
        job?.cancel()
        dispatch(UpdateEvent.Cancelled)
    }

    /** Hash check of the APK on disk; on success go to Ready and try the install. */
    private fun verifyThenInstall() {
        val m = manifest ?: return
        job = activity.lifecycleScope.launch {
            val apk = checker.apkFile()
            if (!apk.isFile) {
                // Gone while waiting (cache cleared): fetch it again.
                state = UpdateState.Available
                startDownload()
            } else if (checker.verify(apk, m)) {
                verifiedApk = apk
                dispatch(UpdateEvent.ChecksumOk)
                install()
            } else {
                dispatch(UpdateEvent.ChecksumBad)
            }
        }
    }

    private fun install() {
        val apk = verifiedApk ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            dispatch(UpdateEvent.PermissionMissing)
            return
        }
        if (checker.install(apk)) dispatch(UpdateEvent.InstallerOpened) else dispatch(UpdateEvent.InstallerFailed)
    }

    private fun retryInstall() {
        // The verified file is still on disk: check and reinstall it; otherwise download again.
        if (verifiedApk?.isFile == true) {
            dispatch(UpdateEvent.DownloadStarted)
            dispatch(UpdateEvent.DownloadFinished)
            verifyThenInstall()
        } else startDownload()
    }

    private fun openInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(intent) }
    }
}
