package com.tdarby.comet.downloads

import android.content.Context

/**
 * Download ids the user removed from Comet's list but kept on disk. DownloadManager.remove() also
 * deletes the file, so "Remove from list" hides the id here instead. Normal tabs only (private
 * tabs have no downloads list).
 */
class HiddenDownloads(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hidden_downloads", Context.MODE_PRIVATE)

    fun isHidden(id: Long): Boolean = prefs.getStringSet(KEY, emptySet())?.contains(id.toString()) == true

    fun hide(id: Long) = edit { it.add(id.toString()) }

    fun forget(id: Long) = edit { it.remove(id.toString()) }

    private fun edit(change: (MutableSet<String>) -> Unit) {
        val set = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        change(set)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    private companion object { const val KEY = "ids" }
}
