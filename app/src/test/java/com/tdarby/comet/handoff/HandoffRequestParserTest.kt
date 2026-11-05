package com.tdarby.comet.handoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HandoffRequestParserTest {
    private fun parse(raw: String): ParseResult =
        HandoffRequestParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun status(raw: String) = (parse(raw) as? ParseResult.Rejected)?.status

    private fun post(length: Any?, body: String = "", extra: String = "") =
        "POST / HTTP/1.1\r\nHost: tv\r\n" + (if (length == null) "" else "Content-Length: $length\r\n") +
            extra + "\r\n" + body

    @Test
    fun readsARequestWithItsBody() {
        val ok = parse(post(9, "code=1234", "Content-Type: application/x-www-form-urlencoded\r\n")) as ParseResult.Ok
        assertEquals("POST", ok.request.method)
        assertEquals("/", ok.request.path)
        assertEquals("application/x-www-form-urlencoded", ok.request.headers["content-type"])
        assertArrayEquals("code=1234".toByteArray(), ok.request.body)
    }

    @Test
    fun dropsTheQueryFromThePath() {
        val ok = parse("GET /t/abc?x=1#y HTTP/1.1\r\nHost: tv\r\n\r\n") as ParseResult.Ok
        assertEquals("/t/abc", ok.request.path)
    }

    @Test
    fun refusesWhatBreaksTheLimitsOrTheFormat() {
        val cases = mapOf(
            "head over 8 KB" to (
                "GET / HTTP/1.1\r\nX-Pad: " + "a".repeat(HandoffRequestParser.MAX_HEAD_BYTES) + "\r\n\r\n" to 431),
            "body over 4 KB" to (post(HandoffRequestParser.MAX_BODY_BYTES + 1) to 413),
            "POST without a length" to (post(null) to 411),
            "length that is not a number" to (post("12abc") to 400),
            "negative length" to (post("-1") to 400),
            "body shorter than its length" to (post(20, "code=1") to 400),
            "chunked body" to (post(0, extra = "Transfer-Encoding: chunked\r\n") to 400),
            "two lengths" to (post(0, extra = "Content-Length: 0\r\n") to 400),
            "request line with two parts" to ("GET /\r\n\r\n" to 400),
            "unknown HTTP version" to ("GET / HTTP/2.0\r\n\r\n" to 400),
            "absolute-form target" to ("GET http://tv/ HTTP/1.1\r\n\r\n" to 400),
            "lower-case method" to ("get / HTTP/1.1\r\n\r\n" to 400),
            "header without a colon" to ("GET / HTTP/1.1\r\nHost tv\r\n\r\n" to 400),
            "folded header line" to ("GET / HTTP/1.1\r\nA: b\r\n c\r\n\r\n" to 400),
            "target over 2048 characters" to ("GET /" + "a".repeat(2100) + " HTTP/1.1\r\n\r\n" to 414),
            "closed halfway through the head" to ("GET / HTTP/1.1\r\nHost: tv\r\n" to 400)
        )
        cases.forEach { (name, case) -> assertEquals(name, case.second, status(case.first)) }
    }

    @Test
    fun aBodyOfExactlyTheLimitIsRead() {
        val body = "a".repeat(HandoffRequestParser.MAX_BODY_BYTES)
        val ok = parse(post(body.length, body)) as ParseResult.Ok
        assertEquals(body.length, ok.request.body.size)
    }

    @Test
    fun anEmptyConnectionIsNotARequest() {
        assertTrue(parse("") === ParseResult.NoRequest)
    }

    @Test
    fun formBodiesDecodePlusPercentAndUtf8() {
        val form = FormBody.parse("text=caf%C3%A9+au+lait%21&code=123456&empty=&flag".toByteArray())!!
        assertEquals("café au lait!", form["text"])
        assertEquals("123456", form["code"])
        assertEquals("", form["empty"])
        assertEquals("", form["flag"])
    }

    @Test
    fun malformedFormBodiesAreRefusedWhole() {
        val cases = listOf(
            "text=100%",            // cut-off escape
            "text=%4",              // cut-off escape
            "text=%zz",             // not hex
            "text=%FF%FE",          // not UTF-8
            "text=a&text=b",        // duplicate field
            (1..17).joinToString("&") { "f$it=1" } // too many fields
        )
        cases.forEach { assertNull(it, FormBody.parse(it.toByteArray())) }
    }
}
