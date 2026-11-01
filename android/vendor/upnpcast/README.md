# UPnPCast source snapshot

Source: https://github.com/yinnho/UPnPCast, tag v1.3.0,
commit `362ec62601c02c7c931ba55bdde98491990ab9d0` (MIT; see LICENSE).

The app compiles this snapshot instead of the binary dependency so that
`internal/core/UpnpHttp.kt` can report bounded device-description and SOAP
response details to MoonTV. SOAP envelopes are assembled without indented
multiline interpolation: upstream `trimIndent()` leaves whitespace before
the XML declaration when actions contain multiple lines. Requests also send
an explicit UTF-8 byte Content-Length. Success criteria and retry behavior
remain upstream behavior. The optional observer must never affect
request results and is installed only during a cast attempt.
