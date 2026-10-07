# Database

The app uses raw SQLite through `SQLiteOpenHelper`; database operations are
isolated in the data layer. The current schema version is 3.

## Tables

### `games`

| Column  | Definition                          |
| ------- | ----------------------------------- |
| `id`    | `INTEGER PRIMARY KEY AUTOINCREMENT` |
| `title` | `TEXT NOT NULL`, non-blank check    |

### `stations`

| Column      | Definition                                          |
| ----------- | --------------------------------------------------- |
| `id`        | `INTEGER PRIMARY KEY AUTOINCREMENT`                 |
| `game_id`   | Required foreign key to `games.id`, delete cascades |
| `title`     | `TEXT NOT NULL`, non-blank check                    |
| `body_text` | `TEXT NOT NULL DEFAULT ''`                          |
| `position`  | Non-negative integer, unique per game               |
| `qr_token`  | Unique, non-null canonical UUID                     |

`index_stations_game_id` supports loading stations for a game. Repository
queries return stations ordered by `position`.

## Transactions and migrations

Creating a game and its stations happens in one transaction; partial games are
not committed. Foreign-key enforcement is enabled on each database connection.
`onCreate` declares the complete schema. `onUpgrade` fails explicitly when no
migration path is defined rather than silently dropping user data. Increment
`DATABASE_VERSION` only together with explicit, sequential migration steps and
tests for each supported prior version.

The explicit v1-to-v2 migration rebuilds `stations` to make `qr_token` required
and unique, preserving station/game IDs, text, and position while assigning a
UUID to every existing station. New station UUIDs are generated during the
repository's create-game transaction.

## Gameplay sessions (version 3)

### `game_sessions`

| Column         | Definition                                          |
| -------------- | --------------------------------------------------- |
| `id`           | `INTEGER PRIMARY KEY AUTOINCREMENT`                 |
| `game_id`      | Required foreign key to `games.id`, delete cascades |
| `status`       | `IN_PROGRESS` or `COMPLETED`                        |
| `created_at`   | Required Unix epoch milliseconds                    |
| `updated_at`   | Required Unix epoch milliseconds                    |
| `completed_at` | Nullable Unix epoch milliseconds                    |

`index_game_sessions_one_active_per_game` is a partial unique index on
`game_id` for `IN_PROGRESS` rows. It enforces at most one unfinished session
per game while retaining any number of completed runs.

### `station_visits`

| Column       | Definition                                                  |
| ------------ | ----------------------------------------------------------- |
| `session_id` | Required foreign key to `game_sessions.id`, delete cascades |
| `station_id` | Required foreign key to `stations.id`, delete cascades      |
| `visited_at` | Required Unix epoch milliseconds                            |

The composite primary key `(session_id, station_id)` makes visits idempotent.
There is intentionally no duplicate `game_id` here. The repository checks the
session's game against the scanned station inside the same write transaction
that validates order and records the visit.

The v2-to-v3 migration only creates the session tables and indexes; it does not
rebuild or rewrite existing games, stations, or QR UUIDs. Upgrades from v1 run
the existing v1-to-v2 migration followed by v2-to-v3.
