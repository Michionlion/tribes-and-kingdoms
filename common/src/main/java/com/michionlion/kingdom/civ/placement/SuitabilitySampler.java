package com.michionlion.kingdom.civ.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;

public final class SuitabilitySampler {
    private SuitabilitySampler() {
    }

    public static SuitabilityScore sample(ServerLevel level, int x, int z, CivPlacementConfig config) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

        double biomeScore = biomeScore(level, x, surfaceY, z);
        double heightScore = heightScore(surfaceY);
        double slopeScore = slopeScore(level, x, z, config);
        double waterScore = waterScore(level, x, z, config);

        double weightTotal = config.biomeWeight() + config.heightWeight() + config.slopeWeight() + config.waterWeight();
        if (weightTotal <= 0.0D) {
            weightTotal = 1.0D;
        }

        double weighted = (
            (biomeScore * config.biomeWeight())
                + (heightScore * config.heightWeight())
                + (slopeScore * config.slopeWeight())
                + (waterScore * config.waterWeight())
        ) / weightTotal;

        return new SuitabilityScore(
            clamp01(biomeScore),
            clamp01(heightScore),
            clamp01(slopeScore),
            clamp01(waterScore),
            clamp01(weighted)
        );
    }

    private static double biomeScore(ServerLevel level, int x, int y, int z) {
        String biomePath = level.getBiome(new BlockPos(x, y, z))
            .unwrapKey()
            .map(key -> key.identifier().getPath())
            .orElse("plains");

        if (containsAny(biomePath, "forest", "woods", "plains", "savanna", "taiga", "cherry", "jungle")) {
            return 1.0D;
        }
        if (containsAny(biomePath, "river", "beach", "meadow", "grove")) {
            return 0.85D;
        }
        if (containsAny(biomePath, "swamp", "marsh", "mangrove", "badlands")) {
            return 0.55D;
        }
        if (containsAny(biomePath, "mountain", "peaks", "frozen", "snow", "ice")) {
            return 0.45D;
        }
        if (containsAny(biomePath, "desert", "nether", "end", "void")) {
            return 0.20D;
        }

        return 0.65D;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double heightScore(int y) {
        int preferredY = 80;
        int tolerance = 64;
        double distance = Math.abs(y - preferredY);
        return 1.0D - clamp01(distance / tolerance);
    }

    private static double slopeScore(ServerLevel level, int x, int z, CivPlacementConfig config) {
        int offset = config.surfaceSampleOffsetBlocks();

        int center = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int east = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + offset, z);
        int west = level.getHeight(Heightmap.Types.WORLD_SURFACE, x - offset, z);
        int south = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z + offset);
        int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - offset);

        int maxDelta = Math.max(
            Math.max(Math.abs(center - east), Math.abs(center - west)),
            Math.max(Math.abs(center - south), Math.abs(center - north))
        );

        return 1.0D - clamp01(maxDelta / config.maxSlopeDelta());
    }

    private static double waterScore(ServerLevel level, int x, int z, CivPlacementConfig config) {
        int radius = config.waterSearchRadiusBlocks();
        int step = config.waterSearchStepBlocks();
        double bestDistanceSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int sx = x + dx;
                int sz = z + dz;
                int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz);
                BlockPos samplePos = new BlockPos(sx, sy - 1, sz);
                if (!level.getFluidState(samplePos).is(FluidTags.WATER)) {
                    continue;
                }

                double distanceSq = (dx * dx) + (dz * dz);
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                }
            }
        }

        if (bestDistanceSq == Double.MAX_VALUE) {
            return 0.0D;
        }

        double distance = Math.sqrt(bestDistanceSq);
        return 1.0D - clamp01(distance / radius);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
