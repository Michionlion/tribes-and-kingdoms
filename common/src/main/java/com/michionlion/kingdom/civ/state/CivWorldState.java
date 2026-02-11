package com.michionlion.kingdom.civ.state;

import com.michionlion.kingdom.civ.model.CivGraph;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.RoadEdge;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CivWorldState extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String DATA_NAME = "kingdom_civ_world_state";
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private static final String KEY_SCHEMA_VERSION = "schema_version";
    private static final String KEY_WORLD_SEED_HASH = "world_seed_hash";
    private static final String KEY_REGION_GENERATION_VERSION = "region_generation_version";
    private static final String KEY_ANCHORS = "anchors";
    private static final String KEY_ANCHORS_BY_REGION = "anchors_by_region";
    private static final String KEY_PLANNED_REGIONS = "planned_regions";
    private static final String KEY_EDGES = "edges";
    private static final String KEY_STAMPED_CHUNKS = "stamped_chunks";

    private static final String KEY_ANCHOR_ID = "id";
    private static final String KEY_ANCHOR_X = "x";
    private static final String KEY_ANCHOR_Y = "y";
    private static final String KEY_ANCHOR_Z = "z";
    private static final String KEY_ANCHOR_TIER = "tier";
    private static final String KEY_ANCHOR_TYPE = "type";
    private static final String KEY_ANCHOR_RADIUS = "radius";
    private static final String KEY_ANCHOR_CIV_ID = "civ_id";
    private static final String KEY_ANCHOR_BIOME_TAGS = "biome_tags";

    private static final String KEY_EDGE_ID = "id";
    private static final String KEY_EDGE_FROM_ID = "from_id";
    private static final String KEY_EDGE_TO_ID = "to_id";
    private static final String KEY_EDGE_WIDTH = "width";
    private static final String KEY_EDGE_PALETTE_ID = "palette_id";
    private static final String KEY_EDGE_POINTS = "points";

    private static final String KEY_REGION_KEY = "region_key";
    private static final String KEY_REGION_ANCHOR_IDS = "anchor_ids";

    private static final Identifier DEFAULT_PALETTE_ID = Identifier.parse("kingdom:default_road_palette");

    private static final Codec<CivWorldState> CODEC = Codec.PASSTHROUGH.xmap(
        dynamic -> {
            Tag converted = dynamic.convert(NbtOps.INSTANCE).getValue();
            if (converted instanceof CompoundTag compoundTag) {
                return fromTag(compoundTag);
            }
            return new CivWorldState();
        },
        state -> new Dynamic<>(NbtOps.INSTANCE, state.toTag())
    );

    public static final SavedDataType<CivWorldState> TYPE = new SavedDataType<>(
        DATA_NAME,
        CivWorldState::new,
        CODEC,
        DataFixTypes.LEVEL
    );

    private int schemaVersion;
    private long worldSeedHash;
    private int regionGenerationVersion;
    private final CivGraph civGraph;
    private final Map<Long, LongArrayList> anchorsByRegion;
    private final LongSet plannedRegions;
    private final LongSet stampedChunks;

    public CivWorldState() {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.worldSeedHash = 0L;
        this.regionGenerationVersion = 0;
        this.civGraph = new CivGraph();
        this.anchorsByRegion = new LinkedHashMap<>();
        this.plannedRegions = new LongOpenHashSet();
        this.stampedChunks = new LongOpenHashSet();
    }

    public static CivWorldState get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        DimensionDataStorage dataStorage = overworld.getDataStorage();
        return dataStorage.computeIfAbsent(TYPE);
    }

    public static CivWorldState fromTag(CompoundTag tag) {
        CivWorldState state = new CivWorldState();

        int loadedSchemaVersion = tag.getIntOr(KEY_SCHEMA_VERSION, -1);
        if (loadedSchemaVersion != CURRENT_SCHEMA_VERSION) {
            LOGGER.warn(
                "Resetting civ world state due to schema mismatch. found={}, required={}",
                loadedSchemaVersion,
                CURRENT_SCHEMA_VERSION
            );
            return state;
        }

        state.schemaVersion = loadedSchemaVersion;
        state.worldSeedHash = tag.getLongOr(KEY_WORLD_SEED_HASH, 0L);
        state.regionGenerationVersion = tag.getIntOr(KEY_REGION_GENERATION_VERSION, 0);

        ListTag anchorsTag = tag.getListOrEmpty(KEY_ANCHORS);
        for (int i = 0; i < anchorsTag.size(); i++) {
            SettlementAnchor anchor = decodeAnchor(anchorsTag.getCompoundOrEmpty(i));
            state.civGraph.putAnchor(anchor);
        }

        ListTag edgesTag = tag.getListOrEmpty(KEY_EDGES);
        for (int i = 0; i < edgesTag.size(); i++) {
            RoadEdge edge = decodeEdge(edgesTag.getCompoundOrEmpty(i));
            state.civGraph.putEdge(edge);
        }

        ListTag regionsTag = tag.getListOrEmpty(KEY_ANCHORS_BY_REGION);
        for (int i = 0; i < regionsTag.size(); i++) {
            CompoundTag regionTag = regionsTag.getCompoundOrEmpty(i);
            long regionKey = regionTag.getLongOr(KEY_REGION_KEY, 0L);
            long[] anchorIdsArray = regionTag.getLongArray(KEY_REGION_ANCHOR_IDS).orElseGet(() -> new long[0]);
            state.anchorsByRegion.put(regionKey, new LongArrayList(anchorIdsArray));
        }

        long[] plannedRegionArray = tag.getLongArray(KEY_PLANNED_REGIONS).orElseGet(() -> new long[0]);
        for (long plannedRegion : plannedRegionArray) {
            state.plannedRegions.add(plannedRegion);
        }

        long[] stamped = tag.getLongArray(KEY_STAMPED_CHUNKS).orElseGet(() -> new long[0]);
        for (long chunkPos : stamped) {
            state.stampedChunks.add(chunkPos);
        }

        return state;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_SCHEMA_VERSION, schemaVersion);
        tag.putLong(KEY_WORLD_SEED_HASH, worldSeedHash);
        tag.putInt(KEY_REGION_GENERATION_VERSION, regionGenerationVersion);

        ListTag anchorsTag = new ListTag();
        for (SettlementAnchor anchor : civGraph.anchorsById().values()) {
            anchorsTag.add(encodeAnchor(anchor));
        }
        tag.put(KEY_ANCHORS, anchorsTag);

        ListTag edgesTag = new ListTag();
        for (RoadEdge edge : civGraph.edgesById().values()) {
            edgesTag.add(encodeEdge(edge));
        }
        tag.put(KEY_EDGES, edgesTag);

        ListTag byRegionTag = new ListTag();
        for (Map.Entry<Long, LongArrayList> entry : anchorsByRegion.entrySet()) {
            CompoundTag regionTag = new CompoundTag();
            regionTag.putLong(KEY_REGION_KEY, entry.getKey());
            regionTag.putLongArray(KEY_REGION_ANCHOR_IDS, entry.getValue().toLongArray());
            byRegionTag.add(regionTag);
        }
        tag.put(KEY_ANCHORS_BY_REGION, byRegionTag);

        long[] plannedRegionArray = plannedRegions.toLongArray();
        Arrays.sort(plannedRegionArray);
        tag.putLongArray(KEY_PLANNED_REGIONS, plannedRegionArray);

        long[] stampedChunksArray = stampedChunks.toLongArray();
        Arrays.sort(stampedChunksArray);
        tag.putLongArray(KEY_STAMPED_CHUNKS, stampedChunksArray);

        return tag;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public long worldSeedHash() {
        return worldSeedHash;
    }

    public void setWorldSeedHash(long worldSeedHash) {
        if (this.worldSeedHash != worldSeedHash) {
            this.worldSeedHash = worldSeedHash;
            setDirty();
        }
    }

    public int regionGenerationVersion() {
        return regionGenerationVersion;
    }

    public void setRegionGenerationVersion(int regionGenerationVersion) {
        if (this.regionGenerationVersion != regionGenerationVersion) {
            this.regionGenerationVersion = regionGenerationVersion;
            setDirty();
        }
    }

    public CivGraph civGraph() {
        return new CivGraph(civGraph.anchorsById(), civGraph.edgesById());
    }

    public Map<Long, LongList> anchorsByRegion() {
        Map<Long, LongList> view = new LinkedHashMap<>();
        for (Map.Entry<Long, LongArrayList> entry : anchorsByRegion.entrySet()) {
            view.put(entry.getKey(), LongLists.unmodifiable(entry.getValue()));
        }
        return Collections.unmodifiableMap(view);
    }

    public LongSet stampedChunks() {
        return new LongOpenHashSet(stampedChunks);
    }

    public SettlementAnchor anchor(long anchorId) {
        return civGraph.anchor(anchorId);
    }

    public List<SettlementAnchor> anchorsInRegion(RegionKey regionKey) {
        LongArrayList anchorIds = anchorsByRegion.get(regionKey.asLong());
        if (anchorIds == null || anchorIds.isEmpty()) {
            return List.of();
        }

        List<SettlementAnchor> anchors = new ArrayList<>(anchorIds.size());
        for (long anchorId : anchorIds) {
            SettlementAnchor anchor = civGraph.anchor(anchorId);
            if (anchor != null) {
                anchors.add(anchor);
            }
        }

        return List.copyOf(anchors);
    }

    public boolean isRegionPlanned(RegionKey regionKey) {
        return plannedRegions.contains(regionKey.asLong());
    }

    public void putAnchor(RegionKey regionKey, SettlementAnchor anchor) {
        civGraph.putAnchor(anchor);
        anchorsByRegion.computeIfAbsent(regionKey.asLong(), unused -> new LongArrayList()).add(anchor.id());
        setDirty();
    }

    public void putEdge(RoadEdge edge) {
        civGraph.putEdge(edge);
        setDirty();
    }

    public void replaceRegionPlan(RegionKey regionKey, List<SettlementAnchor> anchors) {
        clearRegionPlan(regionKey);
        for (SettlementAnchor anchor : anchors) {
            civGraph.putAnchor(anchor);
            anchorsByRegion.computeIfAbsent(regionKey.asLong(), unused -> new LongArrayList()).add(anchor.id());
        }
        plannedRegions.add(regionKey.asLong());
        setDirty();
    }

    public void clearRegionPlan(RegionKey regionKey) {
        long packedRegionKey = regionKey.asLong();
        LongArrayList removedAnchorIds = anchorsByRegion.remove(packedRegionKey);
        plannedRegions.remove(packedRegionKey);

        if (removedAnchorIds != null) {
            Set<Long> removedAnchorIdSet = new HashSet<>();
            for (long anchorId : removedAnchorIds) {
                civGraph.removeAnchor(anchorId);
                removedAnchorIdSet.add(anchorId);
            }

            if (!removedAnchorIdSet.isEmpty()) {
                List<Long> edgeIdsToRemove = new ArrayList<>();
                for (RoadEdge edge : civGraph.edgesById().values()) {
                    if (removedAnchorIdSet.contains(edge.fromAnchorId()) || removedAnchorIdSet.contains(edge.toAnchorId())) {
                        edgeIdsToRemove.add(edge.id());
                    }
                }
                for (long edgeId : edgeIdsToRemove) {
                    civGraph.removeEdge(edgeId);
                }
            }
        }

        setDirty();
    }

    public boolean markChunkStamped(long chunkPosLong) {
        boolean added = stampedChunks.add(chunkPosLong);
        if (added) {
            setDirty();
        }
        return added;
    }

    public boolean isChunkStamped(long chunkPosLong) {
        return stampedChunks.contains(chunkPosLong);
    }

    private static CompoundTag encodeAnchor(SettlementAnchor anchor) {
        CompoundTag anchorTag = new CompoundTag();
        anchorTag.putLong(KEY_ANCHOR_ID, anchor.id());
        anchorTag.putInt(KEY_ANCHOR_X, anchor.center().getX());
        anchorTag.putInt(KEY_ANCHOR_Y, anchor.center().getY());
        anchorTag.putInt(KEY_ANCHOR_Z, anchor.center().getZ());
        anchorTag.putString(KEY_ANCHOR_TIER, anchor.tier().name());
        anchorTag.putString(KEY_ANCHOR_TYPE, anchor.type().name());
        anchorTag.putInt(KEY_ANCHOR_RADIUS, anchor.radius());
        anchorTag.putLong(KEY_ANCHOR_CIV_ID, anchor.civId());

        List<Identifier> sortedBiomeTags = new ArrayList<>(anchor.biomeTags());
        sortedBiomeTags.sort((left, right) -> left.toString().compareTo(right.toString()));

        ListTag biomeTagsTag = new ListTag();
        for (Identifier biomeTag : sortedBiomeTags) {
            biomeTagsTag.add(StringTag.valueOf(biomeTag.toString()));
        }
        anchorTag.put(KEY_ANCHOR_BIOME_TAGS, biomeTagsTag);

        return anchorTag;
    }

    private static SettlementAnchor decodeAnchor(CompoundTag anchorTag) {
        long id = anchorTag.getLongOr(KEY_ANCHOR_ID, 0L);
        BlockPos center = new BlockPos(
            anchorTag.getIntOr(KEY_ANCHOR_X, 0),
            anchorTag.getIntOr(KEY_ANCHOR_Y, 0),
            anchorTag.getIntOr(KEY_ANCHOR_Z, 0)
        );
        TechTier tier = parseEnum(anchorTag.getStringOr(KEY_ANCHOR_TIER, ""), TechTier.class, TechTier.STONE);
        SettlementType type = parseEnum(anchorTag.getStringOr(KEY_ANCHOR_TYPE, ""), SettlementType.class, SettlementType.TRIBE);
        int radius = anchorTag.getIntOr(KEY_ANCHOR_RADIUS, 0);
        long civId = anchorTag.getLongOr(KEY_ANCHOR_CIV_ID, 0L);

        Set<Identifier> biomeTags = new LinkedHashSet<>();
        ListTag biomeTagsTag = anchorTag.getListOrEmpty(KEY_ANCHOR_BIOME_TAGS);
        for (int i = 0; i < biomeTagsTag.size(); i++) {
            String biomeTagRaw = biomeTagsTag.getStringOr(i, "");
            Identifier biomeTag = Identifier.tryParse(biomeTagRaw);
            if (biomeTag != null) {
                biomeTags.add(biomeTag);
            }
        }

        return new SettlementAnchor(id, center, tier, type, radius, civId, biomeTags);
    }

    private static CompoundTag encodeEdge(RoadEdge edge) {
        CompoundTag edgeTag = new CompoundTag();
        edgeTag.putLong(KEY_EDGE_ID, edge.id());
        edgeTag.putLong(KEY_EDGE_FROM_ID, edge.fromAnchorId());
        edgeTag.putLong(KEY_EDGE_TO_ID, edge.toAnchorId());
        edgeTag.putInt(KEY_EDGE_WIDTH, edge.width());
        edgeTag.putString(KEY_EDGE_PALETTE_ID, edge.paletteId().toString());

        ListTag pointsTag = new ListTag();
        for (BlockPos point : edge.controlPoints()) {
            CompoundTag pointTag = new CompoundTag();
            pointTag.putInt(KEY_ANCHOR_X, point.getX());
            pointTag.putInt(KEY_ANCHOR_Y, point.getY());
            pointTag.putInt(KEY_ANCHOR_Z, point.getZ());
            pointsTag.add(pointTag);
        }
        edgeTag.put(KEY_EDGE_POINTS, pointsTag);

        return edgeTag;
    }

    private static RoadEdge decodeEdge(CompoundTag edgeTag) {
        long id = edgeTag.getLongOr(KEY_EDGE_ID, 0L);
        long fromAnchorId = edgeTag.getLongOr(KEY_EDGE_FROM_ID, 0L);
        long toAnchorId = edgeTag.getLongOr(KEY_EDGE_TO_ID, 0L);
        int width = edgeTag.getIntOr(KEY_EDGE_WIDTH, 1);

        Identifier paletteId = Identifier.tryParse(edgeTag.getStringOr(KEY_EDGE_PALETTE_ID, DEFAULT_PALETTE_ID.toString()));
        if (paletteId == null) {
            paletteId = DEFAULT_PALETTE_ID;
        }

        List<BlockPos> controlPoints = new ArrayList<>();
        ListTag pointsTag = edgeTag.getListOrEmpty(KEY_EDGE_POINTS);
        for (int i = 0; i < pointsTag.size(); i++) {
            CompoundTag pointTag = pointsTag.getCompoundOrEmpty(i);
            controlPoints.add(new BlockPos(
                pointTag.getIntOr(KEY_ANCHOR_X, 0),
                pointTag.getIntOr(KEY_ANCHOR_Y, 0),
                pointTag.getIntOr(KEY_ANCHOR_Z, 0)
            ));
        }

        return new RoadEdge(id, fromAnchorId, toAnchorId, width, paletteId, controlPoints);
    }

    private static <E extends Enum<E>> E parseEnum(String name, Class<E> type, E fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
