## Tribes and Kingdoms (MC 1.21.11) — consolidated spec → dev task breakdown (Fabric + NeoForge)

### Core gameplay spec (v1)

* **Worldgen-first “civilizations”:** villages generate rarer overall, but appear in **clusters** (“kingdoms/civs”), with **internal roads** and light terraforming prebuilt.
* **Static tiers (no upgrading):** each settlement/cluster is assigned a **tech level** at generation time and never advances.

  * Tier ladder: **Wood (rare) → Stone (common) → Iron (rare) → Diamond (very rare; only multi-village civ) → Netherite (super rare; only capitals of very large civs)**.
* **Tribes vs Kingdoms:**

  * **Tribes:** Wood/Stone single settlements (or tiny clusters).
  * **Kingdoms:** multi-settlement clusters at Stone/Iron/Diamond/Netherite, including a **capital** concept at the high end.
* **Environmental requirements:** each tier has placement constraints (e.g., wood requires nearby tree-producing biomes; higher tiers require “suitable” terrain + biome + maybe proximity to resources, rivers, coastlines, etc.).
* **Roads are static and deterministic:**

  * Roads **inside clusters** are placed during cluster generation.
  * Roads **between clusters** are **pre-planned** (deterministic from world seed + anchors) and then **stamped into chunks** when those chunks generate/load, so players see roads “the first time they see the chunk.”
* **Villager behavior mostly unchanged (v1):**

  * No custom AI/civilization simulation initially.
  * **Trades are biased** by tech level (more/less likely offers, or different pools).
* **No player-dependent divergence:**

  * Road existence/placement must not depend on who had higher view distance first; chunk stamping is driven by deterministic planning + server-side chunk lifecycle hooks.

### Definition: “worldgen-first”

In this project, **worldgen-first** means civilization content is authored by generation systems, not by runtime simulation loops:

* Settlement placement, tier assignment, cluster layout, and road plans come from deterministic generation rules (`world seed + region/chunk coordinates`).
* Features appear as part of world/chunk lifecycle (generation/load stamping), not because villagers or players “build up” civilization state over time.
* Two servers with the same seed + config should produce equivalent civ topology regardless of player exploration order.
* Trade bias can react to nearby generated settlement tier, but the settlement graph itself does not evolve in v1.

---

## Toolchain + repo architecture (Fabric + NeoForge)

### Current project baseline

* This repo already uses **Architectury** with a `common/`, `fabric/`, `neoforge/` split.
* Keep almost all gameplay logic in `common`, with loader-specific event wiring in `fabric` and `neoforge`.
* Use Mojang mappings in shared code and keep loader-only APIs out of `common`.
* For implementation inspiration, RoadWeaver shows a similar multiloader module split. ([GitHub][2])

### Version baseline (from this repository)

* Minecraft: **1.21.11**
* Java target: **21**
* Fabric Loader: **0.18.4**
* Fabric API: **0.141.3+1.21.11**
* NeoForge: **21.11.38-beta**
* Architectury API: **19.0.1**

---

## Worldgen + “roads appear on first sight” — the practical approach

### Key constraint

You can’t reliably “generate roads into unloaded-but-not-generated chunks” in a way that’s both:

* consistent across servers and
* independent of player view distance

…unless you either:

1. **stamp roads when chunks generate/load**, or
2. run a background chunk pregen (which *does* depend on server config + exploration patterns), or
3. simulate “in-universe” building (you explicitly said you *don’t* want that for v1).

So the v1 architecture should be:

### Deterministic planning + chunk stamping

1. **Decide civilization anchors deterministically** (based on world seed + region grid).
2. **Plan road segments deterministically** between anchors (graph edges).
3. **When a chunk is generated/loaded**, check if any planned road geometry intersects it; if yes and not yet applied, **stamp** blocks.

This aligns with your requirement: “when the player sees the chunk the first time, it has roads if it will have roads.”

#### Loader hooks you’ll use

* **Fabric:** `ServerChunkEvents.CHUNK_LOAD` and related lifecycle events are explicitly intended for “chunk is already in the world” load hooks. ([Maven FabricMC][6])
* **NeoForge:** `ChunkEvent.Load` is fired when a chunk loads into a level. ([nekoyue.github.io][7])

  * Avoid `ChunkDataEvent.Load` for block placement; NeoForge’s docs/javadocs note it fires during chunk serialization/deserialization and can be async. ([nekoyue.github.io][7])

#### Persistence

* Store your civilization plans (anchors, tiers, road graph) in **world saved data**:

  * NeoForge documents “Saved Data” for per-level persistence. ([NeoForged Documentation][8])
  * In practice, you can implement this in common code using vanilla `SavedData`/`PersistentState` patterns and only vary the loader glue.

---

# Dev task breakdown (milestones)

## Milestone 0 — scaffolding & conventions

**0.1 Multiloader repo**

* `common/`, `fabric/`, `neoforge/` project layout is already present (Architectury).
* Preserve module boundaries while implementing features.
* Decide mappings strategy (strong recommendation): **use Mojang mappings in common** to reduce cross-loader name drift; keep Yarn-only references out of common code.

