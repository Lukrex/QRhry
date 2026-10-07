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

## Current scope

Continue hardening the local prototype and its test coverage. All game content
remains data-driven; progression remains linear by station `position`.

## Later, not implemented

Text answers, numeric answers, secret codes, optional tasks, scoring, hints,
timers, achievements, branching, networking, synchronization, and shared content
versioning. Add each only with an explicit model, migration, and test plan; do
not hardcode a demonstration game into game logic.
