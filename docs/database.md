# Database

The app uses raw SQLite through `SQLiteOpenHelper`; database operations are
isolated in the data layer. The current schema version is 8.

## Core tables

### `games`

`id INTEGER PRIMARY KEY AUTOINCREMENT`, nonblank `title`, unique stable
`game_uuid`, `content_version`, `updated_at`, and `scoring_mode` constrained to
`EVENTUAL_CORRECT` or `FIRST_TRY_ONLY` (default `EVENTUAL_CORRECT`).

### `stations`

`id INTEGER PRIMARY KEY AUTOINCREMENT`, `game_id` foreign key to `games` with
delete cascade, nonblank `title`, `body_text`, nonnegative `position`, and
unique `qr_token`. `UNIQUE(game_id, position)` preserves linear order.

### `station_media`

Station-owned metadata for images/audio: UUID `id`, `station_id` with cascade,
`media_type`, unique game-relative `relative_path`, MIME type, optional original
filename, checksum, byte size, and display order. Binaries remain under
`filesDir/games/<game_uuid>/`; the database stores no absolute paths or picker
URIs.

## Session and visit lifecycle

### `game_sessions`

`id`, `game_id`, `status` (`IN_PROGRESS`/`COMPLETED`), `created_at`, `updated_at`,
nullable `completed_at`, and nullable `total_task_count` / `total_station_count`
snapshots, plus a `scoring_mode` snapshot. New sessions capture totals and the
game's current scoring mode at start; later policy edits affect future runs
only. V5 active sessions are
backfilled during migration; completed legacy sessions keep null totals because
later task edits make their historical denominators unrecoverable. Their score
is displayed as not recorded; the migration does not infer rewards from old
correct attempts. A partial unique index permits at most one active session per
game while retaining runs.

### `station_visits`

`session_id`, `station_id`, `visited_at`, and nullable `completed_at`; primary
key `(session_id, station_id)`. **A visit row means scanned/visited, not
completed.** A non-null `completed_at` means the station is complete. The table
does not duplicate `game_id`; repository transactions validate game ownership
and ordered progression.

## Task tables

### `tasks`

UUID `id` primary key, `station_id` foreign key to `stations` with cascade,
`type` constrained to `MULTIPLE_CHOICE`, `TRUE_FALSE`, or `SHORT_TEXT`, nonblank `prompt`, nonnegative
`position`, nonnegative integer `points` (default 100), and
`UNIQUE(station_id, position)`. Stations can have zero or more ordered tasks
even though the initial editor exposes at most one.

### `task_options`

UUID `id` primary key, `task_id` foreign key to `tasks` with cascade, nonblank
`text`, nonnegative `position`, `is_correct` constrained to 0/1, and
`UNIQUE(task_id, position)`. A partial unique index enforces at most one correct
option per task. Repository validation requires at least two options and
exactly one correct option.

### `task_attempts`

UUID `id`, `session_id` foreign key to `game_sessions` with cascade, `task_id`
foreign key to `tasks` with restrict, `selected_option_id` (nullable and intentionally not a foreign key; null for true/false and short-text answers), selected-option and prompt text snapshots, nullable `correctness`,
`selected_at`, nullable `submitted_at`, nonnegative `awarded_points` (default
0), and `is_finalized` constrained to 0/1 (default 0). A null `submitted_at`
denotes the persisted pending answer. True/false uses the answer snapshot tokens `TRUE`/`FALSE`; short text stores submitted text in the answer snapshot and is evaluated on submit. A check
constraint requires pending rows to have null correctness and submitted rows to
have a correctness value. A partial unique index allows at most one pending row
per `(session_id, task_id)`; submitted wrong and correct attempts are retained.
Correctness and awarded points are committed atomically. Wrong answers award
zero; a successful task stores its configured points once, subject to the
session's scoring mode. `is_finalized = 1` marks the last incorrect submission
when the player explicitly continues without solving the task. This resolves
the task for progression but never makes it correct or awards points. Score is
summed from immutable awards rather than recalculated from mutable definitions.

Task completion is derived from either a submitted correct attempt or a
finalized incorrect attempt. Station completion is stored on
`station_visits.completed_at` and occurs on scan for taskless stations, or after
all station tasks are correct or explicitly finalized. The first
non-completed station by `position` is the progression cursor.

## Transactions and migrations

Foreign keys are enabled on every connection. Creating/editing station tasks and
options, accepting a scan, saving a pending selection, submitting an answer,
completing a station, and completing the final session are repository-owned
transactions. `onUpgrade` applies sequential migrations and fails explicitly if
a path is undefined; it never silently drops user data.

- **v1→v2** rebuilds `stations` to require unique QR UUIDs while preserving
  station/game IDs, text, and position.
- **v2→v3** adds `game_sessions` and the original visit table; no existing game
  or station rows are rebuilt.
- **v3→v4** adds game UUID/version metadata and `station_media`.
- **v4→v5** adds nullable `station_visits.completed_at`, backfills it from
  `visited_at` because v4 scans completed stations, and creates `tasks`,
  `task_options`, `task_attempts`, and their indexes. It does not rewrite games,
  stations, QR UUIDs, sessions, media metadata, or media files.
- **v5→v6** adds task points (default 100), attempt awards (default zero), and
  nullable session task/station totals. It backfills totals only for active
  sessions and does not rewrite or delete existing rows. Existing attempts
  receive zero points so pre-scoring answers are never retroactively rewarded.
- **v6→v7** adds game scoring modes, snapshots each mode to existing sessions
  as `EVENTUAL_CORRECT`, and adds `task_attempts.is_finalized` (default zero).
  Existing points and awards are preserved; prior incorrect answers remain
  retryable and prior correct answers remain completed.

- **v7→v8** rebuilds tasks, options, and attempts to accept the two new task types and nullable `selected_option_id`. It copies task/option/attempt IDs, snapshots, timestamps, correctness, awards, and finalization flags, retaining the existing indexes and constraints. Existing task types remain `MULTIPLE_CHOICE`. The v2→v3
migration deliberately creates the historical v3 visit shape so the following
v4→v5 step can add `completed_at` exactly once before v5→v6 adds task points
and v6→v7 adds scoring policies and explicit task finalization.
