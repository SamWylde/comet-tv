package com.tdarby.comet.engine

/** The User-Agent Client Hint fields that must change with the legacy User-Agent string. */
internal data class UserAgentMetadataOverride(
    val platform: String,
    val platformVersion: String,
    val architecture: String,
    val model: String,
    val mobile: Boolean,
    val bitness: Int,
    val wow64: Boolean
)

/** Keeps desktop-mode browser identity internally consistent without pinning a Chromium version. */
internal object UserAgentPolicy {
    private val PLATFORM_SECTION = Regex("^Mozilla/5\\.0\\s+\\([^)]*\\)", RegexOption.IGNORE_CASE)
    private val VERSION_TOKEN = Regex("\\s+Version/[^\\s]+", RegexOption.IGNORE_CASE)
    private val MOBILE_TOKEN = Regex("\\s+Mobile(?:/[^\\s]+)?(?=\\s|$)", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    /**
     * Converts the installed WebView's actual UA to a Linux desktop UA while preserving its
     * AppleWebKit, Chromium, and Safari versions. Atypical UAs are returned unchanged rather than
     * inventing a stale browser version.
     */
    fun userAgent(defaultUserAgent: String, desktopMode: Boolean): String? {
        if (!desktopMode) return null
        if (!PLATFORM_SECTION.containsMatchIn(defaultUserAgent)) return defaultUserAgent
        return defaultUserAgent
            .replaceFirst(PLATFORM_SECTION, "Mozilla/5.0 (X11; Linux x86_64)")
            .replace(VERSION_TOKEN, "")
            .replace(MOBILE_TOKEN, "")
            .replace(WHITESPACE, " ")
            .trim()
    }

    /** Desktop mode presents as Chrome, so the low-entropy brand must not still say WebView. */
    fun brand(defaultBrand: String, desktopMode: Boolean): String =
        if (desktopMode && defaultBrand.equals("Android WebView", ignoreCase = true)) {
            "Google Chrome"
        } else {
            defaultBrand
        }

    /** Null means restore the WebView provider's original metadata. */
    fun metadataOverride(defaultUserAgent: String, desktopMode: Boolean): UserAgentMetadataOverride? =
        if (desktopMode && PLATFORM_SECTION.containsMatchIn(defaultUserAgent)) {
            UserAgentMetadataOverride(
                platform = "Linux",
                platformVersion = "",
                architecture = "x86",
                model = "",
                mobile = false,
                bitness = 64,
                wow64 = false
            )
        } else {
            null
        }
}
