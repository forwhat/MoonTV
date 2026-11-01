package com.moontv.android.cast

import com.yinnho.upnpcast.internal.core.UpnpHttp
import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.ServiceInfo
import com.yinnho.upnpcast.internal.media.DlnaMediaController
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class SoapEnvelopeTest {
    @Test
    fun strictRendererAcceptsRealSetUriPlayStopAndStateRequests() = runBlocking {
        val operations = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                return try {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val payload = files["postData"].orEmpty()
                    check(payload.startsWith("<?xml")) { "XML declaration has leading whitespace" }
                    check(session.headers["content-length"]?.toInt() == payload.toByteArray(Charsets.UTF_8).size)
                    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(payload)))
                    val body = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body").item(0)
                    val action = (0 until body.childNodes.length).map { body.childNodes.item(it) }
                        .first { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
                    check(action.namespaceURI == "urn:schemas-upnp-org:service:AVTransport:1")
                    check(session.headers["soapaction"] == "\"${action.namespaceURI}#${action.localName}\"")
                    operations += action.localName
                    if (action.localName == "SetAVTransportURI") {
                        val mediaUri = doc.getElementsByTagName("CurrentURI").item(0).textContent
                        check(mediaUri == "https://media.example/video.m3u8?a=1&b=2")
                        val metadata = doc.getElementsByTagName("CurrentURIMetaData").item(0).textContent
                        val didl = factory.newDocumentBuilder().parse(InputSource(StringReader(metadata)))
                        check(didl.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title")
                            .item(0).textContent == "小城良方 & 第1集")
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/xml",
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                            "<u:${action.localName}Response xmlns:u=\"${action.namespaceURI}\">" +
                            "<CurrentTransportState>PLAYING</CurrentTransportState>" +
                            "</u:${action.localName}Response></s:Body></s:Envelope>")
                } catch (error: Exception) {
                    rejected += error.toString()
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", "Invalid Action")
                }
            }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.listeningPort}"
        val controller = DlnaMediaController(RemoteDevice(
            id = "$endpoint/MediaServer/rendererdevicedesc.xml", displayName = "Strict test renderer",
            address = "127.0.0.1", locationUrl = "$endpoint/MediaServer/rendererdevicedesc.xml",
            services = listOf(ServiceInfo("urn:schemas-upnp-org:service:AVTransport:1", "AVTransport",
                "/upnp/control/mingusavtr", "", "")),
        ))
        try {
            assertTrue(controller.playMediaDirect("https://media.example/video.m3u8?a=1&b=2", "小城良方 & 第1集"))
            assertTrue(controller.control("stop"))
            assertEquals("PLAYING", controller.getTransportInfo())
            assertEquals(listOf("SetAVTransportURI", "Play", "Stop", "GetTransportInfo"), operations)
            assertTrue(rejected.toString(), rejected.isEmpty())
        } finally {
            controller.release()
            server.stop()
        }
    }

    @Test
    fun multilineActionHasXmlDeclarationAtByteZeroAndParsesAsSoap() {
        // Same multiline insertion as DlnaMediaController, not the one-line
        // <test/> body used by the HTTP diagnostics test.
        val action = """
            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent()
        val envelope = UpnpHttp.soapEnvelope(action)
        assertTrue("XML declaration must start at byte zero; prefix=${envelope.take(24)}",
            envelope.startsWith("<?xml"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(envelope)))
        assertEquals("http://schemas.xmlsoap.org/soap/envelope/", document.documentElement.namespaceURI)
        assertEquals(1, document.getElementsByTagNameNS("urn:schemas-upnp-org:service:AVTransport:1", "Play").length)
    }
}
