package com.michionlion.kingdom.dev;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfigManager;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import com.michionlion.kingdom.civ.placement.CivPlacementPlanner;
import com.michionlion.kingdom.civ.placement.RegionPlacementResult;
import com.michionlion.kingdom.civ.state.CivWorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KingdomDebugService {
    private final CivPlacementPlanner planner;

    public KingdomDebugService() {
        this.planner = new CivPlacementPlanner();
    }

    public CivPlacementPlanner planner() {
        return planner;
    }

    public CivPlacementConfig config() {
        return KingdomPlacementConfigManager.snapshot();
    }

    public RegionOperationResult generateRegion(ServerLevel level, RegionKey regionKey, boolean force) {
        CivWorldState state = CivWorldState.get(level);
        state.setWorldSeedHash(level.getSeed());
        state.setRegionGenerationVersion(CivPlacementPlanner.REGION_GENERATION_VERSION);

        if (state.isRegionPlanned(regionKey) && !force) {
            return new RegionOperationResult(regionKey, 0, 0, true);
        }

        RegionPlacementResult result = planner.planRegion(level, regionKey, config());
        state.replaceRegionPlan(regionKey, result.anchors());
        PlacementDebugSnapshotStore.put(level.getServer(), result);

        long acceptedCandidates = result.candidates().stream().filter(candidate -> candidate.accepted()).count();
        return new RegionOperationResult(regionKey, result.anchors().size(), (int) acceptedCandidates, false);
    }

    public AroundOperationResult generateAround(ServerLevel level, BlockPos center, int radiusRegions, boolean force) {
        CivPlacementConfig config = config();
        RegionKey centerRegion = planner.regionAt(center.getX(), center.getZ(), config);

        int totalRegions = 0;
        int skippedRegions = 0;
        int totalAnchors = 0;
        for (int dx = -radiusRegions; dx <= radiusRegions; dx++) {
            for (int dz = -radiusRegions; dz <= radiusRegions; dz++) {
                RegionKey region = new RegionKey(centerRegion.x() + dx, centerRegion.z() + dz);
                RegionOperationResult result = generateRegion(level, region, force);
                totalRegions++;
                if (result.skipped()) {
                    skippedRegions++;
                }
                totalAnchors += result.generatedAnchors();
            }
        }

        return new AroundOperationResult(centerRegion, totalRegions, skippedRegions, totalAnchors);
    }

    public Summary summary(ServerLevel level, BlockPos center, int radiusRegions) {
        CivPlacementConfig config = config();
        RegionKey centerRegion = planner.regionAt(center.getX(), center.getZ(), config);
        CivWorldState state = CivWorldState.get(level);

        List<SettlementAnchor> anchors = new ArrayList<>();
        int regionCount = 0;
        for (int dx = -radiusRegions; dx <= radiusRegions; dx++) {
            for (int dz = -radiusRegions; dz <= radiusRegions; dz++) {
                RegionKey region = new RegionKey(centerRegion.x() + dx, centerRegion.z() + dz);
                if (!state.isRegionPlanned(region)) {
                    continue;
                }

                regionCount++;
                anchors.addAll(state.anchorsInRegion(region));
            }
        }

        Map<TechTier, Integer> byTier = new EnumMap<>(TechTier.class);
        Map<SettlementType, Integer> byType = new EnumMap<>(SettlementType.class);
        for (SettlementAnchor anchor : anchors) {
            byTier.merge(anchor.tier(), 1, Integer::sum);
            byType.merge(anchor.type(), 1, Integer::sum);
        }

        return new Summary(centerRegion, regionCount, anchors.size(), byTier, byType);
    }

    public int visualizeAnchors(ServerLevel level, BlockPos center, int radiusBlocks) {
        CivWorldState state = CivWorldState.get(level);
        List<SettlementAnchor> anchors = anchorsWithinRadius(state, center, radiusBlocks);
        AnchorDebugVisualizer.visualize(level, anchors, config());
        return anchors.size();
    }

    public Path exportGeoJson(
        ServerLevel level,
        BlockPos center,
        int radiusRegions,
        boolean includeRejected,
        String fileName
    ) throws IOException {
        CivPlacementConfig config = config();
        RegionKey centerRegion = planner.regionAt(center.getX(), center.getZ(), config);
        return GeoJsonExporter.exportRegionRadius(level, CivWorldState.get(level), centerRegion, radiusRegions, includeRejected, fileName);
    }

    private static List<SettlementAnchor> anchorsWithinRadius(CivWorldState state, BlockPos center, int radiusBlocks) {
        long radiusSq = (long) radiusBlocks * radiusBlocks;
        List<SettlementAnchor> anchors = new ArrayList<>();

        for (it.unimi.dsi.fastutil.longs.LongList anchorIds : state.anchorsByRegion().values()) {
            for (long anchorId : anchorIds) {
                SettlementAnchor anchor = state.anchor(anchorId);
                if (anchor == null) {
                    continue;
                }

                long dx = anchor.center().getX() - center.getX();
                long dz = anchor.center().getZ() - center.getZ();
                long distanceSq = (dx * dx) + (dz * dz);
                if (distanceSq <= radiusSq) {
                    anchors.add(anchor);
                }
            }
        }

        return anchors;
    }

    public record RegionOperationResult(RegionKey regionKey, int generatedAnchors, int acceptedCandidates, boolean skipped) {
    }

    public record AroundOperationResult(RegionKey centerRegion, int totalRegions, int skippedRegions, int generatedAnchors) {
    }

    public record Summary(
        RegionKey centerRegion,
        int plannedRegions,
        int totalAnchors,
        Map<TechTier, Integer> anchorsByTier,
        Map<SettlementType, Integer> anchorsByType
    ) {
        public Summary {
            anchorsByTier = Map.copyOf(new LinkedHashMap<>(anchorsByTier));
            anchorsByType = Map.copyOf(new LinkedHashMap<>(anchorsByType));
        }
    }
}
