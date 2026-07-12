package com.tdarby.comet.data

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Browser identity advertised to a site. DEFAULT follows the existing global desktop toggle. */
enum class BrowsingIdentity {
    DEFAULT,
    MOBILE_TV,
    DESKTOP
}

/** Layout viewport policy, independently overridable for sites with unusual responsive layouts. */
enum class BrowsingViewport {
    DEFAULT,
    MOBILE_TV,
    DESKTOP
}

/** Exact-host browsing overrides. An all-default value is equivalent to no stored override. */
data class SiteBrowsingSettings(
    val identity: BrowsingIdentity = BrowsingIdentity.DEFAULT,
    val viewport: BrowsingViewport = BrowsingViewport.DEFAULT
) {
    val isDefault: Boolean
        get() = identity == BrowsingIdentity.DEFAULT && viewport == BrowsingViewport.DEFAULT
}

/** Fully resolved, non-default policy that can be applied directly to a browser engine. */
data class ResolvedSiteBrowsingSettings(
    val identity: BrowsingIdentity,
    val viewport: BrowsingViewport
) {
    init {
        require(identity != BrowsingIdentity.DEFAULT)
        require(viewport != BrowsingViewport.DEFAULT)
    }

    val desktopMode: Boolean get() = identity == BrowsingIdentity.DESKTOP
    val useWideViewPort: Boolean get() = viewport == BrowsingViewport.DESKTOP
    val loadWithOverviewMode: Boolean get() = viewport == BrowsingViewport.DESKTOP
}

/** Pure host lookup and fallback rules shared by settings UI and browser engines. */
object SiteBrowsingPolicy {
    fun normalizeHost(rawHost: String?): String? {
        var host = rawHost?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1, host.length - 1)
        if (host.any { it.isWhitespace() || it == '/' || it == '\\' || it == '?' || it == '#' }) {
            return null
        }

        // URI.host returns IPv6 literals without brackets. Keep those literals as-is rather than
        // passing them through IDN, which only accepts DNS names.
        if (':' in host) {
            val normalized = host.lowercase(Locale.ROOT)
            val parsed = runCatching { URI("https://[$normalized]/").host }
                .getOrNull()
                ?.removePrefix("[")
                ?.removeSuffix("]")
            return normalized.takeIf {
                it.length <= MAX_HOST_LENGTH && parsed.equals(normalized, ignoreCase = true)
            }
        }

        return runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrNull()?.takeIf { ascii ->
            ascii.length <= MAX_HOST_LENGTH &&
                ascii.split('.').all { label -> label.isNotEmpty() && label.length <= MAX_LABEL_LENGTH }
        }
    }

    fun settingsForHost(
        settingsByHost: Map<String, SiteBrowsingSettings>,
        host: String?
    ): SiteBrowsingSettings? = normalizeHost(host)?.let(settingsByHost::get)

    fun resolve(
        siteSettings: SiteBrowsingSettings?,
        globalDesktopMode: Boolean
    ): ResolvedSiteBrowsingSettings {
        val fallbackIdentity =
            if (globalDesktopMode) BrowsingIdentity.DESKTOP else BrowsingIdentity.MOBILE_TV
        val identity = siteSettings?.identity
            ?.takeUnless { it == BrowsingIdentity.DEFAULT }
            ?: fallbackIdentity
        val viewport = when (siteSettings?.viewport ?: BrowsingViewport.DEFAULT) {
            BrowsingViewport.MOBILE_TV -> BrowsingViewport.MOBILE_TV
            BrowsingViewport.DESKTOP -> BrowsingViewport.DESKTOP
            BrowsingViewport.DEFAULT -> when (identity) {
                BrowsingIdentity.DESKTOP -> BrowsingViewport.DESKTOP
                BrowsingIdentity.DEFAULT,
                BrowsingIdentity.MOBILE_TV -> BrowsingViewport.MOBILE_TV
            }
        }
        return ResolvedSiteBrowsingSettings(identity, viewport)
    }

    fun resolveForHost(
        settingsByHost: Map<String, SiteBrowsingSettings>,
        host: String?,
        globalDesktopMode: Boolean
    ): ResolvedSiteBrowsingSettings =
        resolve(settingsForHost(settingsByHost, host), globalDesktopMode)

    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
}

/**
 * Versioned JSON persistence. This deliberately has no Android dependency so malformed-data and
 * round-trip behavior remain covered by fast local unit tests.
 */
