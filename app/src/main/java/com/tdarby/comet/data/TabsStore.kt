package com.tdarby.comet.data

import android.content.Context
import com.tdarby.comet.web.TabManager.TabSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the open-tab list (URL + title) and the active index to a small JSON file so tabs are
 * restored across app restarts. Engine history/scroll is not snapshotted — only the URLs are.
 */
class TabsStore(context: Context) {

    private val file = File(context.filesDir, "tabs.json")

    var activeIndex: Int = 0
        private set

    /** Read the saved tabs (empty list if none / unreadable). */
    fun load(): List<TabSnapshot> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        activeIndex = root.optInt("active", 0)
        val arr = root.optJSONArray("tabs") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TabSnapshot(o.optString("u"), o.optString("t"))
        }
    }.getOrDefault(emptyList())

    fun save(tabs: List<TabSnapshot>, active: Int) {
        runCatching {
            val arr = JSONArray()
            tabs.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
            file.writeText(JSONObject().put("active", active).put("tabs", arr).toString())
        }
    }
}
