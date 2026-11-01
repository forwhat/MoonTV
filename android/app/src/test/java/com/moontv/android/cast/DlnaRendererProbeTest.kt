package com.moontv.android.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaRendererProbeTest {
    @Test
    fun recognizesToshibaMediaRendererWithoutTvInManufacturerOrModel() {
        val description = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>客厅电视</friendlyName>
                <manufacturer>TOSHIBA</manufacturer>
                <modelName>55M545F</modelName>
              </device>
            </root>
        """.trimIndent()

        assertTrue(DlnaRendererProbe.hasRendererCapability(description))
    }

    @Test
    fun recognizesRendererByAvTransportService() {
        val description = """
            <service>
              <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            </service>
        """.trimIndent()

        assertTrue(DlnaRendererProbe.hasRendererCapability(description))
    }

    @Test
    fun rejectsMediaServerWithoutRendererCapability() {
        val description = """
            <device>
              <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
              <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
            </device>
        """.trimIndent()

        assertFalse(DlnaRendererProbe.hasRendererCapability(description))
    }
}
