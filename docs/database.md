# Database

The app uses raw SQLite through `SQLiteOpenHelper`; database operations are
isolated in the data layer. The current schema version is 2.

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

Gameplay-session tables are not yet part of the schema; add them with a
migration when persisted play begins.
