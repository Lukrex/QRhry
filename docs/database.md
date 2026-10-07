# Database

The app uses raw SQLite through `SQLiteOpenHelper`; database operations are
isolated in the data layer. The current schema version is 5.

## Core tables

### `games`

`id INTEGER PRIMARY KEY AUTOINCREMENT`, nonblank `title`, unique stable
`game_uuid`, `content_version`, and `updated_at`.

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
and nullable `completed_at`. A partial unique index permits at most one active
session per game while retaining completed runs.

### `station_visits`

`session_id`, `station_id`, `visited_at`, and nullable `completed_at`; primary
key `(session_id, station_id)`. **A visit row means scanned/visited, not
completed.** A non-null `completed_at` means the station is complete. The table
does not duplicate `game_id`; repository transactions validate game ownership
and ordered progression.

## Task tables

### `tasks`

UUID `id` primary key, `station_id` foreign key to `stations` with cascade,
`type` constrained to `MULTIPLE_CHOICE`, nonblank `prompt`, nonnegative
`position`, and `UNIQUE(station_id, position)`. Stations can have zero or more
ordered tasks even though the initial editor exposes at most one.

### `task_options`

UUID `id` primary key, `task_id` foreign key to `tasks` with cascade, nonblank
`text`, nonnegative `position`, `is_correct` constrained to 0/1, and
`UNIQUE(task_id, position)`. A partial unique index enforces at most one correct
option per task. Repository validation requires at least two options and
exactly one correct option.

### `task_attempts`

UUID `id`, `session_id` foreign key to `game_sessions` with cascade, `task_id`
foreign key to `tasks` with restrict, `selected_option_id` (intentionally not a
foreign key), selected-option and prompt text snapshots, nullable `correctness`,
`selected_at`, and nullable `submitted_at`. A null `submitted_at` denotes the
persisted pending selection. A check constraint requires pending rows to have
null correctness and submitted rows to have a correctness value. A partial
unique index allows at most one pending row per `(session_id, task_id)`;
submitted wrong and correct attempts are retained.

Task completion is derived from a submitted correct attempt. Station completion
is stored on `station_visits.completed_at` and occurs on scan for taskless
stations, or after all station tasks have correct attempts. The first
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

Fresh databases are created directly with the complete v5 schema. The v2→v3
migration deliberately creates the historical v3 visit shape so the following
v4→v5 step can add `completed_at` exactly once.
