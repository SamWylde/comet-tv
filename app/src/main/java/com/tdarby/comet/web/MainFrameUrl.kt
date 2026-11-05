package com.tdarby.comet.web

/**
 * True when a TLS error belongs to the page the user is on, as opposed to an image, script or embed
 * it loads. Compared as exact strings with the #fragment dropped (a fragment is never sent to the
 * server). When the main-frame URL is not known yet the error is treated as the page's own, so a
 * failing first load still reaches the error panel.
 *
 * [mainFrameUrl] comes from link navigations, redirects and committed pages. Loads the app starts
 * itself (address bar, bookmarks, home tiles) never pass through shouldOverrideUrlLoading, and
 * onPageStarted only fires once a page commits, so for those the WebView's own [visibleUrl] (which
 * is already the URL being loaded) is the match.
 */
internal fun isMainFrameSslError(errorUrl: String?, mainFrameUrl: String?, visibleUrl: String? = null): Boolean {
    if (mainFrameUrl.isNullOrEmpty()) return true
    val target = errorUrl?.substringBefore('#') ?: return false
    return target == mainFrameUrl.substringBefore('#') || target == visibleUrl?.substringBefore('#')
}
