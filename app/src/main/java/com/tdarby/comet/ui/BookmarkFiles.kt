package com.tdarby.comet.ui

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.tdarby.comet.data.BookmarkTransfer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A Comet bookmark export found in the Downloads folder. */
data class BookmarkExportFile(val name: String, val uri: Uri)

/** Reads and writes bookmark export files in the device's Downloads folder. Call off the main thread. */
object BookmarkFiles {
    private const val NAME_PREFIX = "comet-bookmarks"
    private const val MAX_LISTED = 20

    fun newFileName(now: Date = Date()): String =
        "$NAME_PREFIX-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".json"

    fun isoDate(now: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

    /** Saves [content] as [name] in Downloads. Returns false when the file could not be written. */
    fun save(context: Context, name: String, content: String): Boolean = runCatching {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
        true
    }.getOrDefault(false)

    /**
     * Comet export files in Downloads, newest first. Android 10 and later show an app only the
     * files it saved there itself; Android 9 reads the folder, so a copied-in backup is found too.
     */
    fun listExports(context: Context): List<BookmarkExportFile> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listFromMediaStore(context) else listFromFolder()
    }.getOrDefault(emptyList())

    /** Android 9: read the folder itself; a file just written there isn't in MediaStore until it is scanned. */
    private fun listFromFolder(): List<BookmarkExportFile> {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return dir.listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) && f.name.endsWith(".json") }
            .orEmpty().sortedByDescending { it.lastModified() }.take(MAX_LISTED)
            .map { BookmarkExportFile(it.name, Uri.fromFile(it)) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listFromMediaStore(context: Context): List<BookmarkExportFile> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val out = mutableListOf<BookmarkExportFile>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$NAME_PREFIX%.json"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_LISTED) {
                out.add(BookmarkExportFile(c.getString(1), ContentUris.withAppendedId(collection, c.getLong(0))))
            }
        }
        return out
    }

    /** The file's text, or null when it can't be read. A file over the size limit comes back truncated past the limit. */
    fun read(context: Context, uri: Uri): ReadResult? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BookmarkTransfer.MAX_FILE_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            ReadResult(String(buffer, 0, total, Charsets.UTF_8), total)
        }
    }.getOrNull()

    data class ReadResult(val text: String, val byteLength: Int)
}
