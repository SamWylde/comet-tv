package com.tdarby.comet.support

import java.net.URI

/** A browser brand/version pair reported through User-Agent Client Hints. */
data class UserAgentBrand(
    val brand: String,
    val version: String
)

/** UA-CH values relevant when diagnosing server-side mobile/desktop routing. */
data class UserAgentClientHints(
    val brands: List<UserAgentBrand> = emptyList(),
    val platform: String? = null,
    val platformVersion: String? = null,
    val architecture: String? = null,
    val model: String? = null,
    val mobile: Boolean? = null,
    val bitness: Int? = null,
    val wow64: Boolean? = null
)

/** Viewport behavior that can affect which player or layout a site serves. */
data class SupportViewport(
    val mode: String,
    val widthCssPixels: Int? = null,
    val useWideViewPort: Boolean? = null,
    val loadWithOverviewMode: Boolean? = null
)

/** Facts about this device's process and memory, separate from the browser identity values. */
data class SupportDevice(
    val is64BitProcess: Boolean? = null,
    val supportedAbis: List<String> = emptyList(),
    val memoryClassMb: Int? = null,
    val largeMemoryClassMb: Int? = null,
    val lowRamDevice: Boolean? = null,
    val totalRamMb: Long? = null
)

/** Runtime facts collected by the Android/UI integration layer. */
data class SupportReportInput(
    val cometVersionName: String?,
    val cometVersionCode: Long?,
    val webViewPackage: String?,
    val webViewVersion: String?,
    val androidVersion: String?,
    val androidSdk: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val activeIdentity: String?,
    val viewport: SupportViewport?,
    val userAgent: String?,
    val clientHints: UserAgentClientHints?,
    val fullscreenEnabled: Boolean?,
    val currentUrl: String?,
    val device: SupportDevice? = null
)

/** Copy-safe diagnostic data. The current page is deliberately reduced to its host. */
data class SupportReport(
    val cometVersion: String,
    val webViewPackage: String,
    val webViewVersion: String,
    val androidVersion: String,
    val androidSdk: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val activeIdentity: String,
    val viewport: SupportViewport?,
    val userAgent: String,
    val clientHints: UserAgentClientHints?,
    val fullscreenEnabled: Boolean?,
    val currentHost: String,
    val device: SupportDevice? = null
) {
    fun toCopyText(): String = SupportReportFormatter.format(this)
}

/** Normalizes optional runtime values and strips paths, queries, and credentials from the URL. */
object SupportReportBuilder {
    private const val UNKNOWN = "Unknown"

    fun build(input: SupportReportInput): SupportReport = SupportReport(
        cometVersion = version(input.cometVersionName, input.cometVersionCode),
        webViewPackage = value(input.webViewPackage),
        webViewVersion = value(input.webViewVersion),
        androidVersion = value(input.androidVersion),
        androidSdk = input.androidSdk?.toString() ?: UNKNOWN,
        deviceManufacturer = value(input.deviceManufacturer),
        deviceModel = value(input.deviceModel),
        activeIdentity = value(input.activeIdentity),
        viewport = input.viewport,
        userAgent = value(input.userAgent),
        clientHints = input.clientHints,
        fullscreenEnabled = input.fullscreenEnabled,
        currentHost = host(input.currentUrl),
        device = input.device
    )

    private fun version(name: String?, code: Long?): String {
        val cleanName = name?.trim().orEmpty()
        return when {
            cleanName.isNotEmpty() && code != null -> "$cleanName ($code)"
            cleanName.isNotEmpty() -> cleanName
            code != null -> code.toString()
            else -> UNKNOWN
        }
    }

    private fun value(value: String?): String = value?.trim()?.takeIf(String::isNotEmpty) ?: UNKNOWN

    private fun host(url: String?): String {
        val cleanUrl = url?.trim()?.takeIf(String::isNotEmpty) ?: return UNKNOWN
        return runCatching { URI(cleanUrl).host }
            .getOrNull()
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
            ?: UNKNOWN
    }
}

/** Produces stable plain text suitable for Android's clipboard or an issue report. */
object SupportReportFormatter {
    private const val UNKNOWN = "Unknown"

    fun format(report: SupportReport): String = buildString {
        appendLine("Comet support report")
        appendLine("Comet: ${report.cometVersion}")
        appendLine("WebView package: ${report.webViewPackage}")
        appendLine("WebView version: ${report.webViewVersion}")
        appendLine("Android: ${report.androidVersion} (SDK ${report.androidSdk})")
        appendLine("Device: ${report.deviceManufacturer} ${report.deviceModel}".trimEnd())
        appendLine("Identity: ${report.activeIdentity}")
        appendLine("Viewport: ${viewport(report.viewport)}")
        appendLine("User-Agent: ${report.userAgent}")
        appendLine("UA-CH brands: ${brands(report.clientHints)}")
        appendLine("UA-CH platform: ${hint(report.clientHints?.platform)}")
        appendLine("UA-CH platform version: ${hint(report.clientHints?.platformVersion)}")
        appendLine("UA-CH architecture: ${hint(report.clientHints?.architecture)}")
        appendLine("UA-CH model: ${hint(report.clientHints?.model)}")
        appendLine("UA-CH mobile: ${hint(report.clientHints?.mobile)}")
        appendLine("UA-CH bitness: ${hint(report.clientHints?.bitness)}")
        appendLine("UA-CH WoW64: ${hint(report.clientHints?.wow64)}")
        appendLine("Fullscreen API enabled: ${hint(report.fullscreenEnabled)}")
        appendLine("Current host: ${report.currentHost}")
        appendLine("-- Device (not the browser identity above) --")
        appendLine("Process: ${report.device?.is64BitProcess?.let { if (it) "64-bit" else "32-bit" } ?: UNKNOWN}")
        appendLine("Supported ABIs: ${report.device?.supportedAbis?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: UNKNOWN}")
        appendLine("Memory class: ${hint(report.device?.memoryClassMb?.let { "$it MB" })}")
        appendLine("Large memory class: ${hint(report.device?.largeMemoryClassMb?.let { "$it MB" })}")
        appendLine("Low-RAM device: ${hint(report.device?.lowRamDevice)}")
        append("Total RAM: ${hint(report.device?.totalRamMb?.let { "$it MB" })}")
    }

    private fun viewport(viewport: SupportViewport?): String {
        viewport ?: return UNKNOWN
        return buildList {
            add(viewport.mode.ifBlank { UNKNOWN })
            viewport.widthCssPixels?.let { add("$it CSS px") }
            viewport.useWideViewPort?.let { add("wide=$it") }
            viewport.loadWithOverviewMode?.let { add("overview=$it") }
        }.joinToString(", ")
    }

    private fun brands(hints: UserAgentClientHints?): String = hints
        ?.brands
        ?.takeIf(List<UserAgentBrand>::isNotEmpty)
        ?.joinToString(", ") { "${it.brand} ${it.version}" }
        ?: UNKNOWN

    private fun hint(value: Any?): String = value?.toString()?.takeIf(String::isNotBlank) ?: UNKNOWN
}
