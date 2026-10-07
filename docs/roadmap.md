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
- [ ] Build and run the app on an emulator or device.

## Next

- Add a locally persisted play session and ordered station progression.

## Later, out of current scope

Typed question/task content, external image/audio resources, scoring,
achievements, timers, branching, and online content updates. Add each only with
its data model and migration strategy defined; do not hardcode a demonstration
game into game logic.
