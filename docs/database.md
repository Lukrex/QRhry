# Database

The app uses raw SQLite through `SQLiteOpenHelper`; database operations are
isolated in the data layer. The current schema version is 10.

## Core tables

### `games`

`id INTEGER PRIMARY KEY AUTOINCREMENT`, nonblank `title`, unique stable
`game_uuid`, `content_version`, `updated_at`, and `scoring_mode` constrained to
`EVENTUAL_CORRECT` or `FIRST_TRY_ONLY` (default `EVENTUAL_CORRECT`).

### `stations`

`id INTEGER PRIMARY KEY AUTOINCREMENT`, `game_id` foreign key to `games` with
delete cascade, nonblank `title`, `body_text`, nonnegative `position`, and
unique `qr_token`. `UNIQUE(game_id, position)` preserves linear order.

### `game_routes` and `station_transitions`

Games without a `game_routes` row remain linear and use `stations.position`.
A route row identifies a branched game's starting station. Each transition is
keyed by source station and `condition` (`CORRECT`, `INCORRECT`, or `INFO`). A
null `target_station_id` is an explicit end of the game; a missing transition
is invalid. Source and target stations use foreign keys. Route writes are
transactional and the repository validates required outcomes, reachability,
and cycles before saving. Branched task stations have one task; information
stations have none.

### `station_media`

Station-owned metadata for images/audio: UUID `id`, `station_id` with cascade,
`media_type`, unique game-relative `relative_path`, MIME type, optional original
filename, checksum, byte size, and display order. Binaries remain under
`filesDir/games/<game_uuid>/`; the database stores no absolute paths or picker
URIs.

## Session and visit lifecycle

### `game_sessions`

`id`, `game_id`, `status` (`IN_PROGRESS`/`COMPLETED`), `created_at`, `updated_at`,
nullable `completed_at`, nullable `total_task_count` / `total_station_count`
snapshots, a `scoring_mode` snapshot, and nullable `expected_station_id` route
cursor. New sessions capture totals and the
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
and progression. Branched sessions compare scans against the route cursor;
linear sessions retain position-based behavior.

## Task tables

### `tasks`

UUID `id` primary key, `station_id` foreign key to `stations` with cascade,
`type` constrained to `MULTIPLE_CHOICE`, `TRUE_FALSE`, or `SHORT_TEXT`, nonblank `prompt`, nonnegative
`position`, nonnegative integer `points` (default 100), and
`UNIQUE(station_id, position)`. Stations can have zero or more ordered tasks
even though the initial editor exposes at most one. `time_limit_seconds` is
nullable or constrained to 10–1800 seconds.

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
`is_timed_out = 1` marks a zero-point finalized timeout, distinct from a player
choosing to continue after a wrong answer.

### `task_timers`

One row per active `(session_id, task_id)` stores `activated_at` and an absolute
epoch-millisecond `deadline_at`. It is created once when the player first sees
the task, survives process/device restart, and is removed atomically when an
answer or timeout completes the task. The repository checks the same stored
deadline in the submission transaction. The device wall clock is the source
across reboots, so changing it while the app is running or stopped can shift
the deadline.

Task completion is derived from either a submitted correct attempt or a
finalized incorrect attempt. Station completion is stored on
`station_visits.completed_at` and occurs on scan for taskless stations, or after
all station tasks are correct or explicitly finalized. The first
non-completed station by `position` is the linear progression cursor. Branched
sessions store their cursor in `game_sessions.expected_station_id`. Selecting
the next edge and completing the station happen in one transaction.

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
- **v8→v9** adds the nullable session route cursor and creates empty route
  configuration/transition tables. Existing games have no route row and remain
  linear; sessions, station order, attempts, and scores are not rewritten.
- **v9→v10** adds optional bounded task time limits, a default-false timeout
  marker to attempts, and active `task_timers`. Existing tasks remain untimed
  and existing attempts remain ordinary attempts.
