# Roadmap

## Current slice

- [x] Create a minimal Android/Compose project and Gradle wrapper.
- [x] Create a game with at least two titled stations.
- [x] Save the game and ordered stations atomically in local SQLite.
- [x] Retrieve and display saved games and station text.
- [x] Generate and display a stable QR code for every station.
- [x] Scan a versioned station QR payload and resolve it from SQLite.
- [x] Show distinct malformed/unknown results and navigate valid scans to station details.
- [x] Add domain validation and database/core tests.
- [x] Separate Create/Edit and Play entry points.
- [x] Persist sessions and ordered station visits; restore unfinished sessions.
- [x] Enforce ordered scans, prevent duplicate visits, and persist completion.
- [ ] Run the app manually on an emulator or device.

## Next

- Add editable games/stations if needed, preserving stable QR identifiers.

## Later, out of current scope

Typed question/task content, external image/audio resources, scoring,
achievements, timers, branching, and online content updates. Add each only with
its data model and migration strategy defined; do not hardcode a demonstration
game into game logic.
