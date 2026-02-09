# AGENTS.md

## Project Snapshot
- Project: `tribes-and-kingdoms` (Gradle root project name: `tribes-and-kingdoms`)
- Minecraft target: `1.21.11`
- Architecture: Architectury multi-loader (`common` + `fabric` + `neoforge`)
- Java target: `21`
- Build system: Gradle wrapper (`./gradlew`)

## Module Layout
- `common/`: Shared game logic and classes used by both loaders.
- `fabric/`: Fabric entrypoints, loader metadata, and Fabric-specific wiring.
- `neoforge/`: NeoForge entrypoint, metadata, and NeoForge-specific wiring.

Current entrypoint and shared mod classes:
- `common/src/main/java/com/michionlion/KingdomMod.java`
- `fabric/src/main/java/com/michionlion/fabric/KingdomModFabric.java`
- `fabric/src/main/java/com/michionlion/fabric/client/KingdomModFabricClient.java`
- `neoforge/src/main/java/com/michionlion/neoforge/KingdomModNeoForge.java`

## Verified Local Setup
- JDK: Java 21 is required and currently used by the project (`options.release = 21`).
- Root build succeeds:
  - `./gradlew clean build`
- Loader run tasks exist:
  - `:fabric:runClient`, `:fabric:runServer`
  - `:neoforge:runClient`, `:neoforge:runServer`

## Daily Commands
- Build everything:
  - `./gradlew build`
- Clean + rebuild:
  - `./gradlew clean build`
- Run Fabric client:
  - `./gradlew :fabric:runClient`
- Run NeoForge client:
  - `./gradlew :neoforge:runClient`
- Build only one loader:
  - `./gradlew :fabric:build`
  - `./gradlew :neoforge:build`
- Show available tasks:
  - `./gradlew :fabric:tasks --all`
  - `./gradlew :neoforge:tasks --all`

## Artifact Outputs
- Fabric jar: `fabric/build/libs/kingdom-fabric-<version>.jar`
- NeoForge jar: `neoforge/build/libs/kingdom-neoforge-<version>.jar`
- Common jar(s): `common/build/libs/`

## Version + Metadata Sync Checklist
When changing version, mod id, package names, or display name, keep these files aligned:
- `gradle.properties`
  - `mod_version`
  - `archives_name`
  - dependency versions (`fabric_*`, `neoforge_version`, `architectury_api_version`)
- `common/src/main/java/com/michionlion/KingdomMod.java`
  - `MOD_ID`
- `fabric/src/main/resources/fabric.mod.json`
  - `id`, `name`, `entrypoints`, `depends`
- `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
  - `modId`, `displayName`, dependency ranges
- `common/src/main/resources/kingdom.mixins.json`
  - package + mixin declarations

## Editing Rules For This Repo
- Put cross-loader gameplay/content logic in `common/`.
- Keep loader modules thin: entrypoints, registration glue, and platform-specific integrations only.
- Avoid using Fabric-only or NeoForge-only APIs directly from `common/`.
- If renaming core/entrypoint classes, update metadata entrypoint paths in the same change.

## Remaining Cleanup Ideas
- Verify metadata links if repository ownership/path changes:
  - `fabric/src/main/resources/fabric.mod.json`
  - `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Revisit `modId`/`archives_name` naming if the final branding differs from `kingdom`.
- Confirm license identifier remains accurate (`GPL-3.0-or-later`) for release packaging.

## Quick Validation Before Committing
- `./gradlew clean build`
- `./gradlew :fabric:runClient` (smoke test)
- `./gradlew :neoforge:runClient` (smoke test)
