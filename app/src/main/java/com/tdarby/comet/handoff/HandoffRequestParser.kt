package com.tdarby.comet.handoff

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class HandoffRequest(
    val method: String,
    /** The path only; any query string or fragment is dropped. */
    val path: String,
    /** Header names lower-cased. */
    val headers: Map<String, String>,
    val body: ByteArray
)

sealed interface ParseResult {
    class Ok(val request: HandoffRequest) : ParseResult

    /** The request is refused with this HTTP status. */
    class Rejected(val status: Int) : ParseResult

    /** The peer closed the connection without sending anything. */
    object NoRequest : ParseResult
}

/** A strict, size-limited HTTP/1.x request reader. Socket timeouts surface as IOExceptions. */
object HandoffRequestParser {
    const val MAX_HEAD_BYTES = 8 * 1024
    const val MAX_BODY_BYTES = 4 * 1024
    private const val MAX_TARGET_CHARS = 2048
    private const val CRLF_CRLF = 0x0D0A0D0A
    private val CONTENT_LENGTH = Regex("\\d{1,9}")
    private val METHOD = Regex("[A-Z]{1,16}")
    private val HEADER_NAME = Regex("[A-Za-z0-9-]+")

    fun parse(source: InputStream): ParseResult {
        val input = BufferedInputStream(source, 1024)
        val head = ByteArrayOutputStream()
        var tail = 0 // the last four bytes read
        while (true) {
            val b = input.read()
            if (b < 0) return if (head.size() == 0) ParseResult.NoRequest else ParseResult.Rejected(400)
            head.write(b)
            if (head.size() > MAX_HEAD_BYTES) return ParseResult.Rejected(431)
            tail = (tail shl 8) or b
            if (tail == CRLF_CRLF) break
        }
        // Not head.toString(Charset): that overload needs API 33 and crashes older TVs.
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).dropLast(4).split("\r\n")
        if (lines.any { it.contains('\r') || it.contains('\n') || it.contains('\u0000') }) {
            return ParseResult.Rejected(400)
        }

        val requestLine = lines[0].split(' ')
        if (requestLine.size != 3) return ParseResult.Rejected(400)
        val (method, target, version) = requestLine
        if (!METHOD.matches(method) || (version != "HTTP/1.1" && version != "HTTP/1.0")) {
            return ParseResult.Rejected(400)
        }
        if (target.length > MAX_TARGET_CHARS) return ParseResult.Rejected(414)
        if (!target.startsWith("/")) return ParseResult.Rejected(400)
        val path = target.substringBefore('?').substringBefore('#')

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return ParseResult.Rejected(400) // also covers folded lines
            val name = line.substring(0, colon)
            if (!HEADER_NAME.matches(name)) return ParseResult.Rejected(400)
            if (headers.put(name.lowercase(), line.substring(colon + 1).trim()) != null) {
                return ParseResult.Rejected(400)
            }
        }
        if (headers.containsKey("transfer-encoding")) return ParseResult.Rejected(400)

        val lengthText = headers["content-length"]
        if (lengthText == null) {
            if (method == "POST") return ParseResult.Rejected(411)
            return ParseResult.Ok(HandoffRequest(method, path, headers, ByteArray(0)))
        }
        if (!CONTENT_LENGTH.matches(lengthText)) return ParseResult.Rejected(400)
        val length = lengthText.toInt()
        if (length > MAX_BODY_BYTES) return ParseResult.Rejected(413)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return ParseResult.Rejected(400)
            read += n
        }
        return ParseResult.Ok(HandoffRequest(method, path, headers, body))
    }
}

/** `application/x-www-form-urlencoded` decoding. Anything malformed is refused as a whole. */
object FormBody {
    private const val MAX_FIELDS = 16

    fun parse(body: ByteArray): Map<String, String>? {
        val fields = LinkedHashMap<String, String>()
        var start = 0
        while (start <= body.size) {
            var end = start
            while (end < body.size && body[end] != '&'.code.toByte()) end++
            if (end > start) {
                var eq = start
                while (eq < end && body[eq] != '='.code.toByte()) eq++
                val key = decode(body, start, eq) ?: return null
                val value = if (eq < end) decode(body, eq + 1, end) ?: return null else ""
                if (fields.size >= MAX_FIELDS || fields.put(key, value) != null) return null
            }
            start = end + 1
        }
        return fields
    }

    private fun decode(src: ByteArray, from: Int, to: Int): String? {
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val b = src[i].toInt() and 0xFF
            when (b) {
                '+'.code -> out.write(' '.code)
                '%'.code -> {
                    if (i + 2 >= to) return null
                    val hi = Character.digit(src[i + 1].toInt().toChar(), 16)
                    val lo = Character.digit(src[i + 2].toInt().toChar(), 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 2
                }
                else -> out.write(b)
            }
            i++
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
