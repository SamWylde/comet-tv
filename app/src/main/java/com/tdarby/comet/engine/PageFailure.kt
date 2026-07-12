package com.tdarby.comet.engine

import android.webkit.WebViewClient

enum class PageFailureKind { OFFLINE, DNS, TIMEOUT, CONNECTION, HTTP, SSL, RENDERER_CRASH }

data class PageFailure(
    val kind: PageFailureKind,
    val url: String,
    val detail: String = "",
    val httpStatus: Int? = null
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

    fun fromHttp(status: Int, url: String, reason: String): PageFailure =
        PageFailure(PageFailureKind.HTTP, url, reason, status)
}
