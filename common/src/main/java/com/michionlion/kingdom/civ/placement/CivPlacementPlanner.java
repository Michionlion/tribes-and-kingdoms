package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.TechTier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class CivPlacementPlanner {
    public static final int REGION_GENERATION_VERSION = 2;

    public RegionPlacementResult planRegion(ServerLevel level, RegionKey regionKey, CivPlacementConfig config) {
        return planRegion(level, regionKey, config, List.of());
    }

    public RegionPlacementResult planRegion(
        ServerLevel level,
        RegionKey regionKey,
        CivPlacementConfig config,
        List<BlockPos> preoccupiedCenters
    ) {
        return planRegionWithMetrics(level, regionKey, config, preoccupiedCenters).result();
    }

    public RegionPlanComputation planRegionWithMetrics(
        ServerLevel level,
        RegionKey regionKey,
        CivPlacementConfig config,
        List<BlockPos> preoccupiedCenters
    ) {
        long worldSeedHash = level.getSeed();
        int regionSize = config.regionSizeBlocks();
        int cellSize = config.candidateCellSizeBlocks();
        int cellsPerAxis = Math.max(1, regionSize / Math.max(1, cellSize));

        int originX = Math.multiplyExact(regionKey.x(), regionSize);
        int originZ = Math.multiplyExact(regionKey.z(), regionSize);

        long seedStartNanos = System.nanoTime();
        List<CandidateSeed> generated = new ArrayList<>(cellsPerAxis * cellsPerAxis);
        for (int cx = 0; cx < cellsPerAxis; cx++) {
            for (int cz = 0; cz < cellsPerAxis; cz++) {
                long cellKey = ((((long) cx) & 0xFFFFFFFFL) << 32) | (((long) cz) & 0xFFFFFFFFL);
                long seed = mix64(worldSeedHash ^ regionKey.asLong() ^ cellKey ^ 0xA24BAED4963EE407L);
                Random random = new Random(seed);

                int x = originX + (cx * cellSize) + random.nextInt(cellSize);
                int z = originZ + (cz * cellSize) + random.nextInt(cellSize);
                long deterministicKey = mix64(seed ^ 0x9E3779B97F4A7C15L);

                generated.add(new CandidateSeed(deterministicKey, x, z));
            }
        }

        generated.sort(Comparator.comparingLong(CandidateSeed::deterministicKey));
        if (generated.size() > config.maxCandidatesPerRegion()) {
            generated = new ArrayList<>(generated.subList(0, config.maxCandidatesPerRegion()));
        }
        long seedNanos = System.nanoTime() - seedStartNanos;

        long sampleStartNanos = System.nanoTime();
        List<PlacementCandidate> candidates = new ArrayList<>(generated.size());
        for (CandidateSeed seed : generated) {
            SuitabilitySampler.SuitabilitySample sampled = SuitabilitySampler.sampleAt(level, seed.x(), seed.z(), config);
            BlockPos center = new BlockPos(seed.x(), sampled.surfaceY(), seed.z());
            Identifier biomeId = sampled.biomeId();
            SuitabilityScore score = sampled.score();
            TechTier tier = config.tierForScore(score.total());
            boolean biomeAllowed = SuitabilitySampler.isLandPlacementBiome(biomeId.getPath());
            boolean accepted = biomeAllowed && config.passesMinimumThreshold(score.total());
            String rejectionReason = accepted
                ? ""
                : (biomeAllowed ? "score_below_threshold" : "biome_oceanic_or_coastal");

            PlacementCandidate candidate = new PlacementCandidate(
                regionKey,
                seed.deterministicKey(),
                center,
                biomeId,
                score,
                tier,
                accepted,
                rejectionReason
            );

            candidates.add(candidate);
        }
        long sampleNanos = System.nanoTime() - sampleStartNanos;

        long clusterStartNanos = System.nanoTime();
        ClusterGenerator.ClusterGenerationResult clusterResult = ClusterGenerator.generateClustersWithMetrics(
            level,
            regionKey,
            worldSeedHash,
            config,
            candidates,
            preoccupiedCenters
        );
        List<ClusterPlan> clusters = clusterResult.clusters();
        long clusterNanos = System.nanoTime() - clusterStartNanos;

        long flattenStartNanos = System.nanoTime();
        List<SettlementAnchor> anchors = new ArrayList<>();
        for (ClusterPlan cluster : clusters) {
            anchors.addAll(cluster.allAnchors());
        }

        anchors.sort(Comparator.comparingLong(SettlementAnchor::id));
        long flattenNanos = System.nanoTime() - flattenStartNanos;
        int acceptedCandidates = (int) candidates.stream().filter(PlacementCandidate::accepted).count();

        RegionPlacementResult result = new RegionPlacementResult(regionKey, List.copyOf(anchors), List.copyOf(candidates));
        RegionPlanMetrics metrics = new RegionPlanMetrics(
            seedNanos,
            sampleNanos,
            clusterNanos,
            flattenNanos,
            generated.size(),
            candidates.size(),
            acceptedCandidates,
            clusterResult.metrics()
        );
        return new RegionPlanComputation(result, metrics);
    }

    public RegionKey regionAt(int blockX, int blockZ, CivPlacementConfig config) {
        int regionSize = config.regionSizeBlocks();
        return new RegionKey(Math.floorDiv(blockX, regionSize), Math.floorDiv(blockZ, regionSize));
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private record CandidateSeed(long deterministicKey, int x, int z) {
    }

    public record RegionPlanMetrics(
        long candidateSeedNanos,
        long candidateSamplingNanos,
        long clusterGenerationNanos,
        long anchorCollectionNanos,
        int generatedSeedCount,
        int candidateCount,
        int acceptedCandidateCount,
        ClusterGenerator.ClusterGenerationMetrics clusterMetrics
    ) {
        public long totalNanos() {
            return candidateSeedNanos + candidateSamplingNanos + clusterGenerationNanos + anchorCollectionNanos;
        }
    }

    public record RegionPlanComputation(RegionPlacementResult result, RegionPlanMetrics metrics) {
    }
}
