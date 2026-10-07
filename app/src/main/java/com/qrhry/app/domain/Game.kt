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