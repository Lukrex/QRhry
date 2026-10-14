# `.qrhry` Archive Format

## Version 2

A `.qrhry` file is a ZIP archive. Its format version is independent of SQLite
`DATABASE_VERSION` and the game's `content_version`.

The archive contains exactly one `manifest.json` and the media files referenced
by that manifest. Asset entry names are generated archive aliases; they are not
filesystem paths and do not contain original filenames.

```text
manifest.json
assets/550e8400-e29b-41d4-a716-446655440000.bin
assets/6ba7b810-9dad-11d1-80b4-00c04fd430c8.bin
```

Example manifest shape:

```json
{
  "format": "qrhry",
  "formatVersion": 2,
  "title": "School trail",
  "scoringMode": "FIRST_TRY_ONLY",
  "stations": [
    {
      "title": "Courtyard",
      "bodyText": "Look around the courtyard.",
      "position": 0,
      "media": [
        {
          "entryPath": "assets/550e8400-e29b-41d4-a716-446655440000.bin",
          "mediaType": "IMAGE",
          "mimeType": "image/png",
          "originalFilename": "courtyard.png",
          "byteSize": 8192,
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
      ],
      "tasks": [
        {
          "prompt": "Which building is nearby?",
          "position": 0,
          "points": 150,
          "type": "MULTIPLE_CHOICE",
          "options": [
            { "text": "Library", "position": 0, "isCorrect": true },
            { "text": "Gym", "position": 1, "isCorrect": false }
          ]
        }
      ]
    },
    {
      "title": "Finish",
      "bodyText": "",
      "position": 1,
      "media": [],
      "tasks": []
    }
  ]
}
```

`sha256` is lowercase hexadecimal over the exact uncompressed media bytes.
`byteSize` is the exact uncompressed size. `entryPath` must match the generated
`assets/<canonical-lowercase-uuid>.bin` pattern and occur exactly once in the
ZIP.

## Content And Identity

The manifest contains authored reusable game content only: title, scoring mode,
station order/text/media, task type, order, prompts, and points; multiple-choice options with correctness; true/false correct value; or a short-text correct answer. Version 1 has no game description field because the
current app model has none.

The archive deliberately omits database integer IDs, source game/station/task/
option/media UUIDs, source station QR tokens, absolute paths, local availability
flags, sessions, visits, attempts, scores, and completed-run history. Every
import creates a new game UUID, station QR UUIDs, task/option/media IDs, and
media paths. Thus repeated imports create independent copies; old printed QR
codes continue to identify only the original game, so QR sheets must be
regenerated for each imported copy.

## Validation And Limits

The reader accepts only `format = "qrhry"` and versions 1 and 2. Version 1 tasks are interpreted as `MULTIPLE_CHOICE`; version 2 tasks require an explicit type and exactly the fields for that type. Unknown types and malformed type-specific data are rejected. It rejects
unknown/missing JSON keys, incorrect JSON types, malformed UTF-8/JSON, invalid
ordering, blank required text, negative points, invalid scoring modes, fewer
than two stations, invalid option sets, duplicate media types per station,
invalid MIME/type pairs, duplicate or unreferenced ZIP entries, missing assets,
and byte-size/SHA-256 mismatches.

Limits are enforced against streamed bytes rather than trusted ZIP metadata:

- ZIP file: 100 MiB maximum.
- Manifest: 1 MiB maximum.
- ZIP entries: 128 maximum, including the manifest.
- One uncompressed media entry: 50 MiB maximum.
- Total uncompressed contents: 250 MiB maximum.
- Stations: 64 maximum; tasks per station: 32; options per task: 32; media per
  station: 2.

The ZIP reader never extracts using an entry name. It accepts only
`manifest.json` or a safe generated asset alias, then writes each media stream to
a new generated staging filename beneath app-private cache. This prevents ZIP
Slip/path traversal and avoids trusting original filenames. No archive content
is executed or deserialized as code.

The computed SHA-256 detects media corruption, not publisher identity. Version 1
is not signed and must be treated as untrusted input.

## Import And Export Lifecycle

Export takes a relational game snapshot, verifies every referenced source media
file against stored byte size and SHA-256, then builds and validates a private
temporary ZIP. Only a complete archive is copied to the SAF destination. Missing
or changed media fails the export; the app does not report success for a partial
file and attempts to delete a partially written SAF document.

Import copies the selected SAF document to bounded private staging, validates
the full package and checksums, then presents a summary for user confirmation.
After confirmation, it generates fresh IDs and QR tokens, stages a complete
private game asset directory, promotes that directory, and inserts the full
relational game in one SQLite transaction. A database failure removes the
promoted directory. Startup cleanup removes abandoned import staging and UUID
asset directories not referenced by the database once they are older than 24
hours, avoiding races with active imports. SQLite and the filesystem do not
share an atomic commit; cleanup reconciles a process death between directory
promotion and database commit.

Version 2 exports are not readable by older app versions. The current app continues to import v1. SQLite task data is stored with schema v8.

## SOČ Demonstration

1. Create a game with two ordered stations, one image, one audio file, a
  multiple-choice, true/false, and short-text tasks with configured points, and `FIRST_TRY_ONLY` scoring.
2. Export it through the Android document picker to a `.qrhry` file.
3. Import that file as a new copy and confirm the preview's content summary and
  scoring mode.
4. Show both games side by side in the library, generate/scan a QR code from
  the imported copy, and verify its media and scoring behavior.
5. Import the same archive again to demonstrate independent copies, then try a
  truncated or modified archive and show that the app rejects it without
  adding a game or leaving media behind.
