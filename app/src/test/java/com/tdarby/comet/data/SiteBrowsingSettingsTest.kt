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

    @Test fun malformedJsonFailsClosedToNoOverrides() {
        assertEquals(emptyMap<String, SiteBrowsingSettings>(), SiteBrowsingSettingsJson.decode("{"))
        assertEquals(
            emptyMap<String, SiteBrowsingSettings>(),
            SiteBrowsingSettingsJson.decode("{\"sites\":[{\"host\":\"x\",}]}")
        )
    }
}
