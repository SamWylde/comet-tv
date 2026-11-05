package com.tdarby.comet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteBrowsingSettingsTest {
    @Test fun defaultPolicyPreservesTheExistingGlobalDesktopFallback() {
        val mobile = SiteBrowsingPolicy.resolve(null, globalDesktopMode = false)
        assertEquals(BrowsingIdentity.MOBILE_TV, mobile.identity)
        assertEquals(BrowsingViewport.MOBILE_TV, mobile.viewport)
        assertFalse(mobile.desktopMode)
        assertFalse(mobile.useWideViewPort)

        val desktop = SiteBrowsingPolicy.resolve(
            SiteBrowsingSettings(),
            globalDesktopMode = true
        )
        assertEquals(BrowsingIdentity.DESKTOP, desktop.identity)
        assertEquals(BrowsingViewport.DESKTOP, desktop.viewport)
        assertTrue(desktop.desktopMode)
        assertTrue(desktop.loadWithOverviewMode)
    }

    @Test fun identityAndViewportCanBeOverriddenIndependently() {
        val resolved = SiteBrowsingPolicy.resolve(
            SiteBrowsingSettings(
                identity = BrowsingIdentity.MOBILE_TV,
                viewport = BrowsingViewport.DESKTOP
            ),
            globalDesktopMode = true
        )

        assertFalse(resolved.desktopMode)
        assertTrue(resolved.useWideViewPort)
    }

    @Test fun defaultViewportFollowsTheResolvedIdentity() {
        val resolved = SiteBrowsingPolicy.resolve(
            SiteBrowsingSettings(identity = BrowsingIdentity.DESKTOP),
            globalDesktopMode = false
        )

        assertEquals(BrowsingViewport.DESKTOP, resolved.viewport)
    }

    @Test fun exactHostLookupNormalizesCaseUnicodeAndTrailingDot() {
        val settings = SiteBrowsingSettings(identity = BrowsingIdentity.DESKTOP)
        val map = mapOf("xn--bcher-kva.example" to settings)

        assertEquals(settings, SiteBrowsingPolicy.settingsForHost(map, "BÜCHER.example."))
        assertNull(SiteBrowsingPolicy.settingsForHost(map, "sub.bücher.example"))
        assertNull(SiteBrowsingPolicy.normalizeHost("https://example.com/path"))
        assertEquals("::1", SiteBrowsingPolicy.normalizeHost("[::1]"))
        assertNull(SiteBrowsingPolicy.normalizeHost(":::"))
    }

    @Test fun jsonRoundTripIsDeterministicAndDropsAllDefaultEntries() {
        val original = linkedMapOf(
            "Second.Example." to SiteBrowsingSettings(
                BrowsingIdentity.MOBILE_TV,
                BrowsingViewport.DESKTOP
            ),
            "first.example" to SiteBrowsingSettings(BrowsingIdentity.DESKTOP),
            "unused.example" to SiteBrowsingSettings()
        )

        val encoded = SiteBrowsingSettingsJson.encode(original)
        val decoded = SiteBrowsingSettingsJson.decode(encoded)

        assertTrue(encoded.indexOf("first.example") < encoded.indexOf("second.example"))
        assertEquals(2, decoded.size)
        assertEquals(SiteBrowsingSettings(BrowsingIdentity.DESKTOP), decoded["first.example"])
        assertEquals(
            SiteBrowsingSettings(BrowsingIdentity.MOBILE_TV, BrowsingViewport.DESKTOP),
            decoded["second.example"]
        )
    }

    @Test fun decoderToleratesUnknownFieldsEnumsAndBadRecords() {
        val json = """
            {
              "version": 99,
              "future": {"nested": [true, null, 1.5]},
              "sites": [
                {"host":"GOOD.example","identity":"desktop","viewport":"future-value"},
                {"host":"https://bad.example/path","identity":"DESKTOP"},
                {"identity":"MOBILE_TV"},
                "not-an-object"
              ]
            }
        """.trimIndent()

        assertEquals(
            mapOf(
                "good.example" to SiteBrowsingSettings(
                    BrowsingIdentity.DESKTOP,
                    BrowsingViewport.DEFAULT
                )
            ),
            SiteBrowsingSettingsJson.decode(json)
        )
    }

    @Test fun recordsStoredBeforeTextSizeAndRemoteModeExistedLoadUnchanged() {
        val old = """{"version":1,"sites":[{"host":"old.example","identity":"DESKTOP","viewport":"MOBILE_TV"}]}"""

        val loaded = SiteBrowsingSettingsJson.decode(old)["old.example"]

        assertEquals(
            SiteBrowsingSettings(BrowsingIdentity.DESKTOP, BrowsingViewport.MOBILE_TV),
            loaded
        )
        assertEquals(SiteBrowsingSettings.DEFAULT_TEXT_ZOOM, loaded?.textZoom)
        assertEquals(SiteRemoteMode.GLOBAL, loaded?.remoteMode)
        assertTrue(SiteBrowsingSettings().isDefault)
        // Re-saving an untouched record writes nothing new.
        assertEquals(
            """{"version":1,"sites":[{"host":"old.example","identity":"DESKTOP","viewport":"MOBILE_TV"}]}""",
            SiteBrowsingSettingsJson.encode(mapOf("old.example" to loaded!!))
        )
    }

    @Test fun textSizeAndRemoteModeRoundTripAndKeepAnOtherwiseDefaultSiteStored() {
        val original = mapOf(
            "news.example" to SiteBrowsingSettings(textZoom = 130, remoteMode = SiteRemoteMode.DIRECT),
            "video.example" to SiteBrowsingSettings(remoteMode = SiteRemoteMode.CURSOR),
            "plain.example" to SiteBrowsingSettings(textZoom = SiteBrowsingSettings.DEFAULT_TEXT_ZOOM)
        )

        val decoded = SiteBrowsingSettingsJson.decode(SiteBrowsingSettingsJson.encode(original))

        assertEquals(setOf("news.example", "video.example"), decoded.keys)
        assertEquals(original["news.example"], decoded["news.example"])
        assertEquals(original["video.example"], decoded["video.example"])
        assertFalse(SiteBrowsingSettings(textZoom = 85).isDefault)
        assertFalse(SiteBrowsingSettings(remoteMode = SiteRemoteMode.CURSOR).isDefault)
    }

    @Test fun badTextSizeAndRemoteModeValuesFallBackToDefaults() {
        val json = """
            {"sites":[
              {"host":"a.example","identity":"DESKTOP","textZoom":9999,"remoteMode":"warp"},
              {"host":"b.example","identity":"DESKTOP","textZoom":"big"},
              {"host":"c.example","textZoom":0}
            ]}
        """.trimIndent()

        val decoded = SiteBrowsingSettingsJson.decode(json)

        assertEquals(SiteBrowsingSettings(BrowsingIdentity.DESKTOP), decoded["a.example"])
        assertEquals(SiteBrowsingSettings(BrowsingIdentity.DESKTOP), decoded["b.example"])
        assertNull(decoded["c.example"])
    }

    @Test fun siteRemoteModeOverridesTheGlobalToggleOnlyForThatHost() {
        val map = mapOf(
            "cursor.example" to SiteBrowsingSettings(remoteMode = SiteRemoteMode.CURSOR),
            "direct.example" to SiteBrowsingSettings(remoteMode = SiteRemoteMode.DIRECT),
            "zoom.example" to SiteBrowsingSettings(textZoom = 115)
        )
        // (host, global direct navigation, expected)
        listOf(
            Triple("cursor.example", true, false),
            Triple("direct.example", false, true),
            Triple("zoom.example", true, true),
            Triple("zoom.example", false, false),
            Triple("sub.cursor.example", true, true),
            Triple(null, false, false)
        ).forEach { (host, global, expected) ->
            assertEquals("$host global=$global", expected, SiteBrowsingPolicy.directNavForHost(map, host, global))
        }
        assertEquals(115, SiteBrowsingPolicy.textZoomForHost(map, "ZOOM.example."))
        assertEquals(100, SiteBrowsingPolicy.textZoomForHost(map, "cursor.example"))
    }

    @Test fun malformedJsonFailsClosedToNoOverrides() {
        assertEquals(emptyMap<String, SiteBrowsingSettings>(), SiteBrowsingSettingsJson.decode("{"))
        assertEquals(
            emptyMap<String, SiteBrowsingSettings>(),
            SiteBrowsingSettingsJson.decode("{\"sites\":[{\"host\":\"x\",}]}")
        )
    }
}
