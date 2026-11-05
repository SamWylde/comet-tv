package com.tdarby.comet.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tdarby.comet.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest

/**
 * Checks a hosted JSON manifest for a newer build of the current flavor, downloads the APK,
 * verifies its SHA-256, and launches the system package installer (user-confirmed). Never installs
 * silently. The manifest holds a single "webview" entry:
 *
 * ```json
 * { "webview": { "versionCode": 2, "versionName": "1.1.0", "apkUrl": "...", "sha256": "...",
 *                "notes": "...", "required": false } }
 * ```
 */
class UpdateChecker(private val context: Context) {

    suspend fun check(): ReleaseManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val text = readUrl(MANIFEST_URL) ?: return@runCatching null
            val flavor = JSONObject(text).optJSONObject("webview")
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

    /** Result of [download]: the file is not yet verified; call [verify]. */
    sealed interface DownloadResult {
        data class Done(val apk: File) : DownloadResult
        data class Failed(val noNetwork: Boolean) : DownloadResult
    }

    /** The APK file the last download wrote (it may not exist). */
    fun apkFile(): File = File(context.cacheDir, "comet-update.apk")

    /**
     * Download the APK to cache. [onProgress] gets whole percent values (only when the size is
     * known) from the IO thread. Cancelling the calling coroutine stops the copy and deletes the
     * partial file. The result is unverified: [verify] must pass before installing.
     */
    suspend fun download(manifest: ReleaseManifest, onProgress: (Int) -> Unit = {}): DownloadResult =
        withContext(Dispatchers.IO) {
            val out = apkFile()
            try {
                val conn = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext DownloadResult.Failed(noNetwork = false)
                }
                val total = conn.contentLengthLong
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    out.outputStream().use { sink ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                            }
                        }
                    }
                }
                DownloadResult.Done(out)
            } catch (e: CancellationException) {
                out.delete()
                throw e
            } catch (e: Exception) {
                out.delete()
                DownloadResult.Failed(
                    noNetwork = e is UnknownHostException || e is ConnectException || e is SocketTimeoutException
                )
            }
        }

    /**
     * Verification is **mandatory**: a manifest without a sha256, or a mismatch, fails (we never
     * install an unverified APK). A failing file is deleted.
     */
    suspend fun verify(apk: File, manifest: ReleaseManifest): Boolean = withContext(Dispatchers.IO) {
        val ok = manifest.sha256.isNotBlank() && apk.isFile &&
            runCatching { sha256Matches(apk, manifest.sha256) }.getOrDefault(false)
        if (!ok) apk.delete()
        ok
    }

    /** Launch the package installer for a downloaded APK (user confirms). False if it can't be launched. */
    fun install(apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
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
        // Hosted manifest (raw GitHub on the default branch). It names the tag-specific apkUrl
        // (/releases/download/vX.Y.Z/...) and is updated only after that release's assets exist.
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/SamWylde/comet-tv/main/dist/latest.json"
    }
}
