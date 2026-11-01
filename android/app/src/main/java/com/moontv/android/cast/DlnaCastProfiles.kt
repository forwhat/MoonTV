package com.moontv.android.cast

internal data class DlnaCastProfile(
    val name: String,
    val mimeType: String? = null,
    val emptyMetadata: Boolean = false,
)

/**
 * Compatibility profiles for renderers that reject otherwise playable media
 * because of the DIDL-Lite protocolInfo or CurrentURIMetaData value.
 */
internal object DlnaCastProfiles {
    fun forUrl(url: String): List<DlnaCastProfile> {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val mimeType = when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mp3") -> "audio/mpeg"
            else -> null
        }

        return buildList {
            add(DlnaCastProfile(name = "standard", mimeType = mimeType))
            if (path.endsWith(".m3u8")) {
                add(
                    DlnaCastProfile(
                        name = "hls-alternate-mime",
                        mimeType = "application/x-mpegURL",
                    ),
                )
            }
            add(DlnaCastProfile(name = "empty-metadata", emptyMetadata = true))
        }.distinctBy { it.mimeType to it.emptyMetadata }
    }
}
