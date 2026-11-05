package com.tdarby.comet.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffSessionTest {
    private var now = 0L
    private val session = HandoffSession("a".repeat(32), "123456", lifetimeMs = 1000, clock = { now })

    @Test
    fun theCodeLocksTheSessionAfterFiveWrongTries() {
        for (tries in 4 downTo 1) {
            assertEquals(HandoffSession.CodeResult.Wrong(tries), session.tryCode("000000"))
        }
        assertEquals(HandoffSession.CodeResult.Locked, session.tryCode("000000"))
        assertEquals(HandoffSession.State.LOCKED, session.state)
        // Even the right code and the right token are refused once it is locked.
        assertEquals(HandoffSession.CodeResult.Locked, session.tryCode("123456"))
        assertFalse(session.tokenMatches("a".repeat(32)))
    }

    @Test
    fun aRightCodeBeforeTheLimitIsAccepted() {
        repeat(4) { session.tryCode("nope") }
        assertEquals(HandoffSession.CodeResult.Accepted, session.tryCode("123456"))
    }

    @Test
    fun aMissingCodeCountsAsWrong() {
        assertEquals(HandoffSession.CodeResult.Wrong(4), session.tryCode(null))
    }

    @Test
    fun onlyTheExactTokenMatches() {
        assertTrue(session.tokenMatches("a".repeat(32)))
        assertFalse(session.tokenMatches("a".repeat(31) + "b"))
        assertFalse(session.tokenMatches("a".repeat(31)))
        assertFalse(session.tokenMatches(""))
    }

    @Test
    fun theSessionEndsAfterItsLifetime() {
        now = 999
        assertEquals(HandoffSession.State.ACTIVE, session.state)
        assertTrue(session.tokenMatches("a".repeat(32)))
        now = 1000
        assertEquals(HandoffSession.State.EXPIRED, session.state)
        assertFalse(session.tokenMatches("a".repeat(32)))
        assertEquals(HandoffSession.CodeResult.Expired, session.tryCode("123456"))
    }

    @Test
    fun generatedSecretsHaveTheRightShapeAndDiffer() {
        val a = HandoffSession.generate()
        val b = HandoffSession.generate()
        assertTrue(a.token.matches(Regex("[0-9a-f]{32}")))
        assertTrue(a.code.matches(Regex("\\d{6}")))
        assertNotEquals(a.token, b.token)
        assertEquals(10 * 60 * 1000L, HandoffSession.LIFETIME_MS)
    }
}
