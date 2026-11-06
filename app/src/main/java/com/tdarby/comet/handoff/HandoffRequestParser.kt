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

    /**
     * [bodyLimit] gives the largest body this method and path may send. It is asked after the head is
     * read and before any body byte is, so a route that allows a big body never makes the others buffer one.
     */
    fun parse(
        source: InputStream,
        bodyLimit: (method: String, path: String) -> Int = { _, _ -> MAX_BODY_BYTES }
    ): ParseResult {
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
        if (length > bodyLimit(method, path)) return ParseResult.Rejected(413)
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

/**
 * A `multipart/form-data` body that holds exactly one part, named `file`. The body is scanned as
 * bytes; anything that is not exactly that shape is refused as a whole.
 */
object MultipartFile {
    sealed interface Result {
        class File(val content: ByteArray) : Result

        /** The one part is there but empty: no file was chosen. */
        object NoFile : Result

        object Invalid : Result
    }

    const val MAX_PART_HEAD_BYTES = 2 * 1024
    private val BOUNDARY = Regex("[0-9A-Za-z'()+_,\\-./:=? ]{1,70}")
    private val HEADER_NAME = Regex("[A-Za-z0-9-]+")
    private val HEAD_END = byteArrayOf(13, 10, 13, 10)

    fun parse(contentType: String?, body: ByteArray): Result {
        val boundary = boundaryOf(contentType) ?: return Result.Invalid
        val opening = "--$boundary\r\n".toByteArray(Charsets.ISO_8859_1)
        if (!startsWith(body, opening)) return Result.Invalid
        val headStart = opening.size
        // The head ends at the first blank line, at most MAX_PART_HEAD_BYTES in. No head at all is refused.
        val headEnd = indexOf(body, HEAD_END, headStart, headStart + MAX_PART_HEAD_BYTES)
        if (headEnd <= headStart) return Result.Invalid
        if (!isFileDisposition(String(body, headStart, headEnd - headStart, Charsets.ISO_8859_1))) {
            return Result.Invalid
        }
        val contentStart = headEnd + HEAD_END.size
        val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val contentEnd = indexOf(body, delimiter, contentStart, body.size)
        if (contentEnd < 0) return Result.Invalid
        // After the closing delimiter: "--", then an optional CRLF, then nothing.
        val after = contentEnd + delimiter.size
        val rest = body.size - after
        val closed = rest >= 2 && body[after] == DASH && body[after + 1] == DASH &&
            (rest == 2 || (rest == 4 && body[after + 2] == CR && body[after + 3] == LF))
        if (!closed) return Result.Invalid
        if (contentEnd == contentStart) return Result.NoFile
        return Result.File(body.copyOfRange(contentStart, contentEnd))
    }

    private const val CR: Byte = 13
    private const val LF: Byte = 10
    private const val DASH: Byte = 45 // '-'

    /** True when the part headers are a `form-data` disposition named `file` (plus, optionally, a content type). */
    private fun isFileDisposition(head: String): Boolean {
        var disposition: String? = null
        var sawType = false
        for (line in head.split("\r\n")) {
            if (line.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
            val colon = line.indexOf(':')
            if (colon <= 0) return false
            val name = line.substring(0, colon)
            if (!HEADER_NAME.matches(name)) return false
            when (name.lowercase()) {
                "content-disposition" -> {
                    if (disposition != null) return false
                    disposition = line.substring(colon + 1)
                }
                "content-type" -> {
                    if (sawType) return false
                    sawType = true
                }
                else -> return false
            }
        }
        val segments = splitUnquoted(disposition ?: return false) ?: return false
        if (segments[0].trim().lowercase() != "form-data") return false
        var name: String? = null
        for (param in params(segments) ?: return false) {
            if (param.first == "name") {
                if (name != null) return false
                name = param.second
            }
        }
        return name == "file"
    }

    private fun boundaryOf(contentType: String?): String? {
        val segments = splitUnquoted(contentType ?: return null) ?: return null
        if (segments[0].trim().lowercase() != "multipart/form-data") return null
        var boundary: String? = null
        var bareValue = false
        for (segment in segments.drop(1)) {
            val eq = segment.indexOf('=')
            if (eq < 0 || segment.substring(0, eq).trim().lowercase() != "boundary") continue
            if (boundary != null) return null
            val raw = segment.substring(eq + 1).trim()
            bareValue = !raw.startsWith('"')
            boundary = unquote(raw) ?: return null
        }
        val value = boundary ?: return null
        if (!BOUNDARY.matches(value) || value.endsWith(' ') || (bareValue && value.contains(' '))) return null
        return value
    }

    /** `name=value` pairs after the first segment, names lower-cased, quotes removed; null when malformed. */
    private fun params(segments: List<String>): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        for (segment in segments.drop(1)) {
            if (segment.isBlank()) continue
            val eq = segment.indexOf('=')
            if (eq <= 0) return null
            out.add(segment.substring(0, eq).trim().lowercase() to (unquote(segment.substring(eq + 1).trim()) ?: return null))
        }
        return out
    }

    private fun unquote(raw: String): String? {
        if (!raw.startsWith('"')) return raw
        if (raw.length < 2 || !raw.endsWith('"')) return null
        val inner = raw.substring(1, raw.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) { sb.append(inner[i + 1]); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Splits on ';' outside double quotes; null when a quote is left open. */
    private fun splitUnquoted(text: String): List<String>? {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuote && c == '\\' && i + 1 < text.length -> { current.append(c).append(text[i + 1]); i++ }
                c == '"' -> { inQuote = !inQuote; current.append(c) }
                c == ';' && !inQuote -> { out.add(current.toString()); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        if (inQuote) return null
        out.add(current.toString())
        return out
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    /** The first index in [from, lastStart] where [pattern] starts, or -1. */
    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int, lastStart: Int): Int {
        val last = minOf(lastStart, data.size - pattern.size)
        var i = from
        while (i <= last) {
            if (data[i] == pattern[0]) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
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
