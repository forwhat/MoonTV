package com.moontv.android.cast

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/**
 * Resolves a master HLS playlist to its highest-bandwidth media playlist.
 * The TV still downloads the selected playlist directly; this is not a proxy.
 */
internal object HlsVariantResolver {
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_PLAYLIST_BYTES = 1024 * 1024

    fun resolveVariant(url: String): String? {
        if (!isHlsUrl(url)) return null
        val download = download(url) ?: return null
        return selectVariant(download.finalUrl, download.body)?.takeIf { it != url }
    }

    internal fun selectVariant(baseUrl: String, playlist: String): String? {
        val lines = playlist.lineSequence().map(String::trim).toList()
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?: return@forEachIndexed
                val bandwidth = BANDWIDTH_PATTERN.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                add(bandwidth to uri)
            }
        }
        val selected = variants.maxByOrNull { it.first }?.second ?: return null
        return runCatching { URI(baseUrl).resolve(selected).toString() }.getOrNull()
    }

    private fun isHlsUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8")
    }

    private fun download(url: String): PlaylistDownload? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI(url).toURL().openConnection() as? HttpURLConnection ?: return null
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty(
                "Accept",
                "application/vnd.apple.mpegurl, application/x-mpegURL, */*",
            )
            connection.setRequestProperty("User-Agent", "MoonTV-Android")
            if (connection.responseCode !in 200..299) return null

            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var remaining = MAX_PLAYLIST_BYTES
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            PlaylistDownload(
                finalUrl = connection.url.toString(),
                body = output.toString(Charsets.UTF_8.name()),
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private data class PlaylistDownload(
        val finalUrl: String,
        val body: String,
    )

    private val BANDWIDTH_PATTERN = Regex("(?:^|[:,])\\s*BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
}
