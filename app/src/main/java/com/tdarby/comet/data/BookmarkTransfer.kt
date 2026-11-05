package com.tdarby.comet.data

/**
 * Pure logic for backing up and restoring bookmarks: build the versioned export file, validate an
 * imported one, and merge it into the saved list. Has its own tiny JSON reader so it runs in plain
 * JVM unit tests (the Android org.json classes are not available there).
 */
object BookmarkTransfer {
    const val FORMAT = "comet-bookmarks"
    const val VERSION = 1
    const val MAX_FILE_BYTES = 1_048_576
    const val MAX_BOOKMARKS = 1000
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_TITLE_LENGTH = 200

    enum class Failure { TOO_LARGE, NOT_A_BOOKMARK_FILE }

    sealed class Parsed {
        /** [skipped] counts entries dropped for being malformed or not http/https. */
        data class Ok(val items: List<SiteItem>, val skipped: Int) : Parsed()
        data class Rejected(val reason: Failure) : Parsed()
    }

    data class MergeResult(
        val merged: List<SiteItem>,
        val added: Int,
        val alreadySaved: Int,
        /** Entries not added because the list is full. */
        val overLimit: Int
    )

    fun export(bookmarks: List<SiteItem>, exportedIso: String): String {
        val sb = StringBuilder()
        sb.append("{\"format\":\"").append(FORMAT).append("\",\"version\":").append(VERSION)
        sb.append(",\"exported\":").append(quote(exportedIso)).append(",\"bookmarks\":[")
        bookmarks.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("{\"url\":").append(quote(b.url)).append(",\"title\":").append(quote(b.title)).append('}')
        }
        return sb.append("]}").toString()
    }

    fun parse(text: String, byteLength: Int = text.length): Parsed {
        if (byteLength > MAX_FILE_BYTES) return Parsed.Rejected(Failure.TOO_LARGE)
        val root = runCatching { JsonReader(text).readDocument() }.getOrNull() as? Map<*, *>
            ?: return Parsed.Rejected(Failure.NOT_A_BOOKMARK_FILE)
        val version = (root["version"] as? Double)?.toInt()
        val entries = root["bookmarks"] as? List<*>
        if (root["format"] != FORMAT || version == null || version < 1 || version > VERSION || entries == null) {
            return Parsed.Rejected(Failure.NOT_A_BOOKMARK_FILE)
        }
        var skipped = 0
        val items = mutableListOf<SiteItem>()
        for (entry in entries) {
            val o = entry as? Map<*, *>
            val url = (o?.get("url") as? String)?.trim()
            if (o == null || url == null || url.length > MAX_URL_LENGTH || !isWebUrl(url)) { skipped++; continue }
            val title = (o["title"] as? String)?.trim().orEmpty().take(MAX_TITLE_LENGTH)
            items.add(SiteItem(title.ifBlank { url }, url))
        }
        return Parsed.Ok(items, skipped)
    }

    /** Appends new bookmarks after the existing ones; existing entries (and their titles) never change. */
    fun merge(existing: List<SiteItem>, incoming: List<SiteItem>, cap: Int = MAX_BOOKMARKS): MergeResult {
        val merged = existing.toMutableList()
        val seen = existing.mapTo(HashSet()) { normalizeUrl(it.url) }
        var added = 0
        var already = 0
        var over = 0
        for (item in incoming) {
            if (!seen.add(normalizeUrl(item.url))) { already++; continue }
            if (merged.size >= cap) { over++; continue }
            merged.add(item)
            added++
        }
        return MergeResult(merged, added, already, over)
    }

    fun isWebUrl(url: String): Boolean {
        val m = URL_START.matchEntire(url) ?: return false
        return m.groupValues[2].isNotEmpty()
    }

    /** Scheme and host lower-cased, trailing slash dropped; the rest of the URL is compared as written. */
    fun normalizeUrl(url: String): String {
        val m = URL_START.matchEntire(url.trim()) ?: return url.trim().trimEnd('/')
        return (m.groupValues[1].lowercase() + "://" + m.groupValues[2].lowercase() + m.groupValues[3]).trimEnd('/')
    }

    private val URL_START = Regex("^(https?)://([^/?#]*)(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Minimal JSON reader: objects, arrays, strings, numbers (as Double), true/false/null. */
    private class JsonReader(private val s: String) {
        private var i = 0

        fun readDocument(): Any? {
            val v = readValue()
            skipWs()
            if (i != s.length) error("trailing data")
            return v
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun readValue(): Any? {
            skipWs()
            return when (val c = s.getOrNull(i) ?: error("eof")) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("bad char")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) error("bad literal")
            i += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s.getOrNull(i++) ?: error("unterminated string")
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s.getOrNull(i++) ?: error("bad escape")) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            if (i + 4 > s.length) error("bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        '"', '\\', '/' -> sb.append(e)
                        else -> error("bad escape")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipWs()
            if (s.getOrNull(i) == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("bad array")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s.getOrNull(i) == '}') { i++; return out }
            while (true) {
                skipWs()
                if (s.getOrNull(i) != '"') error("bad key")
                val key = readString()
                skipWs()
                if (s.getOrNull(i++) != ':') error("bad object")
                out[key] = readValue()
                skipWs()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("bad object")
                }
            }
        }
    }
}
