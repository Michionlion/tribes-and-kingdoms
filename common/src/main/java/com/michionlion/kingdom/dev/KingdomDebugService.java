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
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class KingdomDebugService {
    private static final Logger LOGGER = LogUtils.getLogger();

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
        initializeStateMetadata(level, state);
        CivPlacementConfig placementConfig = config();

        long totalStartNanos = System.nanoTime();
        List<BlockPos> preoccupied = existingCapitalCenters(state, regionKey);
        RegionOperationResult result = planAndPersistRegion(level, state, placementConfig, regionKey, force, preoccupied);
        long totalMillis = nanosToMillis(System.nanoTime() - totalStartNanos);

        LOGGER.info(
            "[kingdom] region {},{} force={} skipped={} anchors={} accepted_candidates={} total_ms={}",
            regionKey.x(),
            regionKey.z(),
            force,
            result.skipped(),
            result.generatedAnchors(),
            result.acceptedCandidates(),
            totalMillis
        );
        return result;
    }

    public AroundOperationResult generateAround(ServerLevel level, BlockPos center, int radiusRegions, boolean force) {
        CivPlacementConfig config = config();
        RegionKey centerRegion = planner.regionAt(center.getX(), center.getZ(), config);
        CivWorldState state = CivWorldState.get(level);
        initializeStateMetadata(level, state);
        List<RegionKey> regions = regionsInRadius(centerRegion, radiusRegions);
        boolean parallelRegionPlanning = isParallelRegionPlanningEnabled(config);

        long totalStartNanos = System.nanoTime();
        AroundOperationResult result = parallelRegionPlanning && regions.size() > 1
            ? generateAroundParallel(level, state, config, centerRegion, regions, force)
            : generateAroundSequential(level, state, config, centerRegion, regions, force);
        long totalMillis = nanosToMillis(System.nanoTime() - totalStartNanos);

        LOGGER.info(
            "[kingdom] generate around center_region={},{} radius={} force={} regions={} skipped={} anchors={} mode={} total_ms={}",
            centerRegion.x(),
            centerRegion.z(),
            radiusRegions,
            force,
            result.totalRegions(),
            result.skippedRegions(),
            result.generatedAnchors(),
            parallelRegionPlanning && regions.size() > 1 ? "parallel" : "sequential",
            totalMillis
        );
        logRunTimingSummary(result.timingSummary(), totalMillis, parallelRegionPlanning ? "parallel" : "sequential");
        return result;
    }

    private AroundOperationResult generateAroundSequential(
        ServerLevel level,
        CivWorldState state,
        CivPlacementConfig config,
        RegionKey centerRegion,
        List<RegionKey> regions,
        boolean force
    ) {
        List<BlockPos> runningCapitalCenters = existingCapitalCenters(state, null);
        int totalRegions = 0;
        int skippedRegions = 0;
        int totalAnchors = 0;
        RunTimingAccumulator timingAccumulator = new RunTimingAccumulator();

        for (RegionKey region : regions) {
            List<BlockPos> regionExistingCapitals = capitalCentersInRegion(state, region);
            List<BlockPos> preoccupied = copyWithout(runningCapitalCenters, regionExistingCapitals);

            RegionOperationResult result = planAndPersistRegion(level, state, config, region, force, preoccupied);
            totalRegions++;
            if (result.skipped()) {
                skippedRegions++;
                continue;
            }

            timingAccumulator.add(result);
            totalAnchors += result.generatedAnchors();
            removeCenters(runningCapitalCenters, regionExistingCapitals);
            runningCapitalCenters.addAll(capitalCentersInRegion(state, region));
        }

        return new AroundOperationResult(centerRegion, totalRegions, skippedRegions, totalAnchors, timingAccumulator.snapshot());
    }

    private AroundOperationResult generateAroundParallel(
        ServerLevel level,
        CivWorldState state,
        CivPlacementConfig config,
        RegionKey centerRegion,
        List<RegionKey> regions,
        boolean force
    ) {
        int stride = parallelBucketStride(config);
        Map<BucketKey, List<RegionKey>> buckets = bucketizeRegions(regions, stride);
        List<BucketKey> orderedBuckets = new ArrayList<>(buckets.keySet());
        orderedBuckets.sort(Comparator.comparingInt(BucketKey::bucketX).thenComparingInt(BucketKey::bucketZ));

        List<BlockPos> runningCapitalCenters = existingCapitalCenters(state, null);
        int totalRegions = 0;
        int skippedRegions = 0;
        int totalAnchors = 0;
        RunTimingAccumulator timingAccumulator = new RunTimingAccumulator();

        int threadCount = configuredParallelRegionThreads(config, regions.size());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, new PlanningThreadFactory());
        try {
            for (BucketKey bucket : orderedBuckets) {
                List<RegionPlanTask> planTasks = new ArrayList<>();
                for (RegionKey region : buckets.get(bucket)) {
                    List<BlockPos> existingRegionCapitals = capitalCentersInRegion(state, region);
                    if (state.isRegionPlanned(region) && !force) {
                        totalRegions++;
                        skippedRegions++;
                        continue;
                    }

                    List<BlockPos> preoccupied = copyWithout(runningCapitalCenters, existingRegionCapitals);
                    CompletableFuture<PlannedRegion> future = CompletableFuture.supplyAsync(
                        () -> planRegionOnly(level, region, config, preoccupied),
                        executor
                    );
                    planTasks.add(new RegionPlanTask(region, existingRegionCapitals, future));
                }

                planTasks.sort(Comparator.comparingLong(task -> task.region().asLong()));
                for (RegionPlanTask task : planTasks) {
                    PlannedRegion planned = task.future().join();
                    long persistStartNanos = System.nanoTime();
                    state.replaceRegionPlan(planned.regionKey(), planned.result().anchors());
                    PlacementDebugSnapshotStore.put(level.getServer(), planned.result());
                    long persistMillis = nanosToMillis(System.nanoTime() - persistStartNanos);

                    removeCenters(runningCapitalCenters, task.existingCapitals());
                    runningCapitalCenters.addAll(capitalCenters(planned.result().anchors()));

                    totalRegions++;
                    totalAnchors += planned.result().anchors().size();
                    RegionOperationResult regionResult = new RegionOperationResult(
                        planned.regionKey(),
                        planned.result().anchors().size(),
                        planned.acceptedCandidates(),
                        false,
                        nanosToMillis(planned.planNanos()),
                        persistMillis,
                        planned.planningMetrics()
                    );
                    timingAccumulator.add(regionResult);

                    LOGGER.info(
                        "[kingdom] region {},{} force={} skipped=false anchors={} accepted_candidates={} "
                            + "plan_ms={} persist_ms={} seed_ms={} sample_ms={} cluster_ms={} flatten_ms={} "
                            + "sat_attempts={} sat_samples={} sat_placed={} total_ms={}",
                        planned.regionKey().x(),
                        planned.regionKey().z(),
                        force,
                        planned.result().anchors().size(),
                        planned.acceptedCandidates(),
                        nanosToMillis(planned.planNanos()),
                        persistMillis,
                        nanosToMillis(planned.planningMetrics().candidateSeedNanos()),
                        nanosToMillis(planned.planningMetrics().candidateSamplingNanos()),
                        nanosToMillis(planned.planningMetrics().clusterGenerationNanos()),
                        nanosToMillis(planned.planningMetrics().anchorCollectionNanos()),
                        planned.planningMetrics().clusterMetrics().satellitePlacementAttempts(),
                        planned.planningMetrics().clusterMetrics().satelliteSuitabilitySamples(),
                        planned.planningMetrics().clusterMetrics().satellitesPlaced(),
                        nanosToMillis(planned.planNanos()) + persistMillis
                    );
                }
            }
        } finally {
            executor.shutdownNow();
        }

        return new AroundOperationResult(centerRegion, totalRegions, skippedRegions, totalAnchors, timingAccumulator.snapshot());
    }

    private RegionOperationResult planAndPersistRegion(
        ServerLevel level,
        CivWorldState state,
        CivPlacementConfig config,
        RegionKey regionKey,
        boolean force,
        List<BlockPos> preoccupiedCenters
    ) {
        if (state.isRegionPlanned(regionKey) && !force) {
            return new RegionOperationResult(regionKey, 0, 0, true, 0L, 0L, null);
        }

        long planStartNanos = System.nanoTime();
        CivPlacementPlanner.RegionPlanComputation computation = planner.planRegionWithMetrics(level, regionKey, config, preoccupiedCenters);
        RegionPlacementResult result = computation.result();
        CivPlacementPlanner.RegionPlanMetrics planningMetrics = computation.metrics();
        long planMillis = nanosToMillis(System.nanoTime() - planStartNanos);

        long persistStartNanos = System.nanoTime();
        state.replaceRegionPlan(regionKey, result.anchors());
        PlacementDebugSnapshotStore.put(level.getServer(), result);
        long persistMillis = nanosToMillis(System.nanoTime() - persistStartNanos);

        int acceptedCandidates = (int) result.candidates().stream().filter(candidate -> candidate.accepted()).count();
        LOGGER.info(
            "[kingdom] region {},{} force={} skipped=false anchors={} accepted_candidates={} "
                + "plan_ms={} persist_ms={} seed_ms={} sample_ms={} cluster_ms={} flatten_ms={} "
                + "sat_attempts={} sat_samples={} sat_placed={} total_ms={}",
            regionKey.x(),
            regionKey.z(),
            force,
            result.anchors().size(),
            acceptedCandidates,
            planMillis,
            persistMillis,
            nanosToMillis(planningMetrics.candidateSeedNanos()),
            nanosToMillis(planningMetrics.candidateSamplingNanos()),
            nanosToMillis(planningMetrics.clusterGenerationNanos()),
            nanosToMillis(planningMetrics.anchorCollectionNanos()),
            planningMetrics.clusterMetrics().satellitePlacementAttempts(),
            planningMetrics.clusterMetrics().satelliteSuitabilitySamples(),
            planningMetrics.clusterMetrics().satellitesPlaced(),
            planMillis + persistMillis
        );

        return new RegionOperationResult(regionKey, result.anchors().size(), acceptedCandidates, false, planMillis, persistMillis, planningMetrics);
    }

    private PlannedRegion planRegionOnly(
        ServerLevel level,
        RegionKey regionKey,
        CivPlacementConfig config,
        List<BlockPos> preoccupiedCenters
    ) {
        long startNanos = System.nanoTime();
        CivPlacementPlanner.RegionPlanComputation computation = planner.planRegionWithMetrics(level, regionKey, config, preoccupiedCenters);
        RegionPlacementResult result = computation.result();
        int acceptedCandidates = (int) result.candidates().stream().filter(candidate -> candidate.accepted()).count();
        long planNanos = System.nanoTime() - startNanos;
        return new PlannedRegion(regionKey, result, acceptedCandidates, planNanos, computation.metrics());
    }

    private static List<RegionKey> regionsInRadius(RegionKey centerRegion, int radiusRegions) {
        List<RegionKey> regions = new ArrayList<>((radiusRegions * 2 + 1) * (radiusRegions * 2 + 1));
        for (int dx = -radiusRegions; dx <= radiusRegions; dx++) {
            for (int dz = -radiusRegions; dz <= radiusRegions; dz++) {
                regions.add(new RegionKey(centerRegion.x() + dx, centerRegion.z() + dz));
            }
        }
        return regions;
    }

    private static int parallelBucketStride(CivPlacementConfig config) {
        int regionSize = Math.max(1, config.regionSizeBlocks());
        int kingdomSpacing = Math.max(config.minAnchorSpacingBlocks(), (int) Math.round(config.minAnchorSpacingBlocks() * 2.0D));
        return Math.max(1, Math.floorDiv(Math.max(0, kingdomSpacing - 1), regionSize) + 2);
    }

    private static boolean isParallelRegionPlanningEnabled(CivPlacementConfig config) {
        String override = System.getProperty("kingdom.parallelRegionPlanning");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return config.parallelRegionPlanning();
    }

    private static int configuredParallelRegionThreads(CivPlacementConfig config, int regionCount) {
        int autoThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int configuredThreads = config.parallelRegionThreads() == 0 ? autoThreads : config.parallelRegionThreads();

        String override = System.getProperty("kingdom.parallelRegionThreads");
        if (override != null) {
            try {
                configuredThreads = Integer.parseInt(override);
            } catch (NumberFormatException error) {
                LOGGER.warn("[kingdom] invalid kingdom.parallelRegionThreads='{}'; using config/default value.", override);
            }
        }

        return Math.max(1, Math.min(configuredThreads, regionCount));
    }

    private static Map<BucketKey, List<RegionKey>> bucketizeRegions(List<RegionKey> regions, int stride) {
        Map<BucketKey, List<RegionKey>> buckets = new LinkedHashMap<>();
        for (RegionKey region : regions) {
            BucketKey bucket = new BucketKey(Math.floorMod(region.x(), stride), Math.floorMod(region.z(), stride));
            buckets.computeIfAbsent(bucket, unused -> new ArrayList<>()).add(region);
        }
        return buckets;
    }

    private static void logRunTimingSummary(RunTimingSummary summary, long wallClockTotalMillis, String mode) {
        if (summary.plannedRegions() <= 0) {
            return;
        }

        long stagePlanMillis = summary.candidateSeedMillis()
            + summary.candidateSamplingMillis()
            + summary.clusterGenerationMillis()
            + summary.anchorCollectionMillis();
        long avgPlanMillis = Math.round(summary.plannerTotalMillis() / (double) summary.plannedRegions());
        long avgPersistMillis = Math.round(summary.persistTotalMillis() / (double) summary.plannedRegions());
        double samplingPct = percentage(summary.candidateSamplingMillis(), summary.plannerTotalMillis());
        double clusterPct = percentage(summary.clusterGenerationMillis(), summary.plannerTotalMillis());
        String slowestRegions = formatSlowestRegions(summary.slowestRegionsByPlanMs());

        LOGGER.info(
            "[kingdom] run_timing mode={} planned_regions={} planner_ms={} persist_ms={} wall_clock_ms={} "
                + "avg_plan_ms={} avg_persist_ms={} stage_ms(seed={}, sample={}, cluster={}, flatten={}) "
                + "stage_pct(sample={}%, cluster={}%) candidates(seeds={}, total={}, accepted={}) "
                + "clusters(capitals={}, tribes_total={}, tribes_promoted={}, tribes_backfill={}, backfill_eligible={}) "
                + "satellites(requested={}, placed={}, attempts={}, sampled_points={}, unique_positions={}) "
                + "slowest_regions={}",
            mode,
            summary.plannedRegions(),
            summary.plannerTotalMillis(),
            summary.persistTotalMillis(),
            wallClockTotalMillis,
            avgPlanMillis,
            avgPersistMillis,
            summary.candidateSeedMillis(),
            summary.candidateSamplingMillis(),
            summary.clusterGenerationMillis(),
            summary.anchorCollectionMillis(),
            formatPct(samplingPct),
            formatPct(clusterPct),
            summary.generatedSeedCount(),
            summary.candidateCount(),
            summary.acceptedCandidateCount(),
            summary.capitalsPlaced(),
            summary.totalTribesPlaced(),
            summary.promotedTribesPlaced(),
            summary.backfillTribesPlaced(),
            summary.backfillEligibleCandidateCount(),
            summary.satellitesRequested(),
            summary.satellitesPlaced(),
            summary.satellitePlacementAttempts(),
            summary.satelliteSuitabilitySamples(),
            summary.uniqueSatelliteSamplePositions(),
            slowestRegions
        );

        if (stagePlanMillis > 0 && summary.plannerTotalMillis() > stagePlanMillis) {
            LOGGER.info(
                "[kingdom] run_timing planner_overhead_ms={} (planner_total_ms={} stage_total_ms={})",
                summary.plannerTotalMillis() - stagePlanMillis,
                summary.plannerTotalMillis(),
                stagePlanMillis
            );
        }
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static double percentage(long part, long total) {
        if (total <= 0L) {
            return 0.0D;
        }
        return (part * 100.0D) / total;
    }

    private static String formatPct(double pct) {
        return String.format(java.util.Locale.ROOT, "%.1f", pct);
    }

    private static String formatSlowestRegions(List<RegionTimingBreakdown> slowestRegionsByPlanMs) {
        if (slowestRegionsByPlanMs.isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < slowestRegionsByPlanMs.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            RegionTimingBreakdown breakdown = slowestRegionsByPlanMs.get(i);
            builder.append('(')
                .append(breakdown.regionKey().x())
                .append(',')
                .append(breakdown.regionKey().z())
                .append(":plan=")
                .append(breakdown.planMillis())
                .append("ms sample=")
                .append(breakdown.sampleMillis())
                .append("ms cluster=")
                .append(breakdown.clusterMillis())
                .append("ms)");
        }
        builder.append(']');
        return builder.toString();
    }

    private static void initializeStateMetadata(ServerLevel level, CivWorldState state) {
        state.setWorldSeedHash(level.getSeed());
        state.setRegionGenerationVersion(CivPlacementPlanner.REGION_GENERATION_VERSION);
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

    private static List<BlockPos> existingCapitalCenters(CivWorldState state, RegionKey excludedRegion) {
        List<BlockPos> centers = new ArrayList<>();
        Long excludedRegionKey = excludedRegion == null ? null : excludedRegion.asLong();

        for (Map.Entry<Long, it.unimi.dsi.fastutil.longs.LongList> entry : state.anchorsByRegion().entrySet()) {
            if (excludedRegionKey != null && entry.getKey().equals(excludedRegionKey)) {
                continue;
            }

            for (long anchorId : entry.getValue()) {
                SettlementAnchor anchor = state.anchor(anchorId);
                if (anchor != null && anchor.type() == SettlementType.KINGDOM_CAPITAL) {
                    centers.add(anchor.center());
                }
            }
        }

        return centers;
    }

    private static List<BlockPos> capitalCentersInRegion(CivWorldState state, RegionKey regionKey) {
        List<BlockPos> centers = new ArrayList<>();
        for (SettlementAnchor anchor : state.anchorsInRegion(regionKey)) {
            if (anchor.type() == SettlementType.KINGDOM_CAPITAL) {
                centers.add(anchor.center());
            }
        }
        return centers;
    }

    private static List<BlockPos> capitalCenters(List<SettlementAnchor> anchors) {
        List<BlockPos> centers = new ArrayList<>();
        for (SettlementAnchor anchor : anchors) {
            if (anchor.type() == SettlementType.KINGDOM_CAPITAL) {
                centers.add(anchor.center());
            }
        }
        return centers;
    }

    private static List<BlockPos> copyWithout(List<BlockPos> source, List<BlockPos> excluded) {
        if (excluded.isEmpty()) {
            return List.copyOf(source);
        }

        Map<Long, Integer> counts = new HashMap<>();
        for (BlockPos excludedPos : excluded) {
            counts.merge(excludedPos.asLong(), 1, Integer::sum);
        }

        List<BlockPos> filtered = new ArrayList<>(source.size());
        for (BlockPos pos : source) {
            int count = counts.getOrDefault(pos.asLong(), 0);
            if (count > 0) {
                if (count == 1) {
                    counts.remove(pos.asLong());
                } else {
                    counts.put(pos.asLong(), count - 1);
                }
                continue;
            }
            filtered.add(pos);
        }
        return filtered;
    }

    private static void removeCenters(List<BlockPos> target, List<BlockPos> toRemove) {
        if (toRemove.isEmpty() || target.isEmpty()) {
            return;
        }

        Map<Long, Integer> counts = new HashMap<>();
        for (BlockPos removedPos : toRemove) {
            counts.merge(removedPos.asLong(), 1, Integer::sum);
        }

        target.removeIf(pos -> {
            int count = counts.getOrDefault(pos.asLong(), 0);
            if (count <= 0) {
                return false;
            }
            if (count == 1) {
                counts.remove(pos.asLong());
            } else {
                counts.put(pos.asLong(), count - 1);
            }
            return true;
        });
    }

    private record BucketKey(int bucketX, int bucketZ) {
    }

    private record RegionPlanTask(
        RegionKey region,
        List<BlockPos> existingCapitals,
        CompletableFuture<PlannedRegion> future
    ) {
    }

    private record PlannedRegion(
        RegionKey regionKey,
        RegionPlacementResult result,
        int acceptedCandidates,
        long planNanos,
        CivPlacementPlanner.RegionPlanMetrics planningMetrics
    ) {
    }

    private static final class RunTimingAccumulator {
        private int plannedRegions;
        private long plannerTotalMillis;
        private long persistTotalMillis;
        private long candidateSeedMillis;
        private long candidateSamplingMillis;
        private long clusterGenerationMillis;
        private long anchorCollectionMillis;
        private int generatedSeedCount;
        private int candidateCount;
        private int acceptedCandidateCount;
        private int capitalsPlaced;
        private int promotedTribesPlaced;
        private int backfillTribesPlaced;
        private int backfillEligibleCandidateCount;
        private int satellitesRequested;
        private int satellitesPlaced;
        private int satellitePlacementAttempts;
        private int satelliteSuitabilitySamples;
        private int uniqueSatelliteSamplePositions;
        private final List<RegionTimingBreakdown> slowestRegionsByPlanMs = new ArrayList<>();

        private void add(RegionOperationResult result) {
            if (result.skipped() || result.planningMetrics() == null) {
                return;
            }

            plannedRegions++;
            plannerTotalMillis += result.planMillis();
            persistTotalMillis += result.persistMillis();

            CivPlacementPlanner.RegionPlanMetrics metrics = result.planningMetrics();
            candidateSeedMillis += nanosToMillis(metrics.candidateSeedNanos());
            candidateSamplingMillis += nanosToMillis(metrics.candidateSamplingNanos());
            clusterGenerationMillis += nanosToMillis(metrics.clusterGenerationNanos());
            anchorCollectionMillis += nanosToMillis(metrics.anchorCollectionNanos());

            generatedSeedCount += metrics.generatedSeedCount();
            candidateCount += metrics.candidateCount();
            acceptedCandidateCount += metrics.acceptedCandidateCount();

            var cluster = metrics.clusterMetrics();
            capitalsPlaced += cluster.capitalsPlaced();
            promotedTribesPlaced += cluster.promotedTribesPlaced();
            backfillTribesPlaced += cluster.backfillTribesPlaced();
            backfillEligibleCandidateCount += cluster.backfillEligibleCandidateCount();
            satellitesRequested += cluster.satellitesRequested();
            satellitesPlaced += cluster.satellitesPlaced();
            satellitePlacementAttempts += cluster.satellitePlacementAttempts();
            satelliteSuitabilitySamples += cluster.satelliteSuitabilitySamples();
            uniqueSatelliteSamplePositions += cluster.uniqueSatelliteSamplePositions();

            slowestRegionsByPlanMs.add(
                new RegionTimingBreakdown(
                    result.regionKey(),
                    result.planMillis(),
                    nanosToMillis(metrics.candidateSamplingNanos()),
                    nanosToMillis(metrics.clusterGenerationNanos())
                )
            );
            slowestRegionsByPlanMs.sort(Comparator.comparingLong(RegionTimingBreakdown::planMillis).reversed());
            if (slowestRegionsByPlanMs.size() > 5) {
                slowestRegionsByPlanMs.remove(slowestRegionsByPlanMs.size() - 1);
            }
        }

        private RunTimingSummary snapshot() {
            return new RunTimingSummary(
                plannedRegions,
                plannerTotalMillis,
                persistTotalMillis,
                candidateSeedMillis,
                candidateSamplingMillis,
                clusterGenerationMillis,
                anchorCollectionMillis,
                generatedSeedCount,
                candidateCount,
                acceptedCandidateCount,
                capitalsPlaced,
                promotedTribesPlaced,
                backfillTribesPlaced,
                backfillEligibleCandidateCount,
                satellitesRequested,
                satellitesPlaced,
                satellitePlacementAttempts,
                satelliteSuitabilitySamples,
                uniqueSatelliteSamplePositions,
                List.copyOf(slowestRegionsByPlanMs)
            );
        }
    }

    public record RunTimingSummary(
        int plannedRegions,
        long plannerTotalMillis,
        long persistTotalMillis,
        long candidateSeedMillis,
        long candidateSamplingMillis,
        long clusterGenerationMillis,
        long anchorCollectionMillis,
        int generatedSeedCount,
        int candidateCount,
        int acceptedCandidateCount,
        int capitalsPlaced,
        int promotedTribesPlaced,
        int backfillTribesPlaced,
        int backfillEligibleCandidateCount,
        int satellitesRequested,
        int satellitesPlaced,
        int satellitePlacementAttempts,
        int satelliteSuitabilitySamples,
        int uniqueSatelliteSamplePositions,
        List<RegionTimingBreakdown> slowestRegionsByPlanMs
    ) {
        public RunTimingSummary {
            slowestRegionsByPlanMs = List.copyOf(slowestRegionsByPlanMs);
        }

        public int totalTribesPlaced() {
            return promotedTribesPlaced + backfillTribesPlaced;
        }
    }

    public record RegionTimingBreakdown(RegionKey regionKey, long planMillis, long sampleMillis, long clusterMillis) {
    }

    private static final class PlanningThreadFactory implements ThreadFactory {
        private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "kingdom-region-plan-" + NEXT_ID.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    public record RegionOperationResult(
        RegionKey regionKey,
        int generatedAnchors,
        int acceptedCandidates,
        boolean skipped,
        long planMillis,
        long persistMillis,
        CivPlacementPlanner.RegionPlanMetrics planningMetrics
    ) {
    }

    public record AroundOperationResult(
        RegionKey centerRegion,
        int totalRegions,
        int skippedRegions,
        int generatedAnchors,
        RunTimingSummary timingSummary
    ) {
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
