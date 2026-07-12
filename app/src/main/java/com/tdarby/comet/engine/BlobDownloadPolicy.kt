package com.tdarby.comet.engine

/** Memory-safety checks for blob downloads crossing WebView's JavaScript bridge. */
object BlobDownloadPolicy {
    // A data URL briefly exists as both UTF-16 text and decoded bytes. Keep this conservative for
    // low-memory TV devices rather than allowing a page to hand native code an unbounded value.
    const val MAX_DECODED_BYTES = 16 * 1024 * 1024
    private const val MAX_HEADER_CHARS = 512

    data class ValidatedPayload(val base64Start: Int, val decodedBytes: Int)

    /**
     * Validates the strict Base64 data-URL shape emitted by FileReader.readAsDataURL(). The length
     * checks happen before a payload substring or decoded byte array is allocated.
     */
    fun validate(
        dataUrl: String,
        maxDecodedBytes: Int = MAX_DECODED_BYTES
    ): ValidatedPayload? {
        if (maxDecodedBytes < 0) return null
        val maxEncodedChars = ((maxDecodedBytes.toLong() + 2L) / 3L * 4L).toInt()
        if (dataUrl.length > MAX_HEADER_CHARS + 1 + maxEncodedChars) return null
        if (!dataUrl.startsWith("data:")) return null

        var comma = -1
        val headerLimit = minOf(dataUrl.lastIndex, MAX_HEADER_CHARS)
        for (index in 5..headerLimit) {
            if (dataUrl[index] == ',') {
                comma = index
                break
            }
        }
        if (comma < 0 || comma < 12) return null
        if (!dataUrl.regionMatches(comma - 7, ";base64", 0, 7, ignoreCase = true)) return null

        val start = comma + 1
        val encodedChars = dataUrl.length - start
        if (encodedChars > maxEncodedChars || encodedChars % 4 != 0) return null
        if (encodedChars == 0) return ValidatedPayload(start, 0)

        var padding = 0
        if (dataUrl[dataUrl.lastIndex] == '=') padding++
        if (encodedChars > 1 && dataUrl[dataUrl.lastIndex - 1] == '=') padding++
        val contentEnd = dataUrl.length - padding
        for (index in start until dataUrl.length) {
            val char = dataUrl[index]
            val valid = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '+' || char == '/' || (char == '=' && index >= contentEnd)
            if (!valid) return null
        }

        val decodedBytes = encodedChars / 4 * 3 - padding
        if (decodedBytes > maxDecodedBytes) return null
        return ValidatedPayload(start, decodedBytes)
    }
}
