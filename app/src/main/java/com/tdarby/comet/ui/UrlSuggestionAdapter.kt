package com.tdarby.comet.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView

/** One address-bar suggestion source: a bookmark or a history entry. */
data class SuggestionCandidate(val url: String, val title: String, val bookmark: Boolean)

/** Matching and ranking for address-bar suggestions, kept pure so it can be unit tested. */
object UrlSuggestions {
    const val MAX_SUGGESTIONS = 8

    /**
     * Candidates whose title or URL contains every word of [query], capped at [limit]. Bookmarks come
     * before history; within each, entries that start with the query (title, or URL without scheme
     * and "www.") come before substring matches. Order inside a rank follows the input order. A URL
     * listed as a bookmark and in history appears once, as the bookmark. A URL equal to the query is
     * left out.
     */
    fun match(query: String, candidates: List<SuggestionCandidate>, limit: Int = MAX_SUGGESTIONS): List<SuggestionCandidate> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        val bookmarkUrls = candidates.filter { it.bookmark }.mapTo(HashSet()) { it.url }
        return candidates
            .asSequence()
            .filter { it.url.isNotBlank() && (it.bookmark || it.url !in bookmarkUrls) }
            // The page whose URL is already in the bar is no suggestion.
            .filter { it.url.lowercase() != q }
            .distinctBy { it.url to it.bookmark }
            .mapNotNull { c ->
                val url = c.url.lowercase()
                val title = c.title.lowercase()
                val haystack = "$title $url"
                if (!words.all { haystack.contains(it) }) return@mapNotNull null
                val bareUrl = url.substringAfter("://").removePrefix("www.")
                val prefix = title.startsWith(q) || bareUrl.startsWith(q) || url.startsWith(q)
                c to ((if (c.bookmark) 0 else 2) + (if (prefix) 0 else 1))
            }
            .sortedBy { it.second } // stable
            .map { it.first }
            .take(limit)
            .toList()
    }
}

/**
 * Autocomplete suggestions for the address bar: matches the user's bookmark and history titles and
 * URLs, so a couple of keypresses surface a full URL on the remote. The dropdown shows the title
 * with the URL; choosing a row fills the address bar with the URL.
 */
class UrlSuggestionAdapter(context: Context) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {

    // Immutable snapshot swapped atomically — performFiltering runs on a worker thread.
    @Volatile
    private var candidates: List<SuggestionCandidate> = emptyList()
    private val shown = mutableListOf<SuggestionCandidate>()

    /** Refresh the candidate pool (call on focus and on each keystroke). */
    fun setCandidates(items: List<SuggestionCandidate>) {
        candidates = items.filter { it.url.isNotBlank() }
    }

    override fun getCount(): Int = shown.size
    override fun getItem(position: Int): String = shown[position].url

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val c = shown[position]
        (view as? TextView)?.text =
            if (c.title.isBlank() || c.title == c.url) c.url else "${c.title}  ·  ${c.url}"
        return view
    }

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val matches = UrlSuggestions.match(constraint?.toString().orEmpty(), candidates) // one volatile read
            return FilterResults().apply { values = matches; count = matches.size }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            shown.clear()
            @Suppress("UNCHECKED_CAST")
            (results?.values as? List<SuggestionCandidate>)?.let { shown.addAll(it) }
            if ((results?.count ?: 0) > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }
    }

    override fun getFilter(): Filter = filter
}
