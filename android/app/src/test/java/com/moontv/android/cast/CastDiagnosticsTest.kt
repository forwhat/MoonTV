package com.moontv.android.cast

import fi.iki.elonen.NanoHTTPD
import com.yinnho.upnpcast.internal.core.UpnpHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CastDiagnosticsTest {
    @Test
    fun reportHidesUrlSecretsAndIsBounded() {
        val report = CastDiagnostics()
        report.reset("Media=https://user:secret@media.example/a.m3u8?token=abc&key=def#secret")
        assertEquals("Media=https://media.example/a.m3u8?[hidden]",
            CastDiagnostics.redact("Media=https://user:secret@media.example/a.m3u8?token=abc&key=def#secret"))
        assertFalse(report.snapshot().contains("secret"))
        repeat(1000) { report.add("x".repeat(2000)) }
        assertTrue(report.snapshot().length < 62 * 1024)
        report.reset("new attempt")
        assertFalse(report.snapshot().contains("xxxx"))
    }

    @Test
    fun soapFaultIsRecordedWithoutChangingFailureResult() = runBlocking {
        val fault = "<s:Fault><detail><UPnPError><errorCode>714</errorCode>" +
            "<errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault>"
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                session.parseBody(HashMap())
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", fault)
            }
        }
        server.start()
        val events = mutableListOf<UpnpHttp.DiagnosticEvent>()
        UpnpHttp.diagnosticObserver = { events += it }
        try {
            assertNull(UpnpHttp.postSoap("http://127.0.0.1:${server.listeningPort}/control",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", "<test/>"))
            assertEquals(500, events.single().status)
            assertTrue(events.single().operation.endsWith("#SetAVTransportURI"))
            assertTrue(events.single().response.contains("714"))
            assertTrue(events.single().response.contains("Illegal MIME-type"))

            UpnpHttp.diagnosticObserver = { error("observer failure") }
            assertNull(UpnpHttp.postSoap("http://127.0.0.1:${server.listeningPort}/control", "Play", "<test/>"))
        } finally {
            UpnpHttp.diagnosticObserver = null
            server.stop()
        }
    }
}
