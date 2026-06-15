package com.tdarby.comet.adblock

/**
 * Popup / popunder / forced-new-window suppression for the WebView engine.
 *
 * Layers:
 *  - `javaScriptCanOpenWindowsAutomatically = false` (set on the WebView) blocks non-gesture popups.
 *  - [JS] neutralizes `window.open` and rewrites `target=_blank` links to open in the same tab, so
 *    ad popups die while legitimate "open in new tab" links still navigate.
 *  - `onCreateWindow` (in the chrome client) routes any surviving new-window request into the
 *    current tab instead of spawning a window.
 *
 * GeckoView gets equivalent behavior by denying `onNewSession`.
 */
object PopupGuard {

    /** Injected at page start when popup blocking is enabled. */
    const val JS: String = """
        (function() {
          try {
            window.open = function() { return null; };
          } catch (e) {}
          document.addEventListener('click', function(e) {
            try {
              var a = e.target && e.target.closest && e.target.closest('a[target=_blank]');
              if (a) { a.target = '_self'; }
            } catch (err) {}
          }, true);
        })();
    """
}
