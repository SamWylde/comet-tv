package com.tdarby.comet.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class HandoffRouterTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private var now = 0L
    private val session = HandoffSession(token, "654321", lifetimeMs = 1000, clock = { now })
    private val sent = mutableListOf<String>()
    private var outcome = SendOutcome.ACCEPTED
    private val router = HandoffRouter(session) { text -> sent += text; outcome }

    private val form = mapOf("content-type" to "application/x-www-form-urlencoded")

    private fun get(path: String) = router.handle(HandoffRequest("GET", path, emptyMap(), ByteArray(0)))

    private fun post(path: String, body: String, headers: Map<String, String> = form) =
        router.handle(HandoffRequest("POST", path, headers, body.toByteArray()))

    private fun text(r: HandoffResponse) = String(r.body, Charsets.UTF_8)

    @Test
    fun typingTheAddressAsksForTheCodeThenLeadsToTheSendForm() {
        assertTrue(text(get("/")).contains("name=\"code\""))

        val ok = post("/", "code=654321")
        assertEquals(303, ok.status)
        assertEquals("/t/$token", ok.headers["Location"])

        assertTrue(text(get("/t/$token")).contains("name=\"text\""))
    }

    @Test
    fun scanningTheQrCodeShowsTheSendFormAtOnce() {
        val page = get("/t/$token")
        assertEquals(200, page.status)
        assertTrue(text(page).contains("action=\"/t/$token\""))
    }

    @Test
    fun fiveWrongCodesLockEverythingOut() {
        repeat(4) { assertEquals(403, post("/", "code=000000").status) }
        assertEquals(423, post("/", "code=000000").status)
        assertEquals(423, post("/", "code=654321").status)
        assertEquals(423, get("/t/$token").status)
        assertEquals(423, post("/t/$token", "text=cats").status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aWrongOrPartialTokenIsNotFoundAndSendsNothing() {
        assertEquals(404, get("/t/${token.dropLast(1)}0").status)
        assertEquals(404, post("/t/${token.dropLast(1)}0", "text=cats").status)
        assertEquals(404, get("/t/").status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aSubmittedLinkReachesTheTvOnlyWhenItPassesTheInputRules() {
        val ok = post("/t/$token", "text=https%3A%2F%2Fexample.com%2F%3Fa%3D1")
        assertEquals(200, ok.status)
        assertEquals(listOf("https://example.com/?a=1"), sent)

        assertEquals(400, post("/t/$token", "text=file%3A%2F%2F%2Fsdcard").status)
        assertEquals(400, post("/t/$token", "text=").status)
        assertEquals(400, post("/t/$token", "text=%ZZ").status)
        assertEquals(400, post("/t/$token", "other=1").status)
        assertEquals(415, post("/t/$token", "text=cats", mapOf("content-type" to "text/plain")).status)
        assertEquals(415, post("/t/$token", "text=cats", emptyMap()).status)
        assertEquals(1, sent.size)
    }

    @Test
    fun theTvCanRefuseWhileItIsBusyOrClosed() {
        outcome = SendOutcome.BUSY
        assertEquals(429, post("/t/$token", "text=cats").status)
        outcome = SendOutcome.CLOSED
        assertEquals(410, post("/t/$token", "text=cats").status)
    }

    @Test
    fun anExpiredSessionAnswersGoneEverywhere() {
        now = 1000
        assertEquals(410, get("/").status)
        assertEquals(410, get("/t/$token").status)
        assertEquals(410, post("/t/$token", "text=cats").status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun otherPathsAndMethodsAreRefused() {
        assertEquals(404, get("/favicon.ico").status)
        assertEquals(404, get("/t").status)
        assertEquals(404, get("/admin").status)
        for (method in listOf("PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")) {
            val r = router.handle(HandoffRequest(method, "/", emptyMap(), ByteArray(0)))
            assertEquals(method, 405, r.status)
            assertEquals("GET, POST", r.headers["Allow"])
            assertEquals(method, 405, router.handle(HandoffRequest(method, "/t/$token", emptyMap(), ByteArray(0))).status)
        }
    }

    @Test
    fun whatIsEchoedBackIsEscaped() {
        val page = text(post("/t/$token", "text=%3Cscript%3Ealert(%22x%22)%3C%2Fscript%3E+%26+%27"))
        assertFalse(page.contains("<script>"))
        assertTrue(page.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;"))
    }

    @Test
    fun everyResponseIsUncachedCookielessAndUnderAStrictPolicy() {
        val responses = listOf(
            get("/"), get("/t/$token"), get("/nope"), post("/", "code=000000"),
            post("/t/$token", "text=cats"), HandoffResponse.error(431)
        )
        for (r in responses) {
            val head = String(r.toBytes(), Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            assertTrue(lines.contains("Cache-Control: no-store"))
            assertTrue(lines.contains("Connection: close"))
            assertTrue(lines.contains("Content-Length: ${r.body.size}"))
            assertTrue(lines.any { it.startsWith("Content-Security-Policy: default-src 'none';") })
            assertNull(lines.firstOrNull { it.startsWith("Set-Cookie", ignoreCase = true) })
        }
    }

    @Test
    fun theStylePolicyHashMatchesTheStyleBlockAndNothingIsInline() {
        val html = text(get("/"))
        val style = Regex("<style>(.*?)</style>").find(html)!!.groupValues[1]
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(style.toByteArray()))
        assertTrue(HandoffPages.contentSecurityPolicy.contains("style-src 'sha256-$hash'"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains(" style="))
    }
}
