package com.tdarby.comet.handoff

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One pairing session: a 128-bit token (carried by the QR code) and a 6-digit code (typed by hand).
 * It ends after [lifetimeMs], or for good after [MAX_WRONG_CODES] wrong codes. Secrets are compared
 * in constant time. Thread-safe: two connections may be served at once.
 */
class HandoffSession(
    val token: String,
    val code: String,
    private val lifetimeMs: Long = LIFETIME_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    enum class State { ACTIVE, EXPIRED, LOCKED }

    sealed interface CodeResult {
        object Accepted : CodeResult
        data class Wrong(val triesLeft: Int) : CodeResult
        object Locked : CodeResult
        object Expired : CodeResult
    }

    private val startedAt = clock()
    private var wrongCodes = 0

    @get:Synchronized
    val state: State
        get() = when {
            wrongCodes >= MAX_WRONG_CODES -> State.LOCKED
            clock() - startedAt >= lifetimeMs -> State.EXPIRED
            else -> State.ACTIVE
        }

    @Synchronized
    fun tokenMatches(candidate: String): Boolean {
        val equal = constantTimeEquals(token, candidate)
        return equal && state == State.ACTIVE
    }

    @Synchronized
    fun tryCode(candidate: String?): CodeResult {
        when (state) {
            State.LOCKED -> return CodeResult.Locked
            State.EXPIRED -> return CodeResult.Expired
            State.ACTIVE -> Unit
        }
        if (candidate != null && constantTimeEquals(code, candidate)) return CodeResult.Accepted
        wrongCodes++
        return if (wrongCodes >= MAX_WRONG_CODES) CodeResult.Locked
        else CodeResult.Wrong(MAX_WRONG_CODES - wrongCodes)
    }

    companion object {
        const val MAX_WRONG_CODES = 5
        const val LIFETIME_MS = 10 * 60 * 1000L

        fun generate(random: SecureRandom = SecureRandom()): HandoffSession {
            val bytes = ByteArray(16).also(random::nextBytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            val code = "%06d".format(random.nextInt(1_000_000))
            return HandoffSession(token, code)
        }

        private fun constantTimeEquals(secret: String, candidate: String): Boolean =
            MessageDigest.isEqual(secret.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }
}
