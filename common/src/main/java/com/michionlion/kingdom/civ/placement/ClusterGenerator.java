package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.util.AnchorIdGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
        List<PlacementCandidate> candidates
    ) {
        List<PlacementCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator
            .comparingDouble((PlacementCandidate candidate) -> candidate.score().total()).reversed()
            .thenComparingLong(PlacementCandidate::deterministicKey));

        List<BlockPos> selectedCenters = new ArrayList<>();
        List<ClusterPlan> clusters = new ArrayList<>();
        int localOrdinal = 0;

        for (PlacementCandidate candidate : ordered) {
            if (!candidate.accepted()) {
                continue;
            }

            if (isTooClose(candidate.center(), selectedCenters, config.minAnchorSpacingBlocks())) {
                candidate.reject("spacing_conflict");
                continue;
            }

            selectedCenters.add(candidate.center());
            TechTier tier = candidate.assignedTier();
            long civId = mix64(candidate.deterministicKey() ^ worldSeedHash);

            long capitalId = AnchorIdGenerator.deterministicId(worldSeedHash, regionKey, localOrdinal++);
            SettlementAnchor capital = new SettlementAnchor(
                capitalId,
                candidate.center(),
                tier,
                tier == TechTier.WOOD ? SettlementType.TRIBE : SettlementType.KINGDOM_CAPITAL,
                config.radiusForTier(tier),
                civId,
                inferBiomeTags(candidate.biomeId().toString())
            );

            if (tier == TechTier.WOOD) {
                clusters.add(new ClusterPlan(capital, List.of()));
                continue;
            }

            int minSatellites = config.satelliteMinForTier(tier);
            int maxSatellites = config.satelliteMaxForTier(tier);
            int satelliteCount = minSatellites;

            if (maxSatellites > minSatellites) {
                Random random = new Random(mix64(candidate.deterministicKey() ^ 0x6A09E667F3BCC909L));
                satelliteCount = minSatellites + random.nextInt(maxSatellites - minSatellites + 1);
            }

            List<SettlementAnchor> satellites = new ArrayList<>(satelliteCount);
            Random satelliteRandom = new Random(mix64(candidate.deterministicKey() ^ 0xBB67AE8584CAA73BL));

            for (int i = 0; i < satelliteCount; i++) {
                double angle = satelliteRandom.nextDouble() * (Math.PI * 2.0D);
                int distance = config.satelliteMinDistanceBlocks();
                if (config.satelliteMaxDistanceBlocks() > config.satelliteMinDistanceBlocks()) {
                    distance += satelliteRandom.nextInt(config.satelliteMaxDistanceBlocks() - config.satelliteMinDistanceBlocks() + 1);
                }

                int sx = candidate.center().getX() + (int) Math.round(Math.cos(angle) * distance);
                int sz = candidate.center().getZ() + (int) Math.round(Math.sin(angle) * distance);
                int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz);

                Identifier biomeId = level.getBiome(new BlockPos(sx, sy, sz))
                    .unwrapKey()
                    .map(key -> key.identifier())
                    .orElse(Identifier.parse("minecraft:plains"));

                SettlementType type = i < 2 ? SettlementType.KINGDOM_TOWN : SettlementType.OUTPOST;
                long satelliteId = AnchorIdGenerator.deterministicId(worldSeedHash, regionKey, localOrdinal++);

                satellites.add(new SettlementAnchor(
                    satelliteId,
                    new BlockPos(sx, sy, sz),
                    tier,
                    type,
                    Math.max(16, config.radiusForTier(tier) / 2),
                    civId,
                    inferBiomeTags(biomeId.toString())
                ));
            }

            clusters.add(new ClusterPlan(capital, satellites));
        }

        return List.copyOf(clusters);
    }

    private static boolean isTooClose(BlockPos center, List<BlockPos> selectedCenters, int minSpacing) {
        long minSpacingSq = (long) minSpacing * minSpacing;
        for (BlockPos selected : selectedCenters) {
            long dx = center.getX() - selected.getX();
            long dz = center.getZ() - selected.getZ();
            long distanceSq = (dx * dx) + (dz * dz);
            if (distanceSq < minSpacingSq) {
                return true;
            }
        }

        return false;
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
}
