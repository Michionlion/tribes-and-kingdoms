# Milestone 2 Execution Plan (Revised): Deterministic Placement + `/kingdom` Debug Tooling

## Summary

Implement Milestone 2 end-to-end with full cluster planning, deterministic suitability sampling, in-world visualization, and GeoJSON export, while minimizing loader-specific duplication via Architectury common events/utilities.

## Public API / Interface Changes

1. New root command: `/kingdom` (replaces `/civdebug`).
2. New common placement/config APIs under `common`:
`CivPlacementConfig`, `CivPlacementPlanner`, `SuitabilitySampler`, `ClusterGenerator`, `KingdomDebugService`, `GeoJsonExporter`.
3. `CivWorldState` schema update to milestone-2 schema with hard reset on mismatch (no v1 compatibility).
4. Loader UI entrypoints:
Fabric Mod Menu config screen provider.
NeoForge `IConfigScreenFactory` extension registration.

## Key Architectural Decisions

1. Use Architectury for shared integration first:
`CommandRegistrationEvent.EVENT` for server command registration in `common`.
`Platform.getConfigFolder()` for config location in `common`.
2. Keep loader-specific code only for client config-screen entrypoints.
3. Use schema hard reset strategy:
If loaded schema != current schema, clear and reinitialize world civ state.
4. Planning remains command-driven for Milestone 2 (no automatic chunk-load generation).
5. Command namespace is `/kingdom`, all debug functionality grouped under it.

## Detailed Implementation Plan

### Phase 1: Doc + Build/Dependency Setup

1. Add new doc file:
`/Users/saejin/Projects/personal/tribes-and-kingdoms/docs/milestone-2-implementation-plan.md`.
2. Update `/Users/saejin/Projects/personal/tribes-and-kingdoms/gradle.properties`:
add versions for `cloth-config` and `modmenu`.
3. Update Fabric build:
add Cloth Config + Mod Menu client/runtime dependencies.
4. Update NeoForge build:
add Cloth Config NeoForge dependency.
5. Update Fabric metadata:
register Mod Menu API entrypoint in `/Users/saejin/Projects/personal/tribes-and-kingdoms/fabric/src/main/resources/fabric.mod.json`.

### Phase 2: Config System (Common, Server-Authoritative)

1. Add config model in `common`:
`/Users/saejin/Projects/personal/tribes-and-kingdoms/common/src/main/java/com/michionlion/kingdom/civ/config/KingdomPlacementConfig.java`.
2. Config categories:
region settings, candidate generation, score weights, tier thresholds, cluster sizing, command defaults, visualization defaults.
3. Register/load config via Cloth AutoConfig + `Toml4jConfigSerializer` from common init.
4. Persist file at `<config>/kingdom.toml` (via `Platform.getConfigFolder()`).
5. Add config facade:
typed getters + `reload()` + validation/clamping for safe ranges.

### Phase 3: Deterministic Placement Core (Common)

1. Create deterministic planner package:
`common/.../kingdom/civ/placement/`.
2. Implement region mapping:
region size default 2048, region key from block/chunk position.
3. Candidate generation:
deterministic RNG seeded by world seed hash + region key + salt.
4. Suitability sampler:
compute biome affinity, height suitability, slope/roughness, water proximity.
5. Weighted score:
all component weights configurable; normalized [0..1].
6. Tier assignment:
threshold-based deterministic mapping to WOOD/STONE/IRON/DIAMOND/NETHERITE.
7. Cluster planning:
tribe = single anchor.
kingdom = capital + deterministic satellites with spacing/radius constraints.
8. Stable ordering:
sort candidates by deterministic key before selection to avoid hash/order drift.

### Phase 4: World State Integration

1. Update `/Users/saejin/Projects/personal/tribes-and-kingdoms/common/src/main/java/com/michionlion/kingdom/civ/state/CivWorldState.java`:
set Milestone 2 schema version.
2. Hard reset behavior:
if loaded schema != current schema, return fresh default state (log warning).
3. Add region plan operations:
`replaceRegionPlan(region, anchors)`, `clearRegionPlan(region)`, `isRegionPlanned(region)`.
4. Ensure idempotency:
forced regen removes previous region anchors before inserting new deterministic result.
5. Keep `anchors_by_region` canonical index for quick region queries.

### Phase 5: `/kingdom` Commands (Architectury Common Event)

1. Register command tree in common via `CommandRegistrationEvent.EVENT`.
2. Implement subcommands:
`/kingdom generate region <x> <z> [force]`
`/kingdom generate around <radiusRegions> [force]`
`/kingdom summary [radiusRegions]`
`/kingdom visualize anchors [radiusBlocks]`
`/kingdom export geojson <radiusRegions> [includeRejected] [filename]`
`/kingdom config show`
`/kingdom config reload`
`/kingdom config set <path> <value>` (for selected safe fields).
3. Command execution rules:
server-side only, permission-gated (`requires(level >= 2)`), deterministic and idempotent.

### Phase 6: In-World Visualization + GeoJSON Export

1. Add visualization service:
temporary particles/beams at anchor centers, color by tier, distinct capital marker style.
2. Add chat/table summary per run:
anchor counts by tier/type, accepted vs rejected candidates, avg score.
3. Add debug snapshot store:
retain most recent candidate evaluation per region for export/inspection.
4. GeoJSON export:
write to `/Users/saejin/Projects/personal/tribes-and-kingdoms/debug/kingdom/` (runtime cwd-resolved).
5. GeoJSON schema:
FeatureCollection.
accepted anchors as Point features.
optional rejected candidates as Point features.
properties include ids, region, tier, score_total, component scores, rejection_reason, is_capital.
6. Filename defaults:
`kingdom-placement-<world>-<timestamp>.geojson`.

### Phase 7: Loader-Specific UI Integration (Minimal Duplication)

1. Fabric:
add Mod Menu API implementation class returning AutoConfig screen supplier.
2. NeoForge:
register `IConfigScreenFactory` in mod constructor through active `ModContainer`.
3. Keep config logic shared:
both screens edit the same common config holder and serializer.

### Phase 8: Testing and Validation

1. Common unit tests:
determinism stability for fixed seed/region.
order independence across generation order.
score component bounds and weighting math.
cluster constraints (capital existence, satellite count/radius/spacing).
schema mismatch hard reset behavior.
2. Command-level tests:
generate/regen idempotency.
summary correctness against stored state.
export file created and parseable GeoJSON structure.
3. Loader game test smoke:
Fabric and NeoForge startup still passes existing traversal tests.
4. Full validation run:
`./gradlew clean :common:test :fabric:test :neoforge:runCiGameTestServer build`.

## Acceptance Criteria

1. `/kingdom` command suite works on Fabric and NeoForge with one shared command implementation.
2. Milestone 2 generation produces deterministic full clusters and persists anchors per region.
3. Suitability includes biome + height + slope + water proximity with tunable weights/thresholds.
4. Config is TOML-backed and editable via Cloth UI entrypoints on both loaders.
5. In-world visualization and GeoJSON export are both functional for debugging large areas.
6. Old Milestone 1 save schema is not migrated; mismatches are hard-reset cleanly.
7. Existing baseline tests pass and new Milestone 2 tests pass.

## Assumptions and Defaults

1. Overworld-only planning for Milestone 2.
2. Command-driven generation only in this milestone.
3. Hard reset on schema mismatch is acceptable for all existing worlds.
4. Default root command is `/kingdom`; no `/civdebug` alias is added.
5. Architectury command/config path integrations are preferred unless API gaps force loader-specific glue.
