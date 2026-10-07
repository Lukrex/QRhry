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

Stations are stored in user-defined order and receive a stable UUID token when
created. Scanned tokens are resolved in SQLite before the station detail screen
is shown. Unknown identifiers and malformed payloads have distinct results.
Play sessions and ordered gameplay progression are not implemented yet.

## Extension direction

Keep game data independent of the engine and UI. Add progression as a
domain/repository operation rather than embedding station IDs or ordering rules
in Compose. Future media and task content should have typed, ordered
representations instead of accumulating unrelated nullable columns on `stations`.
