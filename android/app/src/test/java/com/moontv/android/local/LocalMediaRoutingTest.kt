package com.moontv.android.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaRoutingTest {
    @Test
    fun `media GET requests stay on the device`() {
        assertTrue(isLocalMediaRequest("GET", "/api/m3u8"))
        assertTrue(isLocalMediaRequest("GET", "/api/m3u8/proxy"))
        assertTrue(isLocalMediaRequest("GET", "/api/image-proxy"))
    }

    @Test
    fun `business APIs and non media methods may use upstream server`() {
        assertFalse(isLocalMediaRequest("GET", "/api/search"))
        assertFalse(isLocalMediaRequest("GET", "/api/douban/categories"))
        assertFalse(isLocalMediaRequest("POST", "/api/m3u8"))
    }
}
