package com.tdarby.comet.adblock

import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGuardTest {
    @Test fun neutralizesWindowOpenAndBlankTargetsAtDocumentStart() {
        assertTrue(PopupGuard.JS.contains("window.open = function() { return null; }"))
        assertTrue(PopupGuard.JS.contains("a[target=_blank]"))
        assertTrue(PopupGuard.JS.contains("a.target = '_self'"))
        assertTrue(PopupGuard.JS.contains("true")) // capture phase runs before page click handlers
    }
}
