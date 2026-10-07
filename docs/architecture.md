# Architecture

## Current implementation

The app is a single Android module using Kotlin and Jetpack Compose. The local
flow is:

`Compose screen -> GameViewModel -> GameRepository -> GameDatabaseHelper -> SQLite`

- `domain` contains game, station, multiple-choice task, option, visit, attempt,
  drafts, and validation models without Android or database dependencies.
- `data.local.GameDatabaseHelper` owns schema version 5, foreign keys, indexes,
  and sequential, non-destructive migrations.
- `data.GameRepository` owns parameterized reads and transactional writes for
  game/station/task content and session progression. It validates QR order,
  session/game/task ownership, answer submissions, and completion.
- `ui.GameViewModel` coordinates screen state and runs repository work on
  `Dispatchers.IO`; Compose does not decide progression or write persistence.
- `ui.GameScreen` renders game/station editing, QR scanning, station media, and
  multiple-choice authoring and play.
- Station images/audio are copied into app-private storage by `GameAssetStore`;
  SQLite stores their metadata and relative paths.
- `qr.StationQrPayload` owns the versioned URI format and strict parser.
  `StationQrCodeGenerator` uses ZXing. `MainActivity` invokes Google Play
  Services Code Scanner and passes results to the ViewModel.

## Task and session lifecycle

Task content is relational and ordered: a station has zero or more tasks; the
first supported task type is `MULTIPLE_CHOICE`, with ordered options. Tasks and
options have stable UUIDs. The editor initially exposes no task or one task per
station, while the model and tables support multiple ordered tasks.

Keep these states distinct:

- **Visited**: `station_visits` row exists after a valid expected QR scan.
- **Task completed**: a submitted correct `task_attempts` row exists for the
  session and task. Pending selections and incorrect submissions do not count.
- **Station completed**: `station_visits.completed_at` is non-null. A taskless
  station completes on scan; otherwise all its tasks need a correct attempt.

The repository resumes an incomplete visited station and accepts no later
station until it is complete. Linear `stations.position` determines order; no
`next_station_id` or branching rule is stored. Session progress and pending
answer selection are reconstructed from SQLite after restart. Incorrect answers
are retained and may be retried indefinitely. Completed sessions remain history.

## Content editing and future scope

Station/task/option saves are transactional. Task configuration cannot change
while its game has an active session. Attempt rows snapshot the prompt, selected
answer text, correctness, and timestamps; selected option IDs are not foreign
keys, so option changes do not erase historical answers. A task with attempts is
not deleted. No scoring, timers, hints, achievements, networking, additional
task types, or branching are implemented. Future task types should use typed
relational data rather than nullable columns on `stations` or opaque JSON.
