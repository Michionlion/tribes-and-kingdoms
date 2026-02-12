# Tribes and Kingdoms

A Minecraft 1.21.11 mod (for Fabric and NeoForge) focused on vanilla-like deterministic civilization generation.

## Vision (1.0.0)

- Villages become rarer but appear as clustered tribes/kingdoms.
- Each settlement gets a static tech tier at generation time.
- Internal roads are generated with clusters.
- Inter-cluster roads are pre-planned and stamped when chunks load/generate.
- Villager AI remains vanilla-like; trades are biased by local settlement tier.

## How Civilization Generation Works

- Core civilization state is generated deterministically from seed + coordinates.
- Settlements/tiers/roads are world-authored features, not villager-driven progression systems.
- Chunk stamping applies pre-planned road data when chunks appear; it does not re-plan based on player order.
- v1 does not include evolving civilization simulation; only trade behavior is biased by nearby generated tier.

## Tech Stack

- Java 21
- Architectury multiloader layout (`common`, `fabric`, `neoforge`)
- Minecraft 1.21.11
- Fabric Loader 0.18.4 + Fabric API 0.141.3+1.21.11
- NeoForge 21.11.38-beta

## Project Layout

- `common/`: shared gameplay logic, world state, deterministic planners.
- `fabric/`: Fabric-specific hooks/integration.
- `neoforge/`: NeoForge-specific hooks/integration.
- `SPEC.md`: product + milestone spec.
- `AGENTS.md`: contributor/agent context and guardrails.

## Build and Run

- Build everything: `./gradlew build`
- Clean build: `./gradlew clean build`
- Run Fabric client: `./gradlew :fabric:runClient`
- Run NeoForge client: `./gradlew :neoforge:runClient`
- Run Fabric game tests: `./gradlew :fabric:test`
- Run NeoForge game tests: `./gradlew :neoforge:runCiGameTestServer`
- Full local gate: `./gradlew clean :common:test :fabric:test :neoforge:runCiGameTestServer build`

Artifacts:

- `fabric/build/libs/tribes-and-kingdoms-fabric-<version>.jar`
- `neoforge/build/libs/tribes-and-kingdoms-neoforge-<version>.jar`

## Dev Command Helpers

- `:fabric:runClient` and `:neoforge:runClient` include dev-only map/pregen mods.
- Included in Fabric dev runtime: Chunky, Sodium, Distant Horizons, Xaero's World Map + XaeroLib, and Voxy (copied to `fabric/run/mods` before `runClient`).
- Included in NeoForge dev runtime: Chunky, Xaero's World Map + XaeroLib (`modLocalRuntime`) and Distant Horizons (copied to `neoforge/run/mods` before `runClient`).
- Voxy is enabled by default on non-macOS hosts and disabled by default on macOS.
- Override macOS Voxy behavior with `-PkingdomEnableVoxyOnMac=true` (or set `kingdom_enable_voxy_on_macos=true` in `gradle.properties`).
- `:fabric:runClient` and `:neoforge:runClient` enable a built-in dev command bridge.
- While the client is running in a singleplayer world, append commands to:
  - `fabric/run/kingdom-dev-commands.txt` (Fabric client run)
  - `neoforge/run/kingdom-dev-commands.txt` (NeoForge client run)
- One command per line; leading `/` is optional; lines starting with `#` are ignored.
- The bridge consumes and clears the file after reading commands.
- Commands run as the local player on the integrated server, so normal command permissions still apply.
- Milestone 2 debug commands are rooted at `/kingdom`:
  - `/kingdom generate around 2 true`
  - `/kingdom summary 3`
  - `/kingdom visualize anchors 2048`
  - `/kingdom export geojson 4 true`
  - `/kingdom config show`

## GeoJSON Analysis Helper

- Run end-to-end game tests + analysis for both loaders with:
  - `./gradlew analyzeKingdomGeoJson`
- `analyzeKingdomGeoJson` runs only the analysis-export game test mode (`kingdom.gametest.mode=analysis`) instead of full regression suites.
- Override mode explicitly with `-PkingdomGameTestMode=full` (or `analysis`) when needed.
- Default analysis window is 3x3 regions centered at `0,0` (radius `1`).
- Configure analysis window radius with:
  - `./gradlew analyzeKingdomGeoJson -PkingdomAnalysisRegionRadius=<radius>`
- Build artifacts written by the task:
  - `build/geojson-analysis/fabric/kingdom-geojson-visual-review.svg`
  - `build/geojson-analysis/neoforge/kingdom-geojson-visual-review.svg`
  - `build/geojson-analysis/fabric/analysis-summary.json`
  - `build/geojson-analysis/neoforge/analysis-summary.json`
- Terrain sample cache (height/slope/biome) is written under:
  - `build/geojson-terrain-cache/fabric/`
  - `build/geojson-terrain-cache/neoforge/`
  - Cache keys include seed + dimension + region window, so changing seed/window naturally invalidates prior entries.
- Terrain export parallelism follows placement config:
  - `[performance].parallelRegionPlanning` gates parallel terrain export on/off.
  - `[performance].parallelRegionThreads` controls worker count.
  - `parallelRegionThreads = 0` means auto (`availableProcessors - 1`).
- Analyze exported placement snapshots with:
  - `python scripts/analyze_kingdom_geojson.py <path-to-geojson> --config <path-to-kingdom.toml>`
- The script writes:
  - `analysis-summary.json`
  - `kingdom-geojson-visual-review.svg`
- Example (default 3x3 game test export around `0,0`):
  - `python scripts/analyze_kingdom_geojson.py fabric/build/run/gameTest/debug/kingdom/kingdom-analysis-export.geojson --config fabric/build/run/gameTest/config/kingdom.toml --expect-center 0 0 --expect-radius 1 --out-dir /tmp/kingdom-geojson-review-3x3`
- For a wider window around `0,0`, export with:
  - `/kingdom generate around 2 true`
  - `/kingdom export geojson 2 true kingdom-export-5x5-region-0-0`
  - then analyze with `--expect-center 0 0 --expect-radius 2`

## Metadata

- Mod id: `kingdom`
- Display name: `Tribes and Kingdoms`
- License: `GPL-3.0-or-later`

## Planning Kickoff (next)

1. Milestone 1: define core types (`TechTier`, `SettlementAnchor`, `CivGraph`) and `CivWorldState` persistence.
2. Milestone 2: deterministic anchor placement and biome/terrain suitability sampling.
3. Milestone 3: data-driven structures (jigsaw pools), internal roads, bounded terraforming.
4. Milestone 4: inter-cluster road planning, chunk intersection index, idempotent stamping hooks.
5. Milestone 5: trade bias integration per tier (Fabric + NeoForge event glue).

## Current Status

- Scaffolding and multiloader setup are in place.
- Placeholder class and metadata cleanup is complete.
- Next coding phase should begin with Milestone 1 domain + persistence scaffolding.

## Automated Game Tests

- Fabric game tests are executed through `:fabric:test` and include a deterministic traversal smoke test.
- NeoForge game tests are executed through `:neoforge:runCiGameTestServer` and use registry-based test registration.
- The traversal test validates:
  - server boot success
  - mock player movement in hybrid concentric traversal out to 1024 blocks
  - chunk generation progress and minimum generated chunk threshold
- CI runs these checks on every pull request and every push.
