# AGENTS.md

## Source of truth

- Product and implementation scope: `SPEC.md`.
- Keep implementation decisions aligned with v1 constraints in `SPEC.md` before adding new systems.

## Project snapshot

- Project: `tribes-and-kingdoms`
- Minecraft: `1.21.11`
- Java target: `21`
- Architecture: Architectury multiloader (`common`, `fabric`, `neoforge`)
- Mod id: `kingdom`
- Display name: `Tribes and Kingdoms`

## Version baseline (from `gradle.properties`)

- `architectury_api_version = 19.0.1`
- `fabric_loader_version = 0.18.4`
- `fabric_api_version = 0.141.3+1.21.11`
- `neoforge_version = 21.11.38-beta`

## Module responsibilities

- `common/`: deterministic planning, world state, routing, generation logic, and shared data structures.
- `fabric/`: Fabric event hooks and Fabric-only integrations.
- `neoforge/`: NeoForge event hooks and NeoForge-only integrations.

## V1 gameplay constraints (do not drift)

- Civilizations are worldgen-first: settlement topology is generated from seed/coordinates and does not rely on runtime civ simulation.
- Tech tiers are static at generation time: WOOD, STONE, IRON, DIAMOND, NETHERITE.
- Roads inside clusters are generated with structures; roads between clusters are planned first and stamped per chunk.
- Road placement must be player-order independent; stamping is server-side and idempotent.
- No custom villager AI/simulation in v1; only trade bias by settlement tier.

## Village/Kingdom placement algorithm (Milestone 2 reference)

1. Region partitioning (`CivPlacementPlanner`)
- World is partitioned into fixed-size planning regions (`region.regionSizeBlocks`, default `2048`).
- For each region, the planner builds a deterministic candidate grid using `candidate.cellSizeBlocks` (default `256`), so default density is `8 x 8 = 64` raw candidates per region.
- Candidate positions are deterministic from `worldSeed ^ regionKey ^ cellKey` and then sorted by deterministic key. The planner keeps the first `candidate.maxPerRegion` (default `12`).

2. Suitability sampling (`SuitabilitySampler`)
- For each candidate x/z, total score is a weighted blend of biome, height, slope, and water:
- `score = (biome*wBiome + height*wHeight + slope*wSlope + water*wWater) / (wBiome + wHeight + wSlope + wWater)`
- Biome scoring is string/path based (for example: forest/plains high, desert/nether low, many unmatched biomes fall back to medium score).
- Water scoring treats nearby water as positive and returns `1.0` immediately if the candidate biome itself is water-like (`ocean`, `river`, `beach`, `shore`, `swamp`, `mangrove`).
- Candidate is initially accepted only if:
- biome passes hard land gate (not oceanic/coastal), and
- `score >= thresholds.wood`.

3. Cluster generation (`ClusterGenerator`)
- Candidates are processed by descending suitability score.
- Hard kingdom spacing is enforced against existing capital centers (including nearby already-planned regions): `kingdomSpacing = max(minAnchorSpacing, minAnchorSpacing * 2)`; with defaults this is `1024`.
- Accepted candidates become either:
- Tribe (`TRIBE`, forced `WOOD`) via deterministic promotion logic, or
- Kingdom capital (`KINGDOM_CAPITAL`) with tier chosen by weighted policy from score/deterministic key.
- Capitals then generate satellites (`KINGDOM_TOWN` / `OUTPOST`) in a deterministic ring (`satelliteMinDistanceBlocks..satelliteMaxDistanceBlocks`) with spacing checks.
- Satellite attempts must pass the same land biome gate and minimum suitability threshold; best valid attempt by score is selected.

4. Sparse-area tribe backfill
- After capital pass, extra tribes are added from remaining candidates to avoid empty regions.
- This backfill can promote low-score candidates if they satisfy remoteness/spacing heuristics.
- Backfill tribes must stay away from existing capitals by at least:
- `cluster.satelliteMaxDistanceBlocks + (cluster.woodRadius * 4)`.

5. Persistence and determinism
- Final anchors are written to `CivWorldState` per region.
- Planning is deterministic for a fixed seed + config + generation version; regions are not regenerated unless forced.

