# Architecture

## Current implementation

The app is a single Android module using Kotlin and Jetpack Compose. Its current
local flow is:

`Compose screen -> GameViewModel -> GameRepository -> GameDatabaseHelper -> SQLite`

- `domain` contains game/station models, drafts, and input validation without
  Android or database dependencies.
- `data.local.GameDatabaseHelper` owns the SQLite schema, foreign-key
  configuration, and schema-version handling.
- `data.GameRepository` performs parameterized reads and writes. Saving a game
  and all of its stations is one transaction.
- `ui.GameViewModel` coordinates screen state and runs repository operations on
  `Dispatchers.IO`.
- `ui.GameScreen` collects state and renders game creation, QR results, and
  station details; it does not access SQLite directly.
- `qr.StationQrPayload` owns the versioned URI format and strict parser.
- `qr.StationQrCodeGenerator` uses ZXing to render each station's persisted
  token as a QR bitmap.
- `MainActivity` invokes Google Play Services Code Scanner and passes its result
  to the ViewModel. This avoids camera permission and camera lifecycle code in
  the app; scanning requires compatible Google Play Services on the device.
- `GameRepository` owns session creation/resumption and validates/records station
  scans transactionally. The next station is derived from `position` and the
  session's persisted visits; no next-station link is stored on `Station`.

Stations are stored in user-defined order and receive a stable UUID token when
created. Create/Edit and Play are separate top-level routes. Play restores active
sessions from SQLite and offers Continue for an unfinished game. In the current
prototype an accepted scan completes a station visit; reaching the final ordered
station marks the persisted session complete. Completed sessions are retained.

## Extension direction

Keep game data independent of the engine and UI. Future media and task content
should have typed, ordered representations instead of accumulating unrelated
nullable columns on `stations`. If task completion later differs from scanning,
model that lifecycle separately from `StationVisit`; add scoring and achievement
events when those features are scoped. Branching should use explicit transition
data or rules rather than a `next_station_id` on a station.
