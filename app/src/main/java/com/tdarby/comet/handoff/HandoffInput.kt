package com.tdarby.comet.handoff

/**
 * What the phone may send: an http/https URL, a bare host, or search text, at most [MAX_CHARS]
 * characters. Whether it becomes a URL or a search is decided later by the app's own
 * `UrlUtils.toUrlOrSearch`; this only rejects what must never reach it.
 */
object HandoffInput {
    const val MAX_CHARS = 2048

    enum class Problem { EMPTY, TOO_LONG, BAD_CHARACTERS, UNSUPPORTED_SCHEME }

    sealed interface Result {
        data class Accepted(val text: String) : Result
        data class Rejected(val problem: Problem) : Result
    }

    private val SCHEME_WITH_AUTHORITY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun check(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Rejected(Problem.EMPTY)
        if (text.length > MAX_CHARS) return Result.Rejected(Problem.TOO_LONG)
        if (text.any { Character.isISOControl(it) }) return Result.Rejected(Problem.BAD_CHARACTERS)
        val scheme = SCHEME_WITH_AUTHORITY.find(text)
        if (scheme != null) {
            val isWeb = scheme.value.startsWith("http://", ignoreCase = true) ||
                scheme.value.startsWith("https://", ignoreCase = true)
            // "http://" with nothing after it is not a link either.
            if (!isWeb || text.length == scheme.value.length) {
                return Result.Rejected(Problem.UNSUPPORTED_SCHEME)
            }
        }
        return Result.Accepted(text)
    }
}
