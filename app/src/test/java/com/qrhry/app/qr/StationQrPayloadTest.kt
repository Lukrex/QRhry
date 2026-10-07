package com.qrhry.app.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class StationQrPayloadTest {
    private val token = "9d05c4bd-82ad-4515-b5c2-435a92100001"

    @Test
    fun createsAndParsesVersionedStationUri() {
        val payload = StationQrPayload.create(token)

        assertEquals("qrhry://station/v1/$token", payload)
        assertEquals(QrPayloadParseResult.Valid(token), StationQrPayload.parse(payload))
    }

    @Test
    fun rejectsMalformedOrUnsupportedPayloads() {
        listOf(
            null,
            "not a URI",
            "https://station/v1/$token",
            "qrhry://station/v2/$token",
            "qrhry://station/v1/not-a-uuid",
            "qrhry://station/v1/$token?extra=value",
            "qrhry://station/v1/$token/extra"
        ).forEach { payload ->
            assertEquals(QrPayloadParseResult.Malformed, StationQrPayload.parse(payload))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotGeneratePayloadForNonCanonicalToken() {
        StationQrPayload.create("not-a-uuid")
    }
}