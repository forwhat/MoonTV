package com.moontv.android.cast

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, per-attempt report. No request bodies, cookies or account credentials. */
internal class CastDiagnostics {
    private val lines = ArrayDeque<String>()
    private var length = 0
    private var started = System.nanoTime()

    @Synchronized
    fun reset(header: String) {
        lines.clear()
        length = 0
        started = System.nanoTime()
        add(header)
    }

    @Synchronized
    fun add(message: String) {
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val safe = "[+${elapsed}ms] ${redact(message).take(12 * 1024)}"
        lines.addLast(safe)
        length += safe.length
        while (length > 60 * 1024 || lines.size > 160) {
            length -= lines.removeFirst().length
        }
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    companion object {
        fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date())

        fun redact(message: String): String = URL.replace(message) { match ->
            runCatching {
                val uri = URI(match.value)
                "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}${uri.rawPath.orEmpty()}" +
                    if (uri.rawQuery != null || uri.rawFragment != null) "?[hidden]" else ""
            }.getOrDefault("[redacted-url]")
        }

        private val URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    }
}
