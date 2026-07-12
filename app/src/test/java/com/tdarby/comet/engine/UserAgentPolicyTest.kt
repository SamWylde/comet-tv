package com.tdarby.comet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UserAgentPolicyTest {
    @Test fun desktopUaPreservesInstalledWebViewVersions() {
        val mobileUa =
            "Mozilla/5.0 (Linux; Android 14; TV Build/UP1A; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/143.0.7499.40 Mobile Safari/537.36"

        val desktopUa = UserAgentPolicy.userAgent(mobileUa, desktopMode = true)

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.7499.40 Safari/537.36",
            desktopUa
        )
        assertFalse(desktopUa.orEmpty().contains("Android"))
        assertFalse(desktopUa.orEmpty().contains("Mobile"))
        assertFalse(desktopUa.orEmpty().contains("; wv"))
    }

    @Test fun desktopUaDoesNotInventAChromiumVersionForAtypicalProviders() {
        val atypicalUa = "ExampleWebEngine/7.2"

        assertEquals(atypicalUa, UserAgentPolicy.userAgent(atypicalUa, desktopMode = true))
        assertNull(UserAgentPolicy.metadataOverride(atypicalUa, desktopMode = true))
    }

    @Test fun disablingDesktopModeRestoresProviderManagedUaAndMetadata() {
        assertNull(UserAgentPolicy.userAgent("provider UA", desktopMode = false))
        assertNull(UserAgentPolicy.metadataOverride("provider UA", desktopMode = false))
    }

    @Test fun desktopMetadataMatchesTheLegacyLinuxIdentity() {
        assertEquals(
            UserAgentMetadataOverride(
                platform = "Linux",
                platformVersion = "",
                architecture = "x86",
                model = "",
                mobile = false,
                bitness = 64,
                wow64 = false
            ),
            UserAgentPolicy.metadataOverride(
                "Mozilla/5.0 (Linux; Android 14; wv) Chrome/143.0 Mobile Safari/537.36",
                desktopMode = true
            )
        )
    }

    @Test fun desktopBrandDoesNotContradictTheChromeUa() {
        assertEquals("Google Chrome", UserAgentPolicy.brand("Android WebView", desktopMode = true))
        assertEquals("Chromium", UserAgentPolicy.brand("Chromium", desktopMode = true))
        assertEquals("Android WebView", UserAgentPolicy.brand("Android WebView", desktopMode = false))
    }
}
