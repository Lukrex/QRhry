package com.qrhry.app.qr

import java.net.URI
import java.util.UUID

sealed interface QrPayloadParseResult {
    data class Valid(val stationToken: String) : QrPayloadParseResult
    data object Malformed : QrPayloadParseResult
}

object StationQrPayload {
    private val tokenPattern = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    )

    fun create(stationToken: String): String {
        require(isCanonicalToken(stationToken)) { "Station token must be a canonical UUID." }
        return "qrhry://station/v1/$stationToken"
    }

    fun parse(payload: String?): QrPayloadParseResult {
        if (payload == null) return QrPayloadParseResult.Malformed
        return try {
            val uri = URI(payload)
            val segments = uri.path?.split('/')?.filter(String::isNotEmpty).orEmpty()
            if (
                uri.scheme != "qrhry" ||
                uri.host != "station" ||
                uri.rawUserInfo != null ||
                uri.port != -1 ||
                uri.query != null ||
                uri.fragment != null ||
                segments.size != 2 ||
                segments[0] != "v1" ||
                !isCanonicalToken(segments[1])
            ) {
                QrPayloadParseResult.Malformed
            } else {
                QrPayloadParseResult.Valid(segments[1])
            }
        } catch (_: Exception) {
            QrPayloadParseResult.Malformed
        }
    }

    private fun isCanonicalToken(token: String): Boolean =
        tokenPattern.matches(token) && runCatching { UUID.fromString(token) }.isSuccess
}