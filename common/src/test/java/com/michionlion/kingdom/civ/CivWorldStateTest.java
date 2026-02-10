package com.michionlion.kingdom.civ;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.RoadEdge;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.state.CivWorldState;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivWorldStateTest {
    @Test
    void serializationRoundTripPreservesState() {
        CivWorldState original = new CivWorldState();
        original.setWorldSeedHash(11223344L);
        original.setRegionGenerationVersion(2);

        SettlementAnchor anchorA = new SettlementAnchor(
            101L,
            new BlockPos(10, 72, -25),
            TechTier.STONE,
            SettlementType.KINGDOM_TOWN,
            64,
            9001L,
            Set.of(requiredResource("minecraft:is_forest"), requiredResource("minecraft:is_overworld"))
        );
        SettlementAnchor anchorB = new SettlementAnchor(
            102L,
            new BlockPos(180, 69, 20),
            TechTier.IRON,
            SettlementType.OUTPOST,
            40,
            9001L,
            Set.of(requiredResource("minecraft:is_overworld"))
        );

        original.putAnchor(new RegionKey(0, 0), anchorA);
        original.putAnchor(new RegionKey(1, 0), anchorB);

        RoadEdge edge = new RoadEdge(
            201L,
            101L,
            102L,
            3,
            requiredResource("kingdom:stone_road"),
            List.of(new BlockPos(10, 72, -25), new BlockPos(180, 69, 20))
        );
        original.putEdge(edge);

        original.markChunkStamped(5L);
        original.markChunkStamped(10L);

        CompoundTag serialized = original.toTag();
        CivWorldState reloaded = CivWorldState.fromTag(serialized);

        assertEquals(original.worldSeedHash(), reloaded.worldSeedHash());
        assertEquals(original.regionGenerationVersion(), reloaded.regionGenerationVersion());
        assertEquals(original.civGraph().anchorsById(), reloaded.civGraph().anchorsById());
        assertEquals(original.civGraph().edgesById(), reloaded.civGraph().edgesById());
        assertEquals(original.anchorsByRegion(), reloaded.anchorsByRegion());
        assertEquals(original.stampedChunks(), reloaded.stampedChunks());
    }

    @Test
    void loadingEmptyStateUsesDefaults() {
        CivWorldState loaded = CivWorldState.fromTag(new CompoundTag());

        assertEquals(CivWorldState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(0L, loaded.worldSeedHash());
        assertEquals(0, loaded.regionGenerationVersion());
        assertTrue(loaded.civGraph().anchorsById().isEmpty());
        assertTrue(loaded.civGraph().edgesById().isEmpty());
        assertTrue(loaded.anchorsByRegion().isEmpty());
        assertTrue(loaded.stampedChunks().isEmpty());
    }

    @Test
    void missingSchemaVersionFallsBackToCurrentSchema() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("world_seed_hash", 77L);
        tag.putInt("region_generation_version", 4);

        CivWorldState loaded = CivWorldState.fromTag(tag);

        assertEquals(CivWorldState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(77L, loaded.worldSeedHash());
        assertEquals(4, loaded.regionGenerationVersion());
    }

    @Test
    void newerSchemaVersionReturnsSafeEmptyState() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CivWorldState.CURRENT_SCHEMA_VERSION + 1);
        tag.putLong("world_seed_hash", 99L);

        CivWorldState loaded = CivWorldState.fromTag(tag);

        assertEquals(CivWorldState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(0L, loaded.worldSeedHash());
        assertTrue(loaded.civGraph().anchorsById().isEmpty());
    }

    @Test
    void anchorsByRegionReferencesKnownAnchors() {
        CivWorldState state = new CivWorldState();
        SettlementAnchor anchor = new SettlementAnchor(
            1000L,
            new BlockPos(0, 70, 0),
            TechTier.WOOD,
            SettlementType.TRIBE,
            24,
            123L,
            Set.of(requiredResource("minecraft:is_overworld"))
        );

        state.putAnchor(new RegionKey(3, -2), anchor);

        Map<Long, LongList> index = state.anchorsByRegion();
        for (LongList ids : index.values()) {
            for (long id : ids) {
                assertTrue(state.civGraph().anchorsById().containsKey(id));
            }
        }
    }

    @Test
    void stampedChunksAreIdempotent() {
        CivWorldState state = new CivWorldState();

        assertTrue(state.markChunkStamped(33L));
        assertFalse(state.markChunkStamped(33L));
        assertTrue(state.isChunkStamped(33L));
        assertEquals(1, state.stampedChunks().size());
    }

    private static Identifier requiredResource(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid resource location: " + id);
        }
        return parsed;
    }
}
