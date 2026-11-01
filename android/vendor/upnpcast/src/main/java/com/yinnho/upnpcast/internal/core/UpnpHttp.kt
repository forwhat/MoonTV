package com.yinnho.upnpcast.internal.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared HTTP execution for UPnP traffic (device description retrieval and
 * SOAP control requests), so timeouts, headers and connection handling stay
 * consistent across the library
 */
internal object UpnpHttp {

    internal data class DiagnosticEvent(
        val operation: String,
        val url: String,
        val status: Int?,
        val elapsedMs: Long,
        val response: String = "",
        val error: String? = null,
        val requestSummary: String? = null,
    )

    @Volatile
    var diagnosticObserver: ((DiagnosticEvent) -> Unit)? = null

    private fun report(event: DiagnosticEvent) {
        runCatching { diagnosticObserver?.invoke(event) }
    }

    private const val USER_AGENT = "UPnPCast/1.0"

    /**
     * GET a resource body; returns null on failure. Retries transient
     * failures (non-200 or IO error) up to [maxRetries] times with a linear
     * backoff of [retryDelayMs] * attempt.
     */
    suspend fun get(
        url: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 10000,
        maxRetries: Int = 1,
        retryDelayMs: Long = 1000L
    ): String? = withContext(Dispatchers.IO) {
        repeat(maxRetries) { attempt ->
            val attemptNumber = attempt + 1
            var connection: HttpURLConnection? = null
            val started = System.nanoTime()
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                        val response = reader.readText()
                        report(DiagnosticEvent("GET description attempt=$attemptNumber", url, status,
                            (System.nanoTime() - started) / 1_000_000, response.take(24 * 1024)))
                        return@withContext response
                    }
                }
                report(DiagnosticEvent("GET description attempt=$attemptNumber", url, status,
                    (System.nanoTime() - started) / 1_000_000))
            } catch (e: Exception) {
                report(DiagnosticEvent("GET description attempt=$attemptNumber", url, null,
                    (System.nanoTime() - started) / 1_000_000, error = "${e.javaClass.simpleName}: ${e.message}"))
                // Fall through to retry
            } finally {
                connection?.disconnect()
            }
            if (attemptNumber < maxRetries) {
                delay(retryDelayMs * attemptNumber)
            }
        }
        null
    }

    /**
     * POST a SOAP envelope with the given action header; returns the
     * response body on HTTP 200, null otherwise.
     */
    suspend fun postSoap(
        url: String,
        soapAction: String,
        body: String,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 5000
    ): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val started = System.nanoTime()
        val payload = soapEnvelope(body).toByteArray(Charsets.UTF_8)
        val requestSummary = "POST Content-Type=text/xml; charset=utf-8 SOAPAction=\"$soapAction\" " +
            "Content-Length=${payload.size} xmlDeclarationAtByteZero=${payload.firstOrNull() == '<'.code.toByte()}"
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setFixedLengthStreamingMode(payload.size)

            connection.outputStream.use { outputStream ->
                outputStream.write(payload)
            }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                    val response = reader.readText()
                    report(DiagnosticEvent(soapAction, url, status,
                        (System.nanoTime() - started) / 1_000_000, response.take(8 * 1024), requestSummary = requestSummary))
                    response
                }
            } else {
                val fault = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(1024)
                        while (result.length < 8 * 1024) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, 8 * 1024 - result.length))
                            if (count < 0) break
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }.orEmpty()
                }.getOrDefault("")
                report(DiagnosticEvent(soapAction, url, status,
                    (System.nanoTime() - started) / 1_000_000, fault, requestSummary = requestSummary))
                null
            }
        } catch (e: Exception) {
            report(DiagnosticEvent(soapAction, url, null,
                (System.nanoTime() - started) / 1_000_000, error = "${e.javaClass.simpleName}: ${e.message}", requestSummary = requestSummary))
            null
        } finally {
            connection?.disconnect()
        }
    }

    // Do not interpolate a multiline action into an indented raw string:
    // its zero-indented lines change trimIndent's common margin, leaving
    // whitespace before the XML declaration and producing invalid XML.
    fun soapEnvelope(body: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
            "<s:Body>\n$body\n</s:Body>\n</s:Envelope>"
}