internal object SiteBrowsingSettingsJson {
    fun encode(settingsByHost: Map<String, SiteBrowsingSettings>): String {
        val normalized = linkedMapOf<String, SiteBrowsingSettings>()
        settingsByHost.forEach { (rawHost, settings) ->
            SiteBrowsingPolicy.normalizeHost(rawHost)?.let { host ->
                if (!settings.isDefault) normalized[host] = settings
            }
        }
        return buildString {
            append("{\"version\":1,\"sites\":[")
            normalized.toSortedMap().entries.forEachIndexed { index, (host, settings) ->
                if (index > 0) append(',')
                append("{\"host\":")
                appendJsonString(host)
                append(",\"identity\":")
                appendJsonString(settings.identity.name)
                append(",\"viewport\":")
                appendJsonString(settings.viewport.name)
                append('}')
            }
            append("]}")
        }
    }

    fun decode(json: String?): Map<String, SiteBrowsingSettings> {
        if (json.isNullOrBlank() || json.length > MAX_JSON_LENGTH) return emptyMap()
        val root = runCatching { JsonReader(json).readDocument() as? Map<*, *> }.getOrNull()
            ?: return emptyMap()
        val sites = root["sites"] as? List<*> ?: return emptyMap()
        val result = linkedMapOf<String, SiteBrowsingSettings>()
        sites.take(MAX_SITES).forEach { value ->
            val item = value as? Map<*, *> ?: return@forEach
            val host = SiteBrowsingPolicy.normalizeHost(item["host"] as? String) ?: return@forEach
            val identity = enumValueOrDefault<BrowsingIdentity>(item["identity"] as? String)
            val viewport = enumValueOrDefault<BrowsingViewport>(item["viewport"] as? String)
            val settings = SiteBrowsingSettings(identity, viewport)
            if (!settings.isDefault) result[host] = settings
        }
        return result
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: enumValues<T>().first { it.name == "DEFAULT" }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** Small strict JSON reader sufficient for versioned preferences, including unknown fields. */
    private class JsonReader(private val source: String) {
        private var index = 0

        fun readDocument(): Any? {
            val value = readValue(depth = 0)
            skipWhitespace()
            require(index == source.length) { "Trailing JSON content" }
            return value
        }

        private fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON nesting is too deep" }
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> error("Unexpected JSON token")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val values = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (consume('}')) return values
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                values[key] = readValue(depth)
                skipWhitespace()
                if (consume('}')) return values
                expect(',')
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val values = mutableListOf<Any?>()
            skipWhitespace()
            if (consume(']')) return values
            while (true) {
                values += readValue(depth)
                skipWhitespace()
                if (consume(']')) return values
                expect(',')
            }
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val ch = source[index++]
                when {
                    ch == '"' -> return result.toString()
                    ch == '\\' -> {
                        require(index < source.length) { "Unterminated JSON escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(readUnicodeEscape())
                            else -> error("Invalid JSON escape")
                        }
                    }
                    ch < ' ' -> error("Unescaped control character")
                    else -> result.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun readUnicodeEscape(): Char {
            require(index + 4 <= source.length) { "Incomplete Unicode escape" }
            val digits = source.substring(index, index + 4)
            index += 4
            return digits.toIntOrNull(16)?.toChar() ?: error("Invalid Unicode escape")
        }

        private fun readNumber(): Number {
            val start = index
            if (source[index] == '-') index++
            require(index < source.length) { "Incomplete JSON number" }
            if (source[index] == '0') {
                index++
            } else {
                require(source[index] in '1'..'9') { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && source[index] == '.') {
                index++
                require(index < source.length && source[index].isDigit()) { "Invalid fraction" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && source[index].lowercaseChar() == 'e') {
                index++
                if (index < source.length && source[index] in "+-") index++
                require(index < source.length && source[index].isDigit()) { "Invalid exponent" }
                while (index < source.length && source[index].isDigit()) index++
            }
            val token = source.substring(start, index)
            return token.toLongOrNull() ?: token.toDoubleOrNull() ?: error("Invalid JSON number")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(source.startsWith(literal, index)) { "Invalid JSON literal" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in " \t\r\n") index++
        }

        private fun consume(expected: Char): Boolean {
            if (index < source.length && source[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            require(consume(expected)) { "Expected '$expected'" }
        }
    }

    private const val MAX_JSON_LENGTH = 1_000_000
    private const val MAX_SITES = 2_048
    private const val MAX_DEPTH = 32
}
