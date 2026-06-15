package com.tdarby.comet.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tdarby.comet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks a hosted JSON manifest for a newer build of the current flavor, downloads the APK,
 * verifies its SHA-256, and launches the system package installer (user-confirmed). Never installs
 * silently. The manifest is keyed by flavor:
 *
 * ```json
 * { "webview": { "versionCode": 2, "versionName": "1.1.0", "apkUrl": "...", "sha256": "...",
 *                "notes": "...", "required": false },
 *   "full":    { ... } }
 * ```
 */
class UpdateChecker(private val context: Context) {

    suspend fun check(): ReleaseManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val text = readUrl(MANIFEST_URL) ?: return@runCatching null
            val flavor = JSONObject(text).optJSONObject(BuildConfig.FLAVOR_LABEL)
                ?: return@runCatching null
            ReleaseManifest(
                versionCode = flavor.getInt("versionCode"),
                versionName = flavor.optString("versionName"),
                apkUrl = flavor.getString("apkUrl"),
                sha256 = flavor.optString("sha256"),
                notes = flavor.optString("notes"),
                required = flavor.optBoolean("required", false)
            )
        }.getOrNull()
    }

    fun isNewer(manifest: ReleaseManifest): Boolean =
        manifest.versionCode > BuildConfig.VERSION_CODE

    /**
     * Download the APK to cache and verify its SHA-256. Verification is **mandatory**: a manifest
     * without a sha256, or a mismatch, yields null (we never install an unverified APK).
     */
    suspend fun download(manifest: ReleaseManifest): File? = withContext(Dispatchers.IO) {
        if (manifest.sha256.isBlank()) return@withContext null
        runCatching {
            val out = File(context.cacheDir, "comet-update.apk")
            (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }

            if (!sha256Matches(out, manifest.sha256)) {
                out.delete()
                return@runCatching null
            }
            out
        }.getOrNull()
    }

    /** Launch the package installer for a downloaded APK (user confirms). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun readUrl(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return conn.inputStream.use { it.bufferedReader().readText() }
    }

    private fun sha256Matches(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(expected.trim(), ignoreCase = true)
    }

    companion object {
        // TODO: point at your GitHub Releases/Pages manifest once the repo exists.
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/OWNER/REPO/main/dist/latest.json"
    }
}