6. Hard biome gate (important)
- Oceanic/coastal biomes are hard-rejected for settlement placement (`ocean`, `deep_ocean`, `beach`, `shore`, `river`, `swamp`, `mangrove`).
- Candidate anchors and satellite anchors both obey the same hard biome gate.
- Satellites also require suitability threshold pass before placement (not spacing-only anymore).

## Loader hook guidance

- Fabric chunk stamping: use `ServerChunkEvents` load/generation lifecycle hooks.
- NeoForge chunk stamping: use `ChunkEvent.Load`.
- Avoid NeoForge `ChunkDataEvent.Load` for block placement.

## Persistence guidance

- Store anchors, road graph, and stamped chunk markers in world saved data (`SavedData`/`PersistentState` pattern).
- Ensure schema has a version field for future migration.

## Planning kickoff (next implementation phase)

- Milestone 1: core domain model and `CivWorldState` persistence.
- Milestone 2: deterministic anchor placement and suitability sampling.
- Milestone 3: settlement/worldgen assets and internal roads.
- Milestone 4: inter-cluster road graph, chunk intersection index, and stamping queue.
- Milestone 5: villager trade bias integration.

## Build and run commands

- Build all: `./gradlew build`
- Clean build: `./gradlew clean build`
- Fabric client: `./gradlew :fabric:runClient`
- NeoForge client: `./gradlew :neoforge:runClient`
- List tasks: `./gradlew :fabric:tasks --all` / `./gradlew :neoforge:tasks --all`

## Artifact outputs

- `fabric/build/libs/tribes-and-kingdoms-fabric-<version>.jar`
- `neoforge/build/libs/tribes-and-kingdoms-neoforge-<version>.jar`

## Metadata sync checklist

When changing naming/versioning, keep these aligned:

- `gradle.properties` (`mod_version`, `archives_name`, dependency versions)
- `common/src/main/java/com/michionlion/KingdomMod.java` (`MOD_ID`)
- `fabric/src/main/resources/fabric.mod.json`
- `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- `common/src/main/resources/kingdom.mixins.json`

## Pre-commit validation

- `./gradlew clean build`
- `./gradlew :fabric:test :neoforge:runCiGameTestServer`
- Optional smoke tests: `:fabric:runClient` and `:neoforge:runClient`

## GeoJSON debug workflow

- Reusable analyzer script: `scripts/analyze_kingdom_geojson.py`
- Preferred one-shot task: `./gradlew analyzeKingdomGeoJson`
  - Runs `:fabric:runGameTest` + `:neoforge:runCiGameTestServer` in analysis-only game test mode (`kingdom.gametest.mode=analysis`) so only GeoJSON export tests execute.
  - Default analysis window is 3x3 regions centered at `0,0` (radius `1`)
  - Override radius with `-PkingdomAnalysisRegionRadius=<radius>`
  - Terrain sample cache lives under `build/geojson-terrain-cache/{fabric,neoforge}` and is keyed by seed/dimension/window.
  - Terrain export parallelism follows placement config:
    - `performance.parallelRegionPlanning` toggles parallel terrain sampling/build.
    - `performance.parallelRegionThreads` controls worker count (`0 => availableProcessors - 1`).
  - Writes build artifacts:
    - `build/geojson-analysis/fabric/kingdom-geojson-visual-review.svg`
    - `build/geojson-analysis/neoforge/kingdom-geojson-visual-review.svg`
    - `build/geojson-analysis/fabric/analysis-summary.json`
    - `build/geojson-analysis/neoforge/analysis-summary.json`
- Use it for Milestone 2 placement exports to generate:
  - `analysis-summary.json`
  - `kingdom-geojson-visual-review.svg`
- Preferred analysis inputs:
  - Fabric game test export: `fabric/build/run/gameTest/debug/kingdom/*.geojson`
  - NeoForge game test export: `neoforge/run/gametest-neoforge/debug/kingdom/*.geojson`
- For 3x3 validation around region `(0,0)`, export with radius `1` and analyze with:
  - `--expect-center 0 0 --expect-radius 1`
