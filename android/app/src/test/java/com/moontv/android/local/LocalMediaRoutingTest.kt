package com.moontv.android.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `json request bodies are decoded as utf8`() {
        assertEquals(
            "application/json; charset=UTF-8",
            requestBodyContentType("application/json"),
        )
        assertEquals(
            "application/problem+json; charset=UTF-8",
            requestBodyContentType("application/problem+json"),
        )
        assertEquals(
            "application/json; charset=utf-8",
            requestBodyContentType("application/json; charset=utf-8"),
        )
        assertEquals(
            "application/x-www-form-urlencoded",
            requestBodyContentType("application/x-www-form-urlencoded"),
        )
        assertNull(requestBodyContentType(null))
    }

    @Test
    fun `only explicit streaming searches bypass the generic call timeout`() {
        assertTrue(isStreamingSearchRequest("/api/search", "1"))
        assertFalse(isStreamingSearchRequest("/api/search", null))
        assertFalse(isStreamingSearchRequest("/api/search", ""))
        assertFalse(isStreamingSearchRequest("/api/search", "0"))
        assertFalse(isStreamingSearchRequest("/api/detail", "1"))
    }

    @Test
    fun `overall search timeout covers all concurrency batches`() {
        assertEquals(35L, searchOverallTimeoutSeconds(6, 30))
        assertEquals(65L, searchOverallTimeoutSeconds(7, 30))
        assertEquals(300L, searchOverallTimeoutSeconds(60, 60))
    }
}
