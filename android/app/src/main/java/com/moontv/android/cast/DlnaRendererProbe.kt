package com.moontv.android.cast

import com.moontv.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/**
 * Verifies an unclassified UPnP device by inspecting its device description.
 *
 * UPnPCast v1.3.0 labels televisions with a manufacturer-name heuristic. That
 * misses valid renderers such as Toshiba sets, so capability discovery is used
 * as the fallback instead of extending a vendor allow-list.
 */
internal object DlnaRendererProbe {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 3_000
    private const val MAX_DESCRIPTION_BYTES = 512 * 1024

    fun isRenderer(descriptionUrl: String): Boolean {
        val description = downloadDescription(descriptionUrl) ?: return false
        return hasRendererCapability(description)
    }

    internal fun hasRendererCapability(description: String): Boolean {
        val normalized = description.lowercase(Locale.ROOT)
        return "urn:schemas-upnp-org:device:mediarenderer:" in normalized ||
            "urn:schemas-upnp-org:service:avtransport:" in normalized
    }

    private fun downloadDescription(descriptionUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val uri = URI(descriptionUrl)
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null

            connection = uri.toURL().openConnection() as? HttpURLConnection ?: return null
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty(
                "User-Agent",
                "MoonTV-Android/${BuildConfig.VERSION_NAME} UPnP/1.0",
            )

            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var remaining = MAX_DESCRIPTION_BYTES
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
