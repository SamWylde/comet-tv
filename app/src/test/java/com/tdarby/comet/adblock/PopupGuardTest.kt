package com.tdarby.comet.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGuardTest {
    @Test fun scriptAllowsActivatedWindowsAndRejectsUnactivatedOnes() {
        assertTrue(PopupGuard.JS.contains("navigator.userActivation"))
        assertTrue(PopupGuard.JS.contains("!activation || activation.isActive"))
        assertTrue(PopupGuard.JS.contains("nativeOpen.apply"))
        assertTrue(PopupGuard.JS.contains("return null"))
        assertFalse(PopupGuard.JS.contains("a[target=_blank]"))
    }

    @Test fun userInitiatedWindowsSurvivePopupBlocking() {
        assertTrue(PopupGuard.shouldOpenNewTab(
            isUserGesture = true,
            blockingEnabled = true,
            targetUrl = "https://example.test/player"
        ))
    }

    @Test fun programmaticWindowsFollowPopupSettingAndRequireAUrl() {
        assertFalse(PopupGuard.shouldOpenNewTab(
            isUserGesture = false,
            blockingEnabled = true,
            targetUrl = "https://example.test/popunder"
        ))
        assertTrue(PopupGuard.shouldOpenNewTab(
            isUserGesture = false,
            blockingEnabled = false,
            targetUrl = "https://example.test/window"
        ))
        assertFalse(PopupGuard.shouldOpenNewTab(
            isUserGesture = true,
            blockingEnabled = false,
            targetUrl = null
        ))
    }
}
