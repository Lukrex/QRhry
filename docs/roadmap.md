# Roadmap

## Completed local milestones

- [x] Android/Compose project, game/station SQLite persistence, stable station
      QR UUIDs, QR generation, and scanning.
- [x] Persisted play sessions, visits, ordered progression, session restoration,
      and retained completed sessions.
- [x] Station image/audio import into app-private game asset directories,
      metadata persistence, editor replacement/removal, and playback.
- [x] SQLite v5 multiple-choice task tables and additive v4→v5 migration.
- [x] Separate station visited and station completed state; taskless stations
      complete on scan, while tasked stations wait for correct submitted answers.
- [x] Persisted pending answer selection, incorrect retry history, task completion,
      station completion, and final-session completion.
- [x] Multiple-choice authoring and gameplay for the first task type.
- [x] Configurable nonnegative points per multiple-choice task, one-time
      transactional awards, and persisted scores per session.
- [x] Completed-run results and history with task/station counts, English and
      Slovak UI, and backward-compatible v5→v6 migration.
- [x] Game-wide eventual-correct and first-try-only scoring policies, plus
      explicit zero-point continuation after a wrong answer; SQLite v7.
- [x] M8: versioned `.qrhry` export/import with SAF, media verification, fresh QR
      identities, and atomic repository import with staged-file cleanup.

## Completed M9

- [x] True/false and deterministic short-text tasks alongside multiple choice.
- [x] Type-specific authoring and gameplay with shared retry, scoring, and session persistence.
- [x] SQLite v8 migration preserving existing task and attempt data.
- [x] `.qrhry` v2 typed task export with v1 multiple-choice import compatibility.

- [x] M10: optional acyclic branched routes with answer-result and information
      transitions, route-aware QR validation, persistent session targets, and
      multiple endings.
- [x] SQLite v9 route tables and session cursor; existing games remain linear.
- [x] `.qrhry` v3 route export/import with v1/v2 linear import compatibility.
- [x] M11: optional 10–1800 second task timers, persisted deadlines, atomic
      zero-point timeout results, and `.qrhry` v4 timer compatibility.
- [x] SQLite v10 task timer state; v1–v3 archives continue to import as untimed.

Linear games continue to follow station `position`; branched games use explicit
route edges.

## Later, not implemented

Numeric answers, secret codes, optional tasks, hints, whole-game timers,
achievements, networking, synchronization, and shared content
versioning. Add each only with an explicit model, migration, and test plan; do
not hardcode a demonstration game into game logic.
