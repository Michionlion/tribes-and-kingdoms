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
- Optional smoke tests: `:fabric:runClient` and `:neoforge:runClient`
