package com.tdarby.comet.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * Autocomplete suggestions for the address bar: substring matches over the user's history and
 * bookmark URLs (case-insensitive), so a couple of keypresses surface a full URL on the remote.
 */
class UrlSuggestionAdapter(context: Context) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {

    // Immutable snapshot swapped atomically — performFiltering runs on a worker thread.
    @Volatile
    private var candidates: List<String> = emptyList()
    private val shown = mutableListOf<String>()

    /** Refresh the candidate pool (call on focus and on each keystroke). */
    fun setCandidates(urls: List<String>) {
        candidates = urls.filter { it.isNotBlank() }.distinct()
    }

    override fun getCount(): Int = shown.size
    override fun getItem(position: Int): String = shown[position]

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val pool = candidates // single volatile read -> consistent snapshot
            val matches =
                if (q.isEmpty()) emptyList()
                else pool.filter { it.lowercase().contains(q) }.take(MAX_SUGGESTIONS)
            return FilterResults().apply { values = matches; count = matches.size }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            shown.clear()
            @Suppress("UNCHECKED_CAST")
            (results?.values as? List<String>)?.let { shown.addAll(it) }
            if ((results?.count ?: 0) > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }
    }

    override fun getFilter(): Filter = filter

    private companion object {
        const val MAX_SUGGESTIONS = 8
    }
}
