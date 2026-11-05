package com.tdarby.comet.handoff

import java.security.MessageDigest
import java.util.Base64

/** The few HTML pages the phone sees. Plain HTML, no scripts, one hashed style block. */
object HandoffPages {
    private const val CSS =
        "body{font:18px/1.4 system-ui,sans-serif;margin:0;padding:20px;background:#111;color:#eee}" +
            "main{max-width:30em;margin:0 auto}h1{font-size:1.4em}" +
            "input{box-sizing:border-box;width:100%;font:inherit;padding:12px;margin:8px 0 16px;" +
            "border-radius:8px;border:1px solid #555;background:#222;color:#eee}" +
            "button{font:inherit;width:100%;padding:14px;border:0;border-radius:8px;" +
            "background:#4fc3f7;color:#000}" +
            "a{color:#4fc3f7}.err{color:#ff8a80}.ok{color:#a5d6a7}"

    /** Only the one style block is allowed to run; nothing else may load or be submitted elsewhere. */
    val contentSecurityPolicy: String = "default-src 'none'; style-src 'sha256-" +
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(CSS.toByteArray())) +
        "'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun codePage(error: String? = null): String = page(
        "<h1>Send to Comet</h1><p>Enter the 6-digit code shown on the TV.</p>" +
            errorLine(error) +
            "<form method=\"post\" action=\"/\">" +
            "<input type=\"text\" name=\"code\" inputmode=\"numeric\" maxlength=\"6\" " +
            "autocomplete=\"off\" autofocus required>" +
            "<button type=\"submit\">Continue</button></form>"
    )

    fun sendPage(token: String, error: String? = null): String = page(
        "<h1>Send to Comet</h1><p>Type a web address or something to search for.</p>" +
            errorLine(error) +
            "<form method=\"post\" action=\"/t/${escape(token)}\">" +
            "<input type=\"text\" name=\"text\" maxlength=\"${HandoffInput.MAX_CHARS}\" " +
            "placeholder=\"Web address or search\" autocomplete=\"off\" autocapitalize=\"off\" " +
            "autocorrect=\"off\" spellcheck=\"false\" autofocus required>" +
            "<button type=\"submit\">Send</button></form>"
    )

    fun sentPage(token: String, text: String): String = page(
        "<h1 class=\"ok\">Sent</h1><p>The TV is asking whether to open it.</p>" +
            "<p>${escape(text.take(200))}</p><p><a href=\"/t/${escape(token)}\">Send another</a></p>"
    )

    fun messagePage(title: String, message: String): String =
        page("<h1>${escape(title)}</h1><p>${escape(message)}</p>")

    private fun errorLine(error: String?) = if (error == null) "" else "<p class=\"err\">${escape(error)}</p>"

    private fun page(body: String) =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Comet</title><style>$CSS</style></head><body><main>$body</main></body></html>"
}
