package com.tdarby.comet.adblock

/**
 * Popup / popunder / forced-new-window suppression for the WebView engine.
 *
 * Layers:
 *  - `javaScriptCanOpenWindowsAutomatically = false` (set on the WebView) blocks non-gesture popups.
 *  - [JS] rejects `window.open` without a live user activation while preserving clicked flows.
 *  - `onCreateWindow` (in the chrome client) routes surviving user requests into managed tabs.
 */
object PopupGuard {

    /** Keep user-clicked windows working while suppressing script-created popunders. */
    fun shouldOpenNewTab(isUserGesture: Boolean, blockingEnabled: Boolean, targetUrl: String?): Boolean =
        !targetUrl.isNullOrBlank() && (isUserGesture || !blockingEnabled)

    /** [JS] wrapped so it does nothing on pages whose host is (or is under) an allowlisted host. */
    fun scriptSkippingHosts(allowlist: Set<String>): String {
        val hosts = allowlist.filter { h -> h.isNotEmpty() && h.all { it.isLetterOrDigit() || it == '.' || it == '-' } }
            .joinToString(",") { "\"$it\"" }
        return "(function(){var a=[$hosts],h=location.hostname.toLowerCase();" +
            "for(var i=0;i<a.length;i++){if(h===a[i]||h.endsWith('.'+a[i]))return;}$JS})();"
    }

    /** Injected at page start when popup blocking is enabled. */
    const val JS: String = """
        (function() {
          try {
            var nativeOpen = window.open;
            window.open = function() {
              var activation = navigator.userActivation;
              if (!activation || activation.isActive) {
                return nativeOpen.apply(window, arguments);
              }
              return null;
            };
          } catch (e) {}
        })();
    """
}
