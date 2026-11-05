package com.tdarby.comet.update

import com.tdarby.comet.update.UpdateEvent as E
import com.tdarby.comet.update.UpdateState as S
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateFlowTest {
    private val resumedNoPerm = E.Resumed(canInstall = false, runningVersionCode = 5, targetVersionCode = 6)
    private val resumedPerm = E.Resumed(canInstall = true, runningVersionCode = 5, targetVersionCode = 6)

    @Test fun transitions() {
        val table = listOf(
            Triple(S.Idle, E.CheckStarted, S.Checking),
            Triple(S.Checking, E.UpdateFound, S.Available),
            Triple(S.Checking, E.NoUpdate, S.UpToDate),
            Triple(S.Checking, E.CheckFailed, S.Failed(UpdateError.CHECK_FAILED)),
            Triple(S.Available, E.DownloadStarted, S.Downloading(0)),
            Triple(S.Downloading(0), E.Progress(40), S.Downloading(40)),
            Triple(S.Downloading(40), E.DownloadFinished, S.Verifying),
            Triple(S.Downloading(40), E.DownloadFailed(noNetwork = true), S.Failed(UpdateError.NO_NETWORK)),
            Triple(S.Downloading(40), E.DownloadFailed(noNetwork = false), S.Failed(UpdateError.DOWNLOAD_FAILED)),
            Triple(S.Downloading(40), E.Cancelled, S.Available),
            Triple(S.Verifying, E.ChecksumOk, S.Ready),
            Triple(S.Verifying, E.ChecksumBad, S.Failed(UpdateError.CHECKSUM_MISMATCH)),
            Triple(S.Failed(UpdateError.DOWNLOAD_FAILED), E.DownloadStarted, S.Downloading(0)),
            Triple(S.Ready, E.PermissionMissing, S.NeedsPermission),
            Triple(S.Ready, E.InstallerOpened, S.Installing),
            Triple(S.Ready, E.InstallerFailed, S.Failed(UpdateError.INSTALLER_UNAVAILABLE)),
            // Returning from system settings: carry on only once install is allowed, without downloading again.
            Triple(S.NeedsPermission, resumedNoPerm, S.NeedsPermission),
            Triple(S.NeedsPermission, resumedPerm, S.Verifying),
            // Returning from the installer on the same version is not a success.
            Triple(S.Installing, resumedNoPerm, S.NotInstalled),
            Triple(S.NotInstalled, E.DownloadStarted, S.Downloading(0)),
            // Nonsense events and a late progress tick do not move the state.
            Triple(S.Ready, E.Progress(10), S.Ready),
            Triple(S.Available, E.ChecksumOk, S.Available),
            Triple(S.Installing, E.CheckStarted, S.Installing),
            Triple(S.Downloading(10), E.CheckStarted, S.Downloading(10)),
        )
        for ((from, event, to) in table) {
            assertEquals("$from + $event", to, UpdateFlow.next(from, event))
        }
    }
}
