package com.tdarby.comet.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val camera = SitePermissionResource.CAMERA.webViewResource!!
    private val microphone = SitePermissionResource.MICROPHONE.webViewResource!!

    @Test fun unknownResourcesAreDeniedAndKnownResourcesRequireAChoice() {
        val policy = PermissionPolicy()

        val result = policy.resolve(
            "https://Meet.Example/room",
            listOf(camera, "android.webkit.resource.MIDI_SYSEX")
        )

        assertEquals(setOf(PermissionKey("https://meet.example", SitePermissionResource.CAMERA)), result.pendingDecisions)
        assertEquals(setOf("android.webkit.resource.MIDI_SYSEX"), result.deniedWebViewResources)
        assertTrue(result.grantedWebViewResources.isEmpty())
    }

    @Test fun allowOnceAppliesOnlyToTheCurrentResolution() {
        val policy = PermissionPolicy()
        val key = policy.key("https://meet.example", SitePermissionResource.CAMERA)!!

        val allowed = policy.resolve("https://meet.example", listOf(camera), mapOf(key to PermissionDecision.ALLOW_ONCE))
        val nextRequest = policy.resolve("https://meet.example", listOf(camera))

        assertEquals(setOf(camera), allowed.grantedWebViewResources)
        assertEquals(setOf(key), nextRequest.pendingDecisions)
    }

    @Test fun sessionGrantIsScopedToCanonicalOriginAndResource() {
        val policy = PermissionPolicy()
        val cameraKey = policy.key("HTTPS://Meet.Example:443/path", SitePermissionResource.CAMERA)!!
        policy.resolve("https://meet.example", listOf(camera), mapOf(cameraKey to PermissionDecision.ALLOW_SESSION))

        assertEquals(setOf(camera), policy.resolve("https://MEET.example/other", listOf(camera)).grantedWebViewResources)
        assertTrue(policy.resolve("https://meet.example", listOf(microphone)).hasPendingDecisions)
        assertTrue(policy.resolve("https://other.example", listOf(camera)).hasPendingDecisions)
    }

    @Test fun denyRevokesAnExistingSessionGrant() {
        val policy = PermissionPolicy()
        val key = policy.key("https://meet.example", SitePermissionResource.CAMERA)!!
        policy.resolve("https://meet.example", listOf(camera), mapOf(key to PermissionDecision.ALLOW_SESSION))

        val denied = policy.resolve("https://meet.example", listOf(camera), mapOf(key to PermissionDecision.DENY))

        assertEquals(setOf(camera), denied.deniedWebViewResources)
        assertTrue(policy.resolve("https://meet.example", listOf(camera)).hasPendingDecisions)
    }

    @Test fun clearingTheSessionRemovesEveryRememberedGrant() {
        val policy = PermissionPolicy()
        val cameraKey = policy.key("https://meet.example", SitePermissionResource.CAMERA)!!
        val microphoneKey = policy.key("https://meet.example", SitePermissionResource.MICROPHONE)!!
        policy.resolve(
            "https://meet.example",
            listOf(camera, microphone),
            mapOf(cameraKey to PermissionDecision.ALLOW_SESSION, microphoneKey to PermissionDecision.ALLOW_SESSION)
        )

        policy.clearSessionGrants()

        val result = policy.resolve("https://meet.example", listOf(camera, microphone))
        assertEquals(setOf(cameraKey, microphoneKey), result.pendingDecisions)
        assertFalse(result.grantedWebViewResources.isNotEmpty())
    }

    @Test fun unsafeOrMalformedOriginsCanNeverReceiveCaptureAccess() {
        val policy = PermissionPolicy()

        for (origin in listOf("file:///sdcard/page.html", "javascript:alert(1)", "not a url", "https://user@example.com")) {
            val result = policy.resolve(origin, listOf(camera))
            assertEquals(origin, setOf(camera), result.deniedWebViewResources)
            assertTrue(origin, result.pendingDecisions.isEmpty())
        }
    }

    @Test fun locationUsesTheSameOriginScopedOnceAndSessionChoices() {
        val policy = PermissionPolicy()
        val location = policy.key("https://maps.example/page", SitePermissionResource.LOCATION)!!
        val otherOrigin = policy.key("https://other.example", SitePermissionResource.LOCATION)!!

        assertTrue(policy.applyDecision(location, PermissionDecision.ALLOW_ONCE))
        assertFalse(policy.isGrantedForSession(location))
        assertTrue(policy.applyDecision(location, PermissionDecision.ALLOW_SESSION))
        assertTrue(policy.isGrantedForSession(location))
        assertFalse(policy.isGrantedForSession(otherOrigin))
        assertFalse(policy.applyDecision(location, PermissionDecision.DENY))
        assertFalse(policy.isGrantedForSession(location))
    }

    @Test fun directlyConstructedNonCanonicalKeyCannotBeGranted() {
        val policy = PermissionPolicy()
        val unsafe = PermissionKey("HTTPS://EXAMPLE.COM/path", SitePermissionResource.LOCATION)

        assertFalse(policy.applyDecision(unsafe, PermissionDecision.ALLOW_SESSION))
        assertFalse(policy.isGrantedForSession(unsafe))
    }
}
