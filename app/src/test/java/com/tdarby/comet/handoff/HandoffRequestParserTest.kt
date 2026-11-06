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
    fun theBodyLimitIsDecidedPerRouteBeforeTheBodyIsRead() {
        val size = HandoffRequestParser.MAX_BODY_BYTES + 1000
        val body = "a".repeat(size)
        val upload = { method: String, path: String -> if (method == "POST" && path == "/up") size else HandoffRequestParser.MAX_BODY_BYTES }
        fun run(path: String): ParseResult = HandoffRequestParser.parse(
            ByteArrayInputStream("POST $path HTTP/1.1\r\nContent-Length: $size\r\n\r\n$body".toByteArray()), upload
        )

        assertEquals(size, (run("/up") as ParseResult.Ok).request.body.size)
        assertEquals(413, (run("/other") as ParseResult.Rejected).status)
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
    fun aMultipartBodyWithOneFilePartGivesTheFile() {
        val ok = MultipartFile.parse(MULTIPART_TYPE, multipartFile("{\"a\":1}\r\n--Xy"))
        assertArrayEquals("{\"a\":1}\r\n--Xy".toByteArray(), (ok as MultipartFile.Result.File).content)
        // A quoted boundary, no trailing CRLF and a filename with a semicolon all work.
        val quoted = MultipartFile.parse(
            "multipart/form-data; boundary=\"XyZ\"",
            multipartFile(tail = "", disposition = "form-data; filename=\"a;b.json\"; name=\"file\"")
        )
        assertArrayEquals("{}".toByteArray(), (quoted as MultipartFile.Result.File).content)
    }

    @Test
    fun aMultipartBodyThatIsNotExactlyOneFilePartIsRefusedWhole() {
        val good = MULTIPART_TYPE
        val cases = mapOf(
            "no content type" to (null to multipartFile()),
            "not multipart" to ("application/json" to multipartFile()),
            "no boundary" to ("multipart/form-data" to multipartFile()),
            "other boundary" to ("multipart/form-data; boundary=Other" to multipartFile()),
            "boundary with a trailing space" to ("multipart/form-data; boundary=\"XyZ \"" to multipartFile(boundary = "XyZ ")),
            "boundary over 70 characters" to ("multipart/form-data; boundary=${"a".repeat(71)}" to multipartFile(boundary = "a".repeat(71))),
            "two boundaries" to ("multipart/form-data; boundary=XyZ; boundary=XyZ" to multipartFile()),
            "two parts" to (good to multipartFile(closing = "\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n{}\r\n--XyZ--")),
            "no closing boundary" to (good to multipartFile(closing = "")),
            "bytes after the closing boundary" to (good to multipartFile(tail = "\r\nmore")),
            "part not named file" to (good to multipartFile(disposition = "form-data; name=\"other\"")),
            "part with no name" to (good to multipartFile(disposition = "form-data")),
            "not form-data" to (good to multipartFile(disposition = "attachment; name=\"file\"")),
            "text before the first boundary" to (good to "junk".toByteArray() + multipartFile()),
            "part headers over 2 KB" to (good to multipartFile(disposition = "form-data; name=\"file\"; x=\"${"a".repeat(2100)}\""))
        )
        cases.forEach { (name, case) ->
            assertTrue(name, MultipartFile.parse(case.first, case.second) === MultipartFile.Result.Invalid)
        }
    }

    @Test
    fun anEmptyFilePartMeansNoFileWasChosen() {
        assertTrue(MultipartFile.parse(MULTIPART_TYPE, multipartFile(content = "")) === MultipartFile.Result.NoFile)
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

/** A `multipart/form-data` body with one part; every piece can be bent to make a malformed one. */
internal fun multipartFile(
    content: String = "{}",
    boundary: String = "XyZ",
    disposition: String = "form-data; name=\"file\"; filename=\"b.json\"",
    closing: String = "--",
    tail: String = "\r\n"
): ByteArray = (
    "--$boundary\r\nContent-Disposition: $disposition\r\nContent-Type: application/json\r\n\r\n" +
        "$content\r\n--$boundary$closing$tail"
    ).toByteArray(Charsets.UTF_8)

internal const val MULTIPART_TYPE = "multipart/form-data; boundary=XyZ"
