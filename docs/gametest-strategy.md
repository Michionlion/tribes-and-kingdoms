# GameTest Strategy

## Goal

Provide deterministic, loader-specific game tests that validate:

- server boot succeeds
- a mock player can traverse outward in a hybrid concentric pattern to 1024 blocks
- chunk generation progresses while traversing

## Test Model

The traversal test is implemented per loader but follows the same rules:

- setup ticks: `40`
- max ticks: `1400`
- radius range: `32` through `1024`
- ring step: `64`
- waypoint spacing: generated from an arc-length heuristic for deterministic ring density
- movement model:
  - inner rings (`<= 256`): incremental movement toward each waypoint
  - outer rings (`> 256`): direct teleport between waypoints

This keeps runtime near one minute while still stress-testing chunk generation.

## Assertions

Each loader test asserts all of the following:

- world/server level is available
- mock player creation succeeds and remains alive
- final distance from origin reaches at least `1024`
- sampled nearby chunks become obtainable as `ChunkStatus.FULL` during traversal
- unique generated chunk count reaches threshold (`>= 64`)

Failure messages include tick count and key counters (waypoint/chunk metrics).

## Why Server-Side Chunk Checks

Chunk-generation validation uses server chunk availability (`ServerChunkCache`) rather than client rendering state.
This avoids false negatives from client-side render/view settings and gives deterministic CI behavior for dedicated test runs.

## Local Commands

- Fabric game tests: `./gradlew :fabric:test`
- NeoForge game tests: `./gradlew :neoforge:runCiGameTestServer`
- Full local gate: `./gradlew clean :common:test :fabric:test :neoforge:runCiGameTestServer build`
