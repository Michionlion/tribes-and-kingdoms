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
- Included in Fabric dev runtime: Chunky, Sodium, Xaero's World Map + XaeroLib (`modLocalRuntime`) and Voxy (copied to `fabric/run/mods` before `runClient`).
- Included in NeoForge dev runtime: Chunky, Xaero's World Map + XaeroLib.
- `:fabric:runClient` and `:neoforge:runClient` enable a built-in dev command bridge.
- While the client is running in a singleplayer world, append commands to:
  - `fabric/run/kingdom-dev-commands.txt` (Fabric client run)
  - `neoforge/run/kingdom-dev-commands.txt` (NeoForge client run)
- One command per line; leading `/` is optional; lines starting with `#` are ignored.
- The bridge consumes and clears the file after reading commands.
- Commands run as the local player on the integrated server, so normal command permissions still apply.

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
