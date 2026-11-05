package com.tdarby.comet.web

/**
 * True when a TLS error belongs to the page the user is on, as opposed to an image, script or embed
 * it loads. Compared as exact strings with the #fragment dropped (a fragment is never sent to the
 * server). When the main-frame URL is not known yet the error is treated as the page's own, so a
 * failing first load still reaches the error panel.
 */
internal fun isMainFrameSslError(errorUrl: String?, mainFrameUrl: String?): Boolean {
    if (mainFrameUrl.isNullOrEmpty()) return true
    return errorUrl?.substringBefore('#') == mainFrameUrl.substringBefore('#')
}
