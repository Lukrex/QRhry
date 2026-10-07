package com.qrhry.app.domain

data class Game(
    val id: Long,
    val title: String,
    val stations: List<Station>
)

data class Station(
    val id: Long,
    val gameId: Long,
    val title: String,
    val bodyText: String,
    val position: Int,
    val qrToken: String
)

data class GameDraft(
    val title: String,
    val stations: List<StationDraft>
)

data class StationDraft(
    val title: String,
    val bodyText: String
)

data class StationQrLookup(
    val gameTitle: String,
    val station: Station
)

enum class GameSessionStatus {
    IN_PROGRESS,
    COMPLETED
}

data class GameSession(
    val id: Long,
    val gameId: Long,
    val status: GameSessionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?
)

data class SessionProgress(
    val session: GameSession,
    val gameTitle: String,
    val stations: List<Station>,
    val visitedStationIds: Set<Long>,
    val nextStation: Station?
) {
    val completedStationCount: Int
        get() = visitedStationIds.size

    val totalStationCount: Int
        get() = stations.size
}

sealed interface StationScanResult {
    data class Accepted(val station: Station, val progress: SessionProgress) : StationScanResult
    data object UnknownQr : StationScanResult
    data class WrongGame(val station: Station) : StationScanResult
    data class OutOfOrder(val expectedStation: Station, val scannedStation: Station) : StationScanResult
    data class AlreadyVisited(val station: Station, val progress: SessionProgress) : StationScanResult
    data object SessionCompleted : StationScanResult
    data object SessionUnavailable : StationScanResult
}