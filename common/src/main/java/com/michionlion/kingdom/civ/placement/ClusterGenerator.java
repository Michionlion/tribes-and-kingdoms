package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.util.AnchorIdGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ClusterGenerator {
    private ClusterGenerator() {
    }

    public static List<ClusterPlan> generateClusters(
        ServerLevel level,
        RegionKey regionKey,
        long worldSeedHash,
        CivPlacementConfig config,
        List<PlacementCandidate> candidates,
        List<BlockPos> preoccupiedCenters
    ) {
        return generateClustersWithMetrics(level, regionKey, worldSeedHash, config, candidates, preoccupiedCenters).clusters();
    }

    public static ClusterGenerationResult generateClustersWithMetrics(
        ServerLevel level,
        RegionKey regionKey,
        long worldSeedHash,
        CivPlacementConfig config,
        List<PlacementCandidate> candidates,
        List<BlockPos> preoccupiedCenters
    ) {
        List<PlacementCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator
            .comparingDouble((PlacementCandidate candidate) -> candidate.score().total()).reversed()
            .thenComparingLong(PlacementCandidate::deterministicKey));

        List<BlockPos> selectedCenters = new ArrayList<>();
        List<BlockPos> occupiedCenters = new ArrayList<>();
        List<BlockPos> capitalCenters = new ArrayList<>();
        if (preoccupiedCenters != null && !preoccupiedCenters.isEmpty()) {
            selectedCenters.addAll(preoccupiedCenters);
            occupiedCenters.addAll(preoccupiedCenters);
            capitalCenters.addAll(preoccupiedCenters);
        }
        int kingdomSpacing = Math.max(config.minAnchorSpacingBlocks(), (int) Math.round(config.minAnchorSpacingBlocks() * 2.0D));
        Set<Long> consumedCandidateKeys = new HashSet<>();
        Map<Long, SuitabilitySampler.SuitabilitySample> sampledSuitabilityByPos = new HashMap<>();
        List<ClusterPlan> clusters = new ArrayList<>();
        int localOrdinal = 0;
        int initialAcceptedCandidates = 0;
        int capitalsPlaced = 0;
        int tribesPromoted = 0;
        int satellitesRequested = 0;
        int satellitesPlaced = 0;
        int satelliteAttempts = 0;
        int satelliteSuitabilitySamples = 0;
        long totalStartNanos = System.nanoTime();
        long primaryPassStartNanos = System.nanoTime();

        for (PlacementCandidate candidate : ordered) {
            if (!candidate.accepted()) {
                continue;
            }
            initialAcceptedCandidates++;
            if (!SuitabilitySampler.isLandPlacementBiome(candidate.biomeId().getPath())) {
                candidate.reject("biome_oceanic_or_coastal");
                continue;
            }

            if (isTooClose(candidate.center(), selectedCenters, kingdomSpacing)) {
                candidate.reject("spacing_conflict");
                continue;
            }

            if (shouldPromoteToTribe(candidate, selectedCenters, config, kingdomSpacing)) {
                long tribeId = AnchorIdGenerator.deterministicId(worldSeedHash, regionKey, localOrdinal++);
                candidate.setAssignedTier(TechTier.WOOD);
                candidate.accept();
                clusters.add(new ClusterPlan(createTribeAnchor(candidate, tribeId, config), List.of()));
                tribesPromoted++;
                selectedCenters.add(candidate.center());
                occupiedCenters.add(candidate.center());
                consumedCandidateKeys.add(candidate.deterministicKey());
                continue;
            }

            TechTier capitalTier = SettlementTierPolicy.chooseCapitalTier(candidate.score().total(), candidate.deterministicKey(), config);
            candidate.setAssignedTier(capitalTier);
            candidate.accept();

            long capitalId = AnchorIdGenerator.deterministicId(worldSeedHash, regionKey, localOrdinal++);
            long civId = capitalId;
            SettlementAnchor capital = new SettlementAnchor(
                capitalId,
                candidate.center(),
                capitalTier,
                SettlementType.KINGDOM_CAPITAL,
                config.radiusForTier(capitalTier),
                civId,
                inferBiomeTags(candidate.biomeId().toString())
            );
            selectedCenters.add(candidate.center());
            occupiedCenters.add(candidate.center());
            capitalCenters.add(candidate.center());
            consumedCandidateKeys.add(candidate.deterministicKey());

            int minSatellites = config.satelliteMinForTier(capitalTier);
            int maxSatellites = config.satelliteMaxForTier(capitalTier);
            int satelliteCount = minSatellites;

            if (maxSatellites > minSatellites) {
                Random random = new Random(mix64(candidate.deterministicKey() ^ 0x6A09E667F3BCC909L));
                satelliteCount = minSatellites + random.nextInt(maxSatellites - minSatellites + 1);
            }
            capitalsPlaced++;
            satellitesRequested += satelliteCount;

            List<SettlementAnchor> satellites = new ArrayList<>(satelliteCount);
            Random satelliteRandom = new Random(mix64(candidate.deterministicKey() ^ 0xBB67AE8584CAA73BL));
            int satelliteSpacing = Math.max(48, (int) Math.round(config.satelliteMinDistanceBlocks() * 0.75D));
            int maxAttemptsPerSatellite = 12;
            int maxValidOptionsPerSatellite = 3;

            for (int i = 0; i < satelliteCount; i++) {
                SatellitePlacementOption bestOption = null;
                int validOptionsEvaluated = 0;
                List<SatelliteAttempt> attempts = new ArrayList<>(maxAttemptsPerSatellite);
                for (int attempt = 0; attempt < maxAttemptsPerSatellite; attempt++) {
                    double angle = satelliteRandom.nextDouble() * (Math.PI * 2.0D);
                    int distance = config.satelliteMinDistanceBlocks();
                    if (config.satelliteMaxDistanceBlocks() > config.satelliteMinDistanceBlocks()) {
                        distance += satelliteRandom.nextInt(config.satelliteMaxDistanceBlocks() - config.satelliteMinDistanceBlocks() + 1);
                    }

                    int sx = candidate.center().getX() + (int) Math.round(Math.cos(angle) * distance);
                    int sz = candidate.center().getZ() + (int) Math.round(Math.sin(angle) * distance);
                    attempts.add(new SatelliteAttempt(sx, sz, distance));
                }
                attempts.sort(
                    Comparator.comparingInt(SatelliteAttempt::distanceFromCapital)
                        .thenComparingInt(SatelliteAttempt::x)
                        .thenComparingInt(SatelliteAttempt::z)
                );

                for (SatelliteAttempt attempt : attempts) {
                    satelliteAttempts++;
                    int sx = attempt.x();
                    int sz = attempt.z();
                    if (isTooCloseXZ(sx, sz, occupiedCenters, satelliteSpacing)) {
                        continue;
                    }

                    satelliteSuitabilitySamples++;
                    SuitabilitySampler.SuitabilitySample sampled = sampleSuitability(sampledSuitabilityByPos, level, sx, sz, config);
                    if (!SuitabilitySampler.isLandPlacementBiome(sampled.biomeId().getPath())) {
                        continue;
                    }
                    if (!config.passesMinimumThreshold(sampled.score().total())) {
                        continue;
                    }

                    BlockPos proposed = new BlockPos(sx, sampled.surfaceY(), sz);
                    if (
                        bestOption == null
                            || sampled.score().total() > bestOption.suitability().total()
                            || (
                                sampled.score().total() == bestOption.suitability().total()
                                    && attempt.distanceFromCapital() < bestOption.distanceFromCapital()
                            )
                    ) {
                        bestOption = new SatellitePlacementOption(proposed, sampled.biomeId(), attempt.distanceFromCapital(), sampled.score());
                    }

                    validOptionsEvaluated++;
                    if (validOptionsEvaluated >= maxValidOptionsPerSatellite) {
                        break;
                    }
                }

                if (bestOption == null) {
                    continue;
                }

                SettlementType type = i < 2 ? SettlementType.KINGDOM_TOWN : SettlementType.OUTPOST;
                double proximity = proximityToCapital(
                    bestOption.distanceFromCapital(),
                    config.satelliteMinDistanceBlocks(),
                    config.satelliteMaxDistanceBlocks()
                );
                long tierKey = candidate.deterministicKey() ^ mix64((((long) i) << 32) ^ bestOption.distanceFromCapital());
                TechTier satelliteTier = SettlementTierPolicy.chooseSatelliteTier(capitalTier, type, proximity, tierKey);
                long satelliteId = AnchorIdGenerator.deterministicId(worldSeedHash, regionKey, localOrdinal++);

                SettlementAnchor satellite = new SettlementAnchor(
                    satelliteId,
                    bestOption.center(),
                    satelliteTier,
                    type,
                    Math.max(16, config.radiusForTier(satelliteTier) / 2),
                    civId,
                    inferBiomeTags(bestOption.biomeId().toString())
                );
                satellites.add(satellite);
                satellitesPlaced++;
                occupiedCenters.add(bestOption.center());
            }

            clusters.add(new ClusterPlan(capital, satellites));
        }

        long primaryPassNanos = System.nanoTime() - primaryPassStartNanos;
        long backfillStartNanos = System.nanoTime();
        BackfillResult backfillResult = addSparseAreaTribes(
            ordered,
            worldSeedHash,
            regionKey,
            config,
            localOrdinal,
            occupiedCenters,
            kingdomSpacing,
            consumedCandidateKeys,
            capitalCenters,
            clusters
        );
        long backfillNanos = System.nanoTime() - backfillStartNanos;

        ClusterGenerationMetrics metrics = new ClusterGenerationMetrics(
            System.nanoTime() - totalStartNanos,
            primaryPassNanos,
            backfillNanos,
            ordered.size(),
            initialAcceptedCandidates,
            capitalsPlaced,
            tribesPromoted,
            backfillResult.addedTribes(),
            backfillResult.eligibleCandidates(),
            satellitesRequested,
            satellitesPlaced,
            satelliteAttempts,
            satelliteSuitabilitySamples,
            sampledSuitabilityByPos.size()
        );
        return new ClusterGenerationResult(List.copyOf(clusters), metrics);
    }

    private static BackfillResult addSparseAreaTribes(
        List<PlacementCandidate> ordered,
        long worldSeedHash,
        RegionKey regionKey,
        CivPlacementConfig config,
        int localOrdinal,
        List<BlockPos> occupiedCenters,
        int kingdomSpacing,
        Set<Long> consumedCandidateKeys,
        List<BlockPos> capitalCenters,
        List<ClusterPlan> clusters
    ) {
        int kingdomCount = 0;
        List<BlockPos> nonTribeCenters = new ArrayList<>();
        for (ClusterPlan cluster : clusters) {
            if (cluster.capital().type() == SettlementType.KINGDOM_CAPITAL) {
                kingdomCount++;
            }
            for (SettlementAnchor anchor : cluster.allAnchors()) {
                if (anchor.type() != SettlementType.TRIBE) {
                    nonTribeCenters.add(anchor.center());
                }
            }
        }

        int availableCandidates = Math.max(0, ordered.size() - consumedCandidateKeys.size());
        int minimumExtraTribes = Math.max(2, (int) Math.round(kingdomCount * 0.40D));
        int targetExtraTribes = Math.min(availableCandidates, Math.max(minimumExtraTribes, (availableCandidates * 2) / 3));
        int remotePriorityQuota = Math.max(1, (int) Math.round(targetExtraTribes * 0.50D));
        int tribeSpacing = Math.max(40, (int) Math.round(config.minAnchorSpacingBlocks() * 0.14D));
        double remoteThreshold = kingdomSpacing * 0.55D;
        int minCapitalBufferDistance = config.satelliteMaxDistanceBlocks() + (config.radiusForTier(TechTier.WOOD) * 4);

        List<TribeBackfillCandidate> eligible = new ArrayList<>();
        for (PlacementCandidate candidate : ordered) {
            if (consumedCandidateKeys.contains(candidate.deterministicKey())) {
                continue;
            }
            if (!SuitabilitySampler.isLandPlacementBiome(candidate.biomeId().getPath())) {
                continue;
            }
            if (isTooClose(candidate.center(), capitalCenters, minCapitalBufferDistance)) {
                continue;
            }

            double nearestDistance = nearestDistance(candidate.center(), nonTribeCenters, kingdomSpacing);
            double remoteFactor = clamp01((nearestDistance - (kingdomSpacing * 0.40D)) / Math.max(1.0D, kingdomSpacing));
            double scorePenalty = clamp01(
                (config.ironThreshold() - candidate.score().total())
                    / Math.max(0.01D, config.ironThreshold() - config.woodThreshold())
            );
            double desirability = (0.75D * remoteFactor) + (0.25D * scorePenalty);
            eligible.add(new TribeBackfillCandidate(candidate, desirability));
        }

        eligible.sort(
            Comparator.comparingDouble(TribeBackfillCandidate::desirability).reversed()
                .thenComparingLong(entry -> entry.candidate().deterministicKey())
        );

        int addedTribes = 0;
        for (TribeBackfillCandidate entry : eligible) {
            if (addedTribes >= targetExtraTribes) {
                break;
            }

            PlacementCandidate candidate = entry.candidate();
            double nearestNonTribeDistance = nearestDistance(candidate.center(), nonTribeCenters, kingdomSpacing);
            boolean remoteEnough = nearestNonTribeDistance >= remoteThreshold;
            if (addedTribes < remotePriorityQuota && !remoteEnough) {
                continue;
            }
            if (!remoteEnough && candidate.score().total() >= config.stoneThreshold()) {
                continue;
            }
            if (isTooClose(candidate.center(), occupiedCenters, tribeSpacing)) {
                continue;
            }

            long tribeId = AnchorIdGenerator.deterministicId(worldSeedHash, regionKey, localOrdinal++);
            candidate.setAssignedTier(TechTier.WOOD);
            candidate.accept();
            clusters.add(new ClusterPlan(createTribeAnchor(candidate, tribeId, config), List.of()));
            occupiedCenters.add(candidate.center());
            consumedCandidateKeys.add(candidate.deterministicKey());
            addedTribes++;
        }

        return new BackfillResult(localOrdinal, addedTribes, eligible.size());
    }

    private static boolean shouldPromoteToTribe(
        PlacementCandidate candidate,
        List<BlockPos> selectedCenters,
        CivPlacementConfig config,
        int kingdomSpacing
    ) {
        double nearestDistance = nearestDistance(candidate.center(), selectedCenters, kingdomSpacing);
        double remoteFactor = clamp01((nearestDistance - (kingdomSpacing * 0.60D)) / Math.max(1.0D, kingdomSpacing));
        double score = candidate.score().total();
        double scorePenalty = clamp01((config.ironThreshold() - score) / Math.max(0.01D, config.ironThreshold() - config.woodThreshold()));
        double roll = SettlementTierPolicy.deterministicRoll01(candidate.deterministicKey() ^ 0xB5297A4D2C13E981L);

        if (score >= config.diamondThreshold()) {
            return false;
        }

        if (score >= config.ironThreshold()) {
            double tribeChance = 0.08D + (0.18D * remoteFactor);
            return roll < tribeChance;
        }

        if (score >= config.stoneThreshold()) {
            double tribeChance = 0.28D + (0.30D * remoteFactor) + (0.22D * scorePenalty);
            return roll < Math.min(0.88D, tribeChance);
        }

        double tribeChance = 0.55D + (0.28D * remoteFactor) + (0.20D * scorePenalty);
        return roll < Math.min(0.95D, tribeChance);
    }

    private static SettlementAnchor createTribeAnchor(PlacementCandidate candidate, long anchorId, CivPlacementConfig config) {
        return new SettlementAnchor(
            anchorId,
            candidate.center(),
            TechTier.WOOD,
            SettlementType.TRIBE,
            config.radiusForTier(TechTier.WOOD),
            anchorId,
            inferBiomeTags(candidate.biomeId().toString())
        );
    }

    private static double nearestDistance(BlockPos center, List<BlockPos> points, int defaultDistance) {
        if (points.isEmpty()) {
            return defaultDistance;
        }

        double best = Double.MAX_VALUE;
        for (BlockPos point : points) {
            double distance = Math.sqrt(horizontalDistanceSq(center, point));
            if (distance < best) {
                best = distance;
            }
        }
        return best == Double.MAX_VALUE ? defaultDistance : best;
    }

    private static double proximityToCapital(int distance, int minDistance, int maxDistance) {
        if (maxDistance <= minDistance) {
            return 1.0D;
        }
        return clamp01(1.0D - ((distance - minDistance) / (double) (maxDistance - minDistance)));
    }

    private static boolean isTooClose(BlockPos center, List<BlockPos> selectedCenters, int minSpacing) {
        long minSpacingSq = (long) minSpacing * minSpacing;
        for (BlockPos selected : selectedCenters) {
            long distanceSq = horizontalDistanceSq(center, selected);
            if (distanceSq < minSpacingSq) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTooCloseXZ(int x, int z, List<BlockPos> selectedCenters, int minSpacing) {
        long minSpacingSq = (long) minSpacing * minSpacing;
        for (BlockPos selected : selectedCenters) {
            long dx = x - selected.getX();
            long dz = z - selected.getZ();
            long distanceSq = (dx * dx) + (dz * dz);
            if (distanceSq < minSpacingSq) {
                return true;
            }
        }
        return false;
    }

    private static long horizontalDistanceSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return (dx * dx) + (dz * dz);
    }

    private static Set<Identifier> inferBiomeTags(String biomeId) {
        Set<Identifier> tags = new LinkedHashSet<>();
        tags.add(Identifier.parse("minecraft:is_overworld"));

        if (containsAny(biomeId, "forest", "taiga", "jungle", "woods", "cherry")) {
            tags.add(Identifier.parse("minecraft:is_forest"));
        }
        if (containsAny(biomeId, "ocean", "river", "beach", "swamp", "mangrove")) {
            tags.add(Identifier.parse("minecraft:is_water"));
        }
        if (containsAny(biomeId, "mountain", "peaks", "hills", "ridge")) {
            tags.add(Identifier.parse("minecraft:is_mountain"));
        }

        return tags;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static SuitabilitySampler.SuitabilitySample sampleSuitability(
        Map<Long, SuitabilitySampler.SuitabilitySample> cache,
        ServerLevel level,
        int x,
        int z,
        CivPlacementConfig config
    ) {
        long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        return cache.computeIfAbsent(key, unused -> SuitabilitySampler.sampleAt(level, x, z, config));
    }

    private record TribeBackfillCandidate(PlacementCandidate candidate, double desirability) {
    }

    private record SatellitePlacementOption(
        BlockPos center,
        Identifier biomeId,
        int distanceFromCapital,
        SuitabilityScore suitability
    ) {
    }

    private record SatelliteAttempt(int x, int z, int distanceFromCapital) {
    }

    private record BackfillResult(int nextLocalOrdinal, int addedTribes, int eligibleCandidates) {
    }

    public record ClusterGenerationResult(List<ClusterPlan> clusters, ClusterGenerationMetrics metrics) {
    }

    public record ClusterGenerationMetrics(
        long totalNanos,
        long primaryPassNanos,
        long backfillNanos,
        int orderedCandidateCount,
        int initialAcceptedCandidateCount,
        int capitalsPlaced,
        int promotedTribesPlaced,
        int backfillTribesPlaced,
        int backfillEligibleCandidateCount,
        int satellitesRequested,
        int satellitesPlaced,
        int satellitePlacementAttempts,
        int satelliteSuitabilitySamples,
        int uniqueSatelliteSamplePositions
    ) {
        public int totalTribesPlaced() {
            return promotedTribesPlaced + backfillTribesPlaced;
        }
    }
}
