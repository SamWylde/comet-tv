package com.tdarby.comet.handoff

import com.tdarby.comet.data.BookmarkTransfer
import com.tdarby.comet.data.SiteItem
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What the TV did with a submitted link or backup. */
enum class SendOutcome { ACCEPTED, BUSY, CLOSED }

/** What the TV offers for bookmark backups. The functions run on server threads. */
class HandoffBackup(
    /** This TV's bookmarks, read on the TV's main thread; null when that did not happen in time. */
    val bookmarks: () -> List<SiteItem>?,
    val fileName: () -> String,
    val exportedAt: () -> String,
    /** Hands a parsed backup (its bookmarks and the count of entries dropped as malformed) to the TV. */
    val receive: (List<SiteItem>, Int) -> SendOutcome
)

class HandoffResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val contentType: String = "text/html; charset=utf-8"
) {
    fun toBytes(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
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
 * accepted text to the TV. With a [backup] there are also `/t/<token>/bookmarks` (GET the page, POST
 * a backup file) and `GET /t/<token>/bookmarks.json`; without one those are 404 like any other path.
 */
class HandoffRouter(
    private val session: HandoffSession,
    private val backup: HandoffBackup? = null,
    private val send: (String) -> SendOutcome
) {
    /**
     * The largest request body the server may read for this method and path. Only an upload of a
     * backup to a path that carries the right token gets more than the ordinary limit; the decision
     * is made from the request line, before any body is read.
     */
    fun bodyLimit(method: String, path: String): Int {
        if (backup != null && method == "POST" && path.startsWith(TOKEN_PREFIX) && path.endsWith(BOOKMARKS_SUFFIX) &&
            session.tokenMatches(path.substring(TOKEN_PREFIX.length, path.length - BOOKMARKS_SUFFIX.length))
        ) {
            return UPLOAD_BODY_LIMIT
        }
        return HandoffRequestParser.MAX_BODY_BYTES
    }

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
        if (path.startsWith(TOKEN_PREFIX)) {
            val rest = path.removePrefix(TOKEN_PREFIX)
            val slash = rest.indexOf('/')
            val tokenPart = if (slash < 0) rest else rest.substring(0, slash)
            val subPath = if (slash < 0) "" else rest.substring(slash)
            if (session.tokenMatches(tokenPart)) {
                when {
                    subPath.isEmpty() -> return when (request.method) {
                        "GET" -> HandoffResponse.html(200, HandoffPages.sendPage(session.token, backupLink = backup != null))
                        "POST" -> submitText(request)
                        else -> methodNotAllowed()
                    }
                    backup != null && subPath == BOOKMARKS_SUFFIX -> return when (request.method) {
                        "GET" -> HandoffResponse.html(200, HandoffPages.backupPage(session.token))
                        "POST" -> submitBackup(request, backup)
                        else -> methodNotAllowed()
                    }
                    backup != null && subPath == BOOKMARKS_JSON_SUFFIX -> return when (request.method) {
                        "GET" -> downloadBackup(backup)
                        else -> methodNotAllowed("GET")
                    }
                }
            }
        }
        return message(404, "Not found", "Nothing here.")
    }

    private fun downloadBackup(backup: HandoffBackup): HandoffResponse {
        val list = backup.bookmarks()
            ?: return message(503, "TV busy", "Comet on the TV couldn't read its bookmarks in time. Try again in a moment.")
        if (list.isEmpty()) return message(200, "No bookmarks yet", "This TV has no bookmarks to save.")
        return HandoffResponse(
            200,
            mapOf("Content-Disposition" to "attachment; filename=\"${backup.fileName()}\""),
            BookmarkTransfer.export(list, backup.exportedAt()).toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8"
        )
    }

    private fun submitBackup(request: HandoffRequest, backup: HandoffBackup): HandoffResponse {
        fun again(status: Int, error: String) =
            HandoffResponse.html(status, HandoffPages.backupPage(session.token, error))
        val content = when (val part = MultipartFile.parse(request.headers["content-type"], request.body)) {
            MultipartFile.Result.Invalid -> return again(400, NOT_A_BACKUP)
            MultipartFile.Result.NoFile -> return again(400, "Choose a file first.")
            is MultipartFile.Result.File -> part.content
        }
        if (content.size > BookmarkTransfer.MAX_FILE_BYTES) {
            return again(413, "That file is too big for a bookmarks backup (limit 1 MB).")
        }
        val text = decodeUtf8(content) ?: return again(400, NOT_A_BACKUP)
        return when (val parsed = BookmarkTransfer.parse(text.removePrefix("﻿"), content.size)) {
            is BookmarkTransfer.Parsed.Rejected -> when (parsed.reason) {
                BookmarkTransfer.Failure.TOO_LARGE -> again(413, "That file is too big for a bookmarks backup (limit 1 MB).")
                BookmarkTransfer.Failure.NOT_A_BOOKMARK_FILE -> again(400, NOT_A_BACKUP)
            }
            is BookmarkTransfer.Parsed.Ok -> when (backup.receive(parsed.items, parsed.skipped)) {
                SendOutcome.ACCEPTED -> HandoffResponse.html(200, HandoffPages.backupSentPage(session.token))
                SendOutcome.BUSY -> again(
                    429,
                    "The TV is still asking about the last thing you sent. Try again in a moment."
                )
                SendOutcome.CLOSED -> message(410, "Session ended", "This session ended. Start a new one on the TV.")
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
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

    private fun methodNotAllowed(allow: String = "GET, POST") =
        HandoffResponse.html(
            405,
            HandoffPages.messagePage("Method not allowed", if (allow == "GET") "Use GET." else "Use GET or POST."),
            mapOf("Allow" to allow)
        )

    private fun message(status: Int, title: String, text: String) =
        HandoffResponse.html(status, HandoffPages.messagePage(title, text))

    private companion object {
        const val TOKEN_PREFIX = "/t/"
        const val BOOKMARKS_SUFFIX = "/bookmarks"
        const val BOOKMARKS_JSON_SUFFIX = "/bookmarks.json"
        const val NOT_A_BACKUP = "That isn't a Comet bookmarks file."

        /** The largest backup file, plus room for the multipart framing around it. */
        const val UPLOAD_BODY_LIMIT = BookmarkTransfer.MAX_FILE_BYTES + 16 * 1024
    }
}