**0.2 Shared “Platform” abstraction (minimal)**

* In `common`, define interfaces:

  * `PlatformEvents` (register chunk hooks, command hooks)
  * `PlatformRegistry` (register custom types if needed)
  * `PlatformConfig` (optional)
* In each loader module, implement + inject these at init time.

**0.3 Dev ergonomics**

* Add a debug logger category + optional debug rendering toggles (server-only at first).
* Add a “/civdebug” command skeleton for: dump anchors, dump roads in radius, force restamp a chunk.

---

## Milestone 1 — data model & saved state

**1.1 Core types (common)**

* `TechTier` enum (WOOD, STONE, IRON, DIAMOND, NETHERITE)
* `SettlementType` (TRIBE, KINGDOM_CAPITAL, KINGDOM_TOWN, OUTPOST, etc.)
* `SettlementAnchor`:

  * `UUID/long id`, `BlockPos center`, `tier`, `biome tags`, `radius`, `connectedCivId`
* `CivGraph`:

  * nodes = anchors, edges = planned roads with metadata (style, width, material palette)

**1.2 Persistence**

* Implement `CivWorldState` (SavedData/PersistentState):

  * world seed hash
  * region generation version
  * anchors by region
  * road edges + per-chunk “stamped” markers (bitset / LongSet of `ChunkPos.toLong()`)

(You want **static** roads and static tiers, so this stays small and stable.)

---

## Milestone 2 — placement: “where do kingdoms/tribes go?”

**2.1 Region grid**

* Partition the world into large regions (e.g., 1024–4096 blocks square).
* Deterministically choose 0–N candidate anchors per region using `seed ^ regionCoordHash`.

**2.2 Suitability sampling**

* Before generating full structures, sample:

  * biome at x/z
  * height at x/z from generator
  * slope/roughness by sampling nearby heights
  * optional: distance to water / rivers
* Apply tier-specific constraints (your wood-needs-trees-within-100-blocks rule, etc.).

**2.3 Cluster definition**

* For kingdoms: choose a capital anchor + 2–8 satellite anchors in a radius (tier determines size).
* For tribes: single anchor with small footprint.

---

## Milestone 3 — worldgen assets: settlements + internal roads + terraforming

**3.1 Data-driven structures first**

* Implement settlements as **jigsaw structures** with tier-specific pools:

  * `data/<modid>/worldgen/structure/...`
  * `.../structure_set/...`
  * `.../template_pool/...`
* Use Misode’s worldgen tools to iterate quickly on JSON while you stabilize the schema. ([Misode][9])
* NeoForge’s docs emphasize datapack registry layout conventions; keep everything in the standard `data/<modid>/worldgen/...` paths. ([NeoForged Documentation][10])

**3.2 Terraform pass (inside the cluster only)**

* After placing the jigsaw layout, run a deterministic “terraform brush”:

  * flatten paths
  * fill small holes
  * cut small ridges
  * optionally place retaining walls
* Keep it bounded (e.g., within `clusterRadius + margin`) to avoid ugly “world scars.”

**3.3 Internal roads**

* Inside the jigsaw layout, include road pieces (NBT structure templates) so clusters spawn with roads prebuilt.

---

## Milestone 4 — inter-cluster roads: planning + stamping

**4.1 Road graph construction**

* Build edges between anchors based on distance thresholds and civ membership:

  * tribes ↔ nearest kingdom node within X
  * kingdoms ↔ kingdoms within Y
* Ensure determinism (same seed ⇒ same graph).

**4.2 Road routing**

* Start simple for v1:

  * A* / Dijkstra over a coarse grid (e.g., sample every 4 blocks)
  * cost = slope penalty + water penalty + forest penalty
* Produce a polyline (list of points), then smooth (optional) into segments.

(For inspiration, RoadWeaver explicitly calls out terrain-aware pathing and infrastructure like bridges/tunnels; you can crib the “feature set” as an implementation checklist even if your algorithm differs. ([GitHub][2]))

**4.3 Chunk intersection index**

* For each road polyline, compute which `ChunkPos` it passes through.
* Store `chunk -> list<roadSegmentRefs>` in `CivWorldState`.

**4.4 Stamping rules**

* For each intersecting chunk:

  * carve/fill to create a walkable grade (bounded height delta)
  * place tier-appropriate blocks (dirt path / gravel / stone bricks / etc.)
  * optionally place lamps, walls, guard posts as tier increases

**4.5 Hook into chunk lifecycle**

* Fabric: register stamping on chunk load/generate via Fabric lifecycle hooks. ([Maven FabricMC][6])
* NeoForge: register stamping on `ChunkEvent.Load`. ([nekoyue.github.io][7])
* Mark stamped chunks in `CivWorldState` so stamping is idempotent.

---

## Milestone 5 — villager trade bias by tech tier (no AI changes)

**5.1 Determine “settlement tier at villager position”**

