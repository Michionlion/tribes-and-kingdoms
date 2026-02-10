# Milestone 1 Data Model and Persistence

## Scope

Milestone 1 establishes the shared civilization domain model and persistent world state.
This phase intentionally excludes placement, worldgen structures, routing, and chunk stamping hooks.

## Implemented Core Types

- `TechTier`: `WOOD`, `STONE`, `IRON`, `DIAMOND`, `NETHERITE`
- `SettlementType`: `TRIBE`, `KINGDOM_CAPITAL`, `KINGDOM_TOWN`, `OUTPOST`
- `RegionKey`: packed `(x, z)` region coordinates with `asLong()` and `fromLong(long)`
- `SettlementAnchor`: deterministic anchor metadata
- `RoadEdge`: planned inter-anchor road metadata
- `CivGraph`: deterministic insertion-order graph storage for anchors and edges
- `AnchorIdGenerator`: deterministic `long` ID derivation from `(worldSeedHash, region, localOrdinal)`

## CivWorldState Schema v1

Top-level NBT keys:

- `schema_version`
- `world_seed_hash`
- `region_generation_version`
- `anchors`
- `anchors_by_region`
- `edges`
- `stamped_chunks`

Anchor entry keys:

- `id`
- `x`
- `y`
- `z`
- `tier`
- `type`
- `radius`
- `civ_id`
- `biome_tags`

Edge entry keys:

- `id`
- `from_id`
- `to_id`
- `width`
- `palette_id`
- `points`

Region index entry keys:

- `region_key`
- `anchor_ids`

## Versioning and Migration Behavior

- Current schema version is `1`.
- Missing `schema_version` is treated as v1.
- If a saved schema is newer than supported, load is skipped and an empty default state is returned.

## Access Pattern

`CivWorldState.get(ServerLevel)` is the single access point for retrieving persistent state from the overworld data storage.

## Testing Coverage

Unit tests validate:

- serialization round-trip
- empty/default load behavior
- schema fallback behavior
- deterministic ID properties
- region index consistency
- stamped chunk idempotency
