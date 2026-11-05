package com.tdarby.comet.handoff

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffInputTest {
    private fun problem(raw: String) = (HandoffInput.check(raw) as? HandoffInput.Result.Rejected)?.problem
    private fun accepted(raw: String) = (HandoffInput.check(raw) as? HandoffInput.Result.Accepted)?.text

    @Test
    fun webLinksHostsAndSearchTextAreAccepted() {
        val cases = mapOf(
            "https://example.com/a?b=c" to "https://example.com/a?b=c",
            "HTTP://Example.com" to "HTTP://Example.com",
            "  example.com  " to "example.com",
            "funny cat videos" to "funny cat videos",
            "javascript:alert(1)" to "javascript:alert(1)", // no "://": the app turns it into a search
            "x".repeat(HandoffInput.MAX_CHARS) to "x".repeat(HandoffInput.MAX_CHARS)
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, accepted(raw)) }
    }

    @Test
    fun everythingElseIsRejectedWithItsReason() {
        val cases = mapOf(
            "" to HandoffInput.Problem.EMPTY,
            "   " to HandoffInput.Problem.EMPTY,
            "x".repeat(HandoffInput.MAX_CHARS + 1) to HandoffInput.Problem.TOO_LONG,
            "https://example.com/\nattack" to HandoffInput.Problem.BAD_CHARACTERS,
            "cats\u0000" to HandoffInput.Problem.BAD_CHARACTERS,
            "file:///sdcard/secret" to HandoffInput.Problem.UNSUPPORTED_SCHEME,
            "javascript://%0aalert(1)" to HandoffInput.Problem.UNSUPPORTED_SCHEME,
            "intent://scan/#Intent;end" to HandoffInput.Problem.UNSUPPORTED_SCHEME,
            "ftp://example.com" to HandoffInput.Problem.UNSUPPORTED_SCHEME,
            "https://" to HandoffInput.Problem.UNSUPPORTED_SCHEME
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, problem(raw)) }
    }
}
