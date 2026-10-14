# Architecture

## Current implementation

The app is a single Android module using Kotlin and Jetpack Compose. The local
flow is:

`Compose screen -> GameViewModel -> GameRepository -> GameDatabaseHelper -> SQLite`

- `domain` contains game, station, typed task, option, visit, attempt,
  drafts, and validation models without Android or database dependencies.
- `data.local.GameDatabaseHelper` owns schema version 8, foreign keys, indexes,
  and sequential, non-destructive migrations.
- `data.GameRepository` owns parameterized reads and transactional writes for
  game/station/task content, session progression, and scoring. It validates QR
  order, session/game/task ownership, answer submissions, and completion.
- `data.archive.GameArchiveService` coordinates SAF import/export, while
  `GameArchiveCodec` owns the versioned ZIP/JSON format and bounded validation.
  `GameRepository` supplies consistent relational snapshots and transactional
  imports; `GameAssetStore` verifies and promotes media directories.
- `ui.GameViewModel` coordinates screen state and runs repository work on
  `Dispatchers.IO`; Compose does not decide progression or write persistence.
- `ui.GameScreen` renders game/station editing, QR scanning, station media, and
  multiple-choice, true/false, and short-text authoring and play.
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
- **Task completed**: a submitted correct attempt or an explicitly finalized
  incorrect attempt exists for the session and task. Pending selections and
  ordinary incorrect submissions remain retryable and do not count.
- **Points awarded**: a successful attempt stores the task's configured point
  value in `task_attempts.awarded_points` in the answer-submission transaction.
  Each game selects `EVENTUAL_CORRECT` (default) or `FIRST_TRY_ONLY`; the chosen
  mode is snapshotted on each session. Incorrect attempts store zero. Explicitly
  continuing after a wrong answer finalizes that wrong attempt for progression,
  not correctness or points. Session score is derived from persisted awards,
  not current task definitions.
- **Station completed**: `station_visits.completed_at` is non-null. A taskless
  station completes on scan; otherwise all its tasks need a correct attempt.

The repository resumes an incomplete visited station and accepts no later
station until it is complete. A station completes when every task is solved or
explicitly finalized after a wrong answer. Linear `stations.position`
determines order; no `next_station_id` or branching rule is stored. Session
progress and pending answer selection are reconstructed from SQLite after
restart. Incorrect answers remain retryable until the player continues. New
sessions snapshot scoring mode and task/station totals at start. Completed runs
can be reopened from Play without starting another session. V5 attempts migrate
with zero awarded points, and completed legacy sessions display their score and
historical totals as unavailable because scoring was not recorded for those
runs.

## Content editing and future scope

Station/task/option saves are transactional. Task configuration cannot change
while its game has an active session. A game's scoring mode may be edited, but
active and completed runs keep their session snapshot. Attempt rows snapshot the
prompt, selected answer text, correctness, awarded points, finalization state,
and timestamps; selected option IDs are not foreign keys, so option changes do
not erase historical answers. A task with attempts is not deleted. No timers,
hints, achievements, networking, additional task types, or branching are
implemented. Future task types should use typed relational data rather than
nullable columns on `stations` or opaque JSON.

## Portable game archives

The `.qrhry` v2 format is a content-only ZIP with a JSON manifest and generated
asset aliases. It excludes sessions, visits, attempts, and results. Import
creates fresh local identities, including new station QR tokens, so it creates a
new QR set rather than reusing a source game's printed codes. Archive parsing
is bounded and never extracts using untrusted ZIP paths. Media are staged and
verified before a repository transaction inserts the game. The archive format,
limits, and failure compensation are specified in `game-archive-format.md`;
startup recovery removes staging/orphan directories older than 24 hours, so a
recent in-progress import is not mistaken for abandoned work. SQLite remains at
schema v7.
