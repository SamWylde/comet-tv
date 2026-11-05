package com.tdarby.comet.downloads

import android.app.DownloadManager as D
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadReasonsTest {
    @Test fun reasonsAreMappedPerStatus() {
        val table = listOf(
            Triple(D.STATUS_FAILED, D.ERROR_INSUFFICIENT_SPACE, DownloadReason.NO_SPACE),
            Triple(D.STATUS_FAILED, D.ERROR_HTTP_DATA_ERROR, DownloadReason.NETWORK_DATA),
            Triple(D.STATUS_FAILED, D.ERROR_CANNOT_RESUME, DownloadReason.CANNOT_RESUME),
            Triple(D.STATUS_FAILED, D.ERROR_DEVICE_NOT_FOUND, DownloadReason.STORAGE_MISSING),
            Triple(D.STATUS_FAILED, D.ERROR_FILE_ALREADY_EXISTS, DownloadReason.FILE_EXISTS),
            Triple(D.STATUS_FAILED, 404, DownloadReason.NOT_FOUND),
            Triple(D.STATUS_FAILED, 403, DownloadReason.ACCESS_DENIED),
            Triple(D.STATUS_FAILED, 503, DownloadReason.SERVER_ERROR),
            Triple(D.STATUS_FAILED, D.ERROR_UNKNOWN, DownloadReason.UNKNOWN),
            Triple(D.STATUS_PAUSED, D.PAUSED_WAITING_FOR_NETWORK, DownloadReason.WAITING_NETWORK),
            Triple(D.STATUS_PAUSED, D.PAUSED_QUEUED_FOR_WIFI, DownloadReason.WAITING_WIFI),
            Triple(D.STATUS_PAUSED, D.PAUSED_WAITING_TO_RETRY, DownloadReason.WAITING_RETRY),
            // A code that only means something for the other status must not leak across.
            Triple(D.STATUS_PAUSED, D.ERROR_INSUFFICIENT_SPACE, DownloadReason.UNKNOWN),
            Triple(D.STATUS_RUNNING, D.ERROR_INSUFFICIENT_SPACE, DownloadReason.NONE),
            Triple(D.STATUS_SUCCESSFUL, 0, DownloadReason.NONE),
        )
        for ((status, reason, expected) in table) {
            assertEquals("status=$status reason=$reason", expected, DownloadReasons.classify(status, reason))
        }
    }
}
