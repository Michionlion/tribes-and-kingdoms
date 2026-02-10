# Content Authoring and Dev Setup (Milestone 1)

This document captures the non-runtime setup used while authoring worldgen content for later milestones.
Milestone 1 does not add runtime dependency wiring for dev-only mods.

## Recommended Dev Client Mods

- Mod Menu: quick visibility into loaded mods and metadata.
- Cloth Config: config screen support for mods that expose tunables.
- spark: profiling for client/server performance checks during iteration.

## Content Authoring Toolchain

- Misode: rapid editing/validation for worldgen JSON structure definitions.
- WorldEdit: in-world structure blockout and terrain prototyping.
- NBTExplorer: inspection of saved world data and serialized NBT payloads.
- Blockbench: mesh/template iteration for future visual assets.

## Road Logic Research Inputs

For Milestone 4 implementation readiness, review RoadArchitect and RoadWeaver patterns with a focus on:

- chunk intersection indexing
- idempotent placement discipline
- deterministic generation inputs

Use those projects for architecture patterns only; keep implementation aligned to this repo's v1 constraints in `SPEC.md`.

## Starter Datapack Layout Added in Milestone 1

- `data/kingdom/worldgen/structure/`
- `data/kingdom/worldgen/structure_set/`
- `data/kingdom/worldgen/template_pool/`

These directories are placeholders for Milestone 3 assets.
