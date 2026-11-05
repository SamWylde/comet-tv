package com.tdarby.comet.downloads

import android.app.DownloadManager

/** Plain-language cause of a failed or paused download, from DownloadManager's COLUMN_REASON. */
enum class DownloadReason {
    NONE, NO_SPACE, NETWORK_DATA, CANNOT_RESUME, STORAGE_MISSING, FILE_EXISTS, FILE_ERROR,
    TOO_MANY_REDIRECTS, SERVER_ERROR, NOT_FOUND, ACCESS_DENIED,
    WAITING_NETWORK, WAITING_WIFI, WAITING_RETRY, UNKNOWN
}

object DownloadReasons {
    /** [status] is a DownloadManager.STATUS_*; [reason] is its COLUMN_REASON for that status. */
    fun classify(status: Int, reason: Int): DownloadReason = when (status) {
        DownloadManager.STATUS_PAUSED -> when (reason) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> DownloadReason.WAITING_NETWORK
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> DownloadReason.WAITING_WIFI
            DownloadManager.PAUSED_WAITING_TO_RETRY -> DownloadReason.WAITING_RETRY
            else -> DownloadReason.UNKNOWN
        }
        DownloadManager.STATUS_FAILED -> when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> DownloadReason.NO_SPACE
            DownloadManager.ERROR_HTTP_DATA_ERROR -> DownloadReason.NETWORK_DATA
            DownloadManager.ERROR_CANNOT_RESUME -> DownloadReason.CANNOT_RESUME
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> DownloadReason.STORAGE_MISSING
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> DownloadReason.FILE_EXISTS
            DownloadManager.ERROR_FILE_ERROR -> DownloadReason.FILE_ERROR
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> DownloadReason.TOO_MANY_REDIRECTS
            401, 403 -> DownloadReason.ACCESS_DENIED
            404, 410 -> DownloadReason.NOT_FOUND
            in 500..599 -> DownloadReason.SERVER_ERROR
            else -> DownloadReason.UNKNOWN
        }
        else -> DownloadReason.NONE
    }
}
