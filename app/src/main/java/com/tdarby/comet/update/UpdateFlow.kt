package com.tdarby.comet.update

/** What went wrong, so the dialog can say what to do next. */
enum class UpdateError { CHECK_FAILED, NO_NETWORK, DOWNLOAD_FAILED, CHECKSUM_MISMATCH, INSTALLER_UNAVAILABLE }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Available : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Verifying : UpdateState
    data object Ready : UpdateState
    /** The user must allow installs from Comet in system settings. */
    data object NeedsPermission : UpdateState
    /** The system installer was opened; success is not known until the app restarts as the new version. */
    data object Installing : UpdateState
    /** The user came back from the installer still on the old version. */
    data object NotInstalled : UpdateState
    data class Failed(val error: UpdateError) : UpdateState
}

sealed interface UpdateEvent {
    data object CheckStarted : UpdateEvent
    data object UpdateFound : UpdateEvent
    data object NoUpdate : UpdateEvent
    data object CheckFailed : UpdateEvent
    data object DownloadStarted : UpdateEvent
    data class Progress(val percent: Int) : UpdateEvent
    data object DownloadFinished : UpdateEvent
    data class DownloadFailed(val noNetwork: Boolean) : UpdateEvent
    data object ChecksumOk : UpdateEvent
    data object ChecksumBad : UpdateEvent
    data object PermissionMissing : UpdateEvent
    data object InstallerOpened : UpdateEvent
    data object InstallerFailed : UpdateEvent
    data object Cancelled : UpdateEvent
    /** The activity came back to the foreground. */
    data class Resumed(val canInstall: Boolean, val runningVersionCode: Int, val targetVersionCode: Int) : UpdateEvent
}

/** The update dialog's transitions. Events that make no sense in a state are ignored. */
object UpdateFlow {
    fun next(state: UpdateState, event: UpdateEvent): UpdateState = when (event) {
        UpdateEvent.CheckStarted -> if (state is UpdateState.Downloading || state is UpdateState.Verifying ||
            state is UpdateState.Installing) state else UpdateState.Checking
        UpdateEvent.UpdateFound -> if (state == UpdateState.Checking) UpdateState.Available else state
        UpdateEvent.NoUpdate -> if (state == UpdateState.Checking) UpdateState.UpToDate else state
        UpdateEvent.CheckFailed ->
            if (state == UpdateState.Checking) UpdateState.Failed(UpdateError.CHECK_FAILED) else state
        UpdateEvent.DownloadStarted -> when (state) {
            UpdateState.Available, UpdateState.NotInstalled, is UpdateState.Failed -> UpdateState.Downloading(0)
            else -> state
        }
        is UpdateEvent.Progress ->
            if (state is UpdateState.Downloading) UpdateState.Downloading(event.percent.coerceIn(0, 100)) else state
        UpdateEvent.DownloadFinished -> if (state is UpdateState.Downloading) UpdateState.Verifying else state
        is UpdateEvent.DownloadFailed -> if (state is UpdateState.Downloading) {
            UpdateState.Failed(if (event.noNetwork) UpdateError.NO_NETWORK else UpdateError.DOWNLOAD_FAILED)
        } else state
        UpdateEvent.ChecksumOk -> if (state == UpdateState.Verifying) UpdateState.Ready else state
        UpdateEvent.ChecksumBad ->
            if (state == UpdateState.Verifying) UpdateState.Failed(UpdateError.CHECKSUM_MISMATCH) else state
        UpdateEvent.PermissionMissing -> if (state == UpdateState.Ready) UpdateState.NeedsPermission else state
        UpdateEvent.InstallerOpened -> if (state == UpdateState.Ready) UpdateState.Installing else state
        UpdateEvent.InstallerFailed ->
            if (state == UpdateState.Ready) UpdateState.Failed(UpdateError.INSTALLER_UNAVAILABLE) else state
        UpdateEvent.Cancelled -> if (state is UpdateState.Downloading) UpdateState.Available else state
        is UpdateEvent.Resumed -> when {
            // Back from system settings with permission granted: re-check the APK we already have.
            state == UpdateState.NeedsPermission && event.canInstall -> UpdateState.Verifying
            // Back from the installer and still the old version: not installed.
            state == UpdateState.Installing && event.runningVersionCode < event.targetVersionCode ->
                UpdateState.NotInstalled
            state == UpdateState.Installing -> UpdateState.Idle
            else -> state
        }
    }
}
