package com.tdarby.comet.data

import android.content.Context
import com.tdarby.comet.web.TabManager.TabSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the open-tab list (URL + title) and the active index to a small JSON file so tabs are
 * restored across app restarts. Engine history/scroll is not snapshotted — only the URLs are.
 */
class TabsStore(context: Context) {

    private val file = File(context.filesDir, "tabs.json")

    // limitedParallelism(1) serializes writes in submission order so a later snapshot can't be
    // overwritten by an earlier one, and keeps file I/O off the UI thread.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    var activeIndex: Int = 0
        private set

    /** Read the saved tabs (empty list if none / unreadable). */
    fun load(): List<TabSnapshot> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray("tabs") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val sourceActive = root.optInt("active", 0).coerceIn(0, arr.length() - 1)
        val limit = TabSessionPolicy.MAX_RESTORED_TABS
        val indices = when {
            arr.length() <= limit -> (0 until arr.length()).toList()
            sourceActive < limit -> (0 until limit).toList()
            else -> (0 until limit - 1).toList() + sourceActive
        }
        activeIndex = if (sourceActive < limit) sourceActive else limit - 1
        indices.map { i ->
            val o = arr.getJSONObject(i)
            TabSnapshot(o.optString("u"), o.optString("t"))
        }
    }.getOrDefault(emptyList())

    fun save(tabs: List<TabSnapshot>, active: Int) {
        // Snapshot now (on the caller thread), write on the serialized IO scope.
        val arr = JSONArray()
        tabs.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
        val json = JSONObject().put("active", active).put("tabs", arr).toString()
        io.launch { runCatching { file.writeText(json) } }
    }
}
