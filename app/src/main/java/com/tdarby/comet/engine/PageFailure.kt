package com.tdarby.comet.engine

import android.webkit.WebViewClient

enum class PageFailureKind { OFFLINE, DNS, TIMEOUT, CONNECTION, SSL, RENDERER_CRASH }

data class PageFailure(
    val kind: PageFailureKind,
    val url: String,
    val detail: String = ""
)

object PageFailureClassifier {
    fun fromWebError(
        errorCode: Int,
        url: String,
        detail: String,
        isOffline: Boolean = false
    ): PageFailure {
        val kind = when {
            isOffline -> PageFailureKind.OFFLINE
            errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> PageFailureKind.SSL
            else -> when (errorCode) {
                WebViewClient.ERROR_HOST_LOOKUP -> PageFailureKind.DNS
                WebViewClient.ERROR_TIMEOUT -> PageFailureKind.TIMEOUT
                else -> PageFailureKind.CONNECTION
            }
        }
        return PageFailure(kind, url, detail)
    }
}