* On trade building, find nearest anchor within radius; fallback to biome-based default if none.

**5.2 Fabric implementation**

* Use Fabric’s `TradeOfferHelper.registerVillagerOffers(...)` to add/weight offers. ([Maven FabricMC][11])

**5.3 NeoForge implementation**

* Subscribe to `VillagerTradesEvent` to modify the profession trade map. ([nekoyue.github.io][12])

---

## Milestone 6 — configs, compatibility, and UX

**6.1 Config**

* Spawn rarity, cluster size distribution, tier rarity curve
* Road width, terraforming aggressiveness, bridge/tunnel toggles
* “Only affect newly generated chunks” vs “retrofit existing chunks on load”

**6.2 Commands**

* `/civ locate` nearest settlement
* `/civ regen_region` recompute anchors for a region (debug only)
* `/civ restamp_chunk` (debug only)

**6.3 Multiplayer safety**

* All stamping on the **server thread** only.
* Keep stamping work bounded per tick (budgeted queue) to avoid load spikes.

---

# Similar mods to reference (and how to avoid feeling derivative)

### Closest “concept neighbors”

* **MineColonies** — player-driven town building / “build your kingdom” fantasy. Your mod differs by being **worldgen-owned**, not player-managed. ([Minecolonies][13])
* **Towns & Towers** — expands/overhauls village structures in worldgen. You’ll overlap on “better villages,” so differentiate via **tiered civ clusters + inter-cluster infrastructure**. ([CurseForge][14])
* **RoadArchitect** — scans structures and connects them with persistent roads. Very relevant inspiration for *road networking + persistence*; avoid being a clone by making roads **a byproduct of civ tiers + cluster planning**, not generic “connect everything.” ([CurseForge][15])
* **Countered’s Settlement Roads** — also “roads between villages,” so your differentiator should be **kingdom clustering + tech tier styling + terraforming + walls/soldiers later**. ([Modrinth][16])
* **Civilizations** (CurseForge) — “discover civilizations” content mod; name collision risk + thematic overlap, so you may want a distinct branding angle (e.g., “Kingdomcraft”, “Villager Realms”, etc.). ([CurseForge][17])

---

## Implementation stance (what I’d do first)

If you want the fastest path to a compelling v1 without getting stuck in villager AI:

1. **Anchors + clusters** (visual wow factor immediately)
2. **Internal roads + terraforming** (clusters feel “intentional”)
3. **Inter-cluster roads stamped on chunk load** (fulfills your “first sight” requirement)
4. **Trade bias** (light gameplay tie-in)

If you want, next we can turn this into a *literal* “task list you can paste into GitHub Issues” (epics → tickets with acceptance criteria), but the milestone plan above is already structured to map 1:1 onto epics.

[2]: https://github.com/shiroha-233/RoadWeaver "GitHub - shiroha-233/RoadWeaver"
[6]: https://maven.fabricmc.net/docs/fabric-api-0.110.5%2B1.21.4/net/fabricmc/fabric/api/event/lifecycle/v1/ServerChunkEvents.html "ServerChunkEvents (fabric-api 0.110.5+1.21.4 API)"
[7]: https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/neoforged/neoforge/event/level/package-summary.html?utm_source=chatgpt.com "Package net.neoforged.neoforge.event.level"
[8]: https://docs.neoforged.net/docs/1.21.3/datastorage/saveddata?utm_source=chatgpt.com "Saved Data | NeoForged docs"
[9]: https://misode.github.io/worldgen/?utm_source=chatgpt.com "Worldgen Generators - Minecraft 1.19, 1.20, 1.21"
[10]: https://docs.neoforged.net/docs/1.21.1/concepts/registries?utm_source=chatgpt.com "Registries | NeoForged docs"
[11]: https://maven.fabricmc.net/docs/fabric-api-0.98.0%2B1.21/net/fabricmc/fabric/api/object/builder/v1/trade/TradeOfferHelper.html?utm_source=chatgpt.com "TradeOfferHelper (fabric-api 0.98.0+1.21 API)"
[12]: https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.20.6-neoforge/net/neoforged/neoforge/event/village/VillagerTradesEvent.html?utm_source=chatgpt.com "VillagerTradesEvent (neoforge 1.20.6-20.6.119)"
[13]: https://minecolonies.com/wiki/?utm_source=chatgpt.com "Wiki Home | MineColonies Wiki"
[14]: https://www.curseforge.com/minecraft/mc-mods/towns-and-towers?utm_source=chatgpt.com "Towns and Towers - Minecraft Mods"
[15]: https://www.curseforge.com/minecraft/mc-mods/roadarchitect?utm_source=chatgpt.com "RoadArchitect - Minecraft Mods"
[16]: https://modrinth.com/mod/countereds-settlement-roads?utm_source=chatgpt.com "Countered's Settlement Roads - Minecraft Mod"
[17]: https://www.curseforge.com/minecraft/mc-mods/civilizations?utm_source=chatgpt.com "Civilizations - Minecraft Mods"
