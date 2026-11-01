package com.moontv.android.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsVariantResolverTest {
    @Test
    fun selectsHighestBandwidthVariantAndResolvesRelativeUrl() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3500000,RESOLUTION=1920x1080
            ../high/index.m3u8?token=abc
        """.trimIndent()

        assertEquals(
            "https://media.example.com/high/index.m3u8?token=abc",
            HlsVariantResolver.selectVariant(
                "https://media.example.com/path/master.m3u8",
                playlist,
            ),
        )
    }

    @Test
    fun ignoresMediaPlaylistWithoutVariants() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            segment-1.ts
        """.trimIndent()

        assertNull(
            HlsVariantResolver.selectVariant(
                "https://media.example.com/live/index.m3u8",
                playlist,
            ),
        )
    }
}
