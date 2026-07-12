package com.tdarby.comet.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportTest {
    @Test fun builderKeepsOnlyLowercaseHostFromCurrentUrl() {
        val report = SupportReportBuilder.build(input(currentUrl = "https://User:secret@Video.Example/path?q=private"))

        assertEquals("video.example", report.currentHost)
        assertFalse(report.toCopyText().contains("secret"))
        assertFalse(report.toCopyText().contains("/path"))
    }

    @Test fun formatterIncludesEveryCompatibilityField() {
        val report = SupportReportBuilder.build(
            input(
                currentUrl = "https://video.example/watch/42",
                clientHints = UserAgentClientHints(
                    brands = listOf(UserAgentBrand("Google Chrome", "143")),
                    platform = "Linux",
                    platformVersion = "",
                    architecture = "x86",
                    model = "",
                    mobile = false,
                    bitness = 64,
                    wow64 = false
                )
            )
        )

        val text = report.toCopyText()
        assertTrue(text.contains("Comet: 1.1.0 (12)"))
        assertTrue(text.contains("WebView package: com.google.android.webview"))
        assertTrue(text.contains("Android: 14 (SDK 34)"))
        assertTrue(text.contains("Device: Google Chromecast"))
        assertTrue(text.contains("Identity: Desktop"))
        assertTrue(text.contains("Viewport: Desktop, 1280 CSS px, wide=true, overview=true"))
        assertTrue(text.contains("User-Agent: Mozilla/5.0 test"))
        assertTrue(text.contains("UA-CH brands: Google Chrome 143"))
        assertTrue(text.contains("UA-CH platform: Linux"))
        assertTrue(text.contains("UA-CH platform version: Unknown"))
        assertTrue(text.contains("UA-CH mobile: false"))
        assertTrue(text.contains("UA-CH bitness: 64"))
        assertTrue(text.contains("Fullscreen API enabled: true"))
        assertTrue(text.endsWith("Current host: video.example"))
    }

    @Test fun missingRuntimeValuesAreRenderedAsUnknown() {
        val report = SupportReportBuilder.build(
            SupportReportInput(
                cometVersionName = " ", cometVersionCode = null,
                webViewPackage = null, webViewVersion = null,
                androidVersion = null, androidSdk = null,
                deviceManufacturer = null, deviceModel = null,
                activeIdentity = null, viewport = null, userAgent = null,
                clientHints = null, fullscreenEnabled = null, currentUrl = "not a URL"
            )
        )

        assertEquals("Unknown", report.currentHost)
        assertTrue(report.toCopyText().contains("Fullscreen API enabled: Unknown"))
        assertTrue(report.toCopyText().contains("UA-CH brands: Unknown"))
    }

    private fun input(
        currentUrl: String,
        clientHints: UserAgentClientHints? = null
    ) = SupportReportInput(
        cometVersionName = "1.1.0",
        cometVersionCode = 12,
        webViewPackage = "com.google.android.webview",
        webViewVersion = "143.0.7499.40",
        androidVersion = "14",
        androidSdk = 34,
        deviceManufacturer = "Google",
        deviceModel = "Chromecast",
        activeIdentity = "Desktop",
        viewport = SupportViewport("Desktop", 1280, true, true),
        userAgent = "Mozilla/5.0 test",
        clientHints = clientHints,
        fullscreenEnabled = true,
        currentUrl = currentUrl
    )
}
