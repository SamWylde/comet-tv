package com.tdarby.comet.handoff

/** What the TV did with a submitted link. */
enum class SendOutcome { ACCEPTED, BUSY, CLOSED }

class HandoffResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0)
) {
    fun toBytes(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Security-Policy: ").append(HandoffPages.contentSecurityPolicy).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
        fun html(status: Int, html: String, extra: Map<String, String> = emptyMap()) =
            HandoffResponse(status, extra, html.toByteArray(Charsets.UTF_8))

        /** A plain page for a status the parser or server refuses with. */
        fun error(status: Int): HandoffResponse =
            html(status, HandoffPages.messagePage(reason(status), "This request can't be handled."))

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            303 -> "See Other"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            415 -> "Unsupported Media Type"
            423 -> "Locked"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}

/**
 * Routes: `GET /` and `POST /` (the code), `GET /t/<token>` and `POST /t/<token>` (the send form).
 * Everything else is 404, or 405 when the path exists but the method doesn't. [send] hands an
 * accepted text to the TV.
 */
class HandoffRouter(
    private val session: HandoffSession,
    private val send: (String) -> SendOutcome
) {
    fun handle(request: HandoffRequest): HandoffResponse {
        when (session.state) {
            HandoffSession.State.EXPIRED ->
                return message(410, "Session ended", "This session ended. Start a new one on the TV.")
            HandoffSession.State.LOCKED ->
                return message(423, "Session locked", "Too many wrong codes. Start a new session on the TV.")
            HandoffSession.State.ACTIVE -> Unit
        }
        val path = request.path
        if (path == "/") {
            return when (request.method) {
                "GET" -> HandoffResponse.html(200, HandoffPages.codePage())
                "POST" -> submitCode(request)
                else -> methodNotAllowed()
            }
        }
        if (path.startsWith(TOKEN_PREFIX) && session.tokenMatches(path.removePrefix(TOKEN_PREFIX))) {
            return when (request.method) {
                "GET" -> HandoffResponse.html(200, HandoffPages.sendPage(session.token))
                "POST" -> submitText(request)
                else -> methodNotAllowed()
            }
        }
        return message(404, "Not found", "Nothing here.")
    }

    private fun submitCode(request: HandoffRequest): HandoffResponse {
        val form = formOf(request) ?: return HandoffResponse.error(formStatus(request))
        return when (val result = session.tryCode(form["code"]?.trim())) {
            HandoffSession.CodeResult.Accepted ->
                HandoffResponse(303, mapOf("Location" to TOKEN_PREFIX + session.token))
            is HandoffSession.CodeResult.Wrong -> HandoffResponse.html(
                403,
                HandoffPages.codePage(
                    if (result.triesLeft == 1) "Wrong code. 1 try left." else "Wrong code. ${result.triesLeft} tries left."
                )
            )
            HandoffSession.CodeResult.Locked ->
                message(423, "Session locked", "Too many wrong codes. Start a new session on the TV.")
            HandoffSession.CodeResult.Expired ->
                message(410, "Session ended", "This session ended. Start a new one on the TV.")
        }
    }

    private fun submitText(request: HandoffRequest): HandoffResponse {
        val form = formOf(request) ?: return HandoffResponse.error(formStatus(request))
        val raw = form["text"] ?: return HandoffResponse.error(400)
        return when (val checked = HandoffInput.check(raw)) {
            is HandoffInput.Result.Rejected -> HandoffResponse.html(
                400,
                HandoffPages.sendPage(session.token, problemText(checked.problem))
            )
            is HandoffInput.Result.Accepted -> when (send(checked.text)) {
                SendOutcome.ACCEPTED -> HandoffResponse.html(200, HandoffPages.sentPage(session.token, checked.text))
                SendOutcome.BUSY -> HandoffResponse.html(
                    429,
                    HandoffPages.sendPage(session.token, "The TV is still asking about the last link. Try again in a moment.")
                )
                SendOutcome.CLOSED -> message(410, "Session ended", "This session ended. Start a new one on the TV.")
            }
        }
    }

    private fun formOf(request: HandoffRequest): Map<String, String>? {
        val type = request.headers["content-type"]?.lowercase() ?: return null
        if (!type.startsWith("application/x-www-form-urlencoded")) return null
        return FormBody.parse(request.body)
    }

    /** Why [formOf] returned null: a wrong content type, or a body that doesn't decode. */
    private fun formStatus(request: HandoffRequest): Int {
        val type = request.headers["content-type"]?.lowercase()
        return if (type == null || !type.startsWith("application/x-www-form-urlencoded")) 415 else 400
    }

    private fun problemText(problem: HandoffInput.Problem) = when (problem) {
        HandoffInput.Problem.EMPTY -> "Type something first."
        HandoffInput.Problem.TOO_LONG -> "That is too long (at most ${HandoffInput.MAX_CHARS} characters)."
        HandoffInput.Problem.BAD_CHARACTERS -> "That has characters that can't be sent."
        HandoffInput.Problem.UNSUPPORTED_SCHEME -> "Only http and https links can be sent."
    }

    private fun methodNotAllowed() =
        HandoffResponse.html(405, HandoffPages.messagePage("Method not allowed", "Use GET or POST."), mapOf("Allow" to "GET, POST"))

    private fun message(status: Int, title: String, text: String) =
        HandoffResponse.html(status, HandoffPages.messagePage(title, text))

    private companion object {
        const val TOKEN_PREFIX = "/t/"
    }
}
