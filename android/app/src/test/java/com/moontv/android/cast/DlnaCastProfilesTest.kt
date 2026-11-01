package com.moontv.android.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaCastProfilesTest {
    @Test
    fun hlsUsesStandardAlternateAndEmptyMetadataProfiles() {
        val profiles = DlnaCastProfiles.forUrl("https://media.example/live/master.m3u8?token=secret")

        assertEquals(
            listOf("standard", "hls-alternate-mime", "empty-metadata"),
            profiles.map { it.name },
        )
        assertEquals("application/vnd.apple.mpegurl", profiles[0].mimeType)
        assertEquals("application/x-mpegURL", profiles[1].mimeType)
        assertTrue(profiles[2].emptyMetadata)
    }

    @Test
    fun directVideoUsesDeclaredMimeThenEmptyMetadata() {
        val profiles = DlnaCastProfiles.forUrl("https://media.example/movie.MP4#play")

        assertEquals(listOf("standard", "empty-metadata"), profiles.map { it.name })
        assertEquals("video/mp4", profiles[0].mimeType)
        assertFalse(profiles[0].emptyMetadata)
        assertTrue(profiles[1].emptyMetadata)
    }

    @Test
    fun extensionlessUrlDoesNotInventMimeType() {
        val profiles = DlnaCastProfiles.forUrl("https://media.example/play?id=123")

        assertEquals(listOf("standard", "empty-metadata"), profiles.map { it.name })
        assertNull(profiles[0].mimeType)
    }
}
