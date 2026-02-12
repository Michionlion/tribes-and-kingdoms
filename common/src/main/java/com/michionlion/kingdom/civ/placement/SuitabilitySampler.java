package com.michionlion.kingdom.civ.placement;

import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

public final class SuitabilitySampler {
    private static final int CHUNK_PROBE_STEP_BLOCKS = 16;

    private SuitabilitySampler() {
    }

    public static SuitabilityScore sample(ServerLevel level, int x, int z, CivPlacementConfig config) {
        return sampleAt(level, x, z, config).score();
    }

    public static SuitabilitySample sampleAt(ServerLevel level, int x, int z, CivPlacementConfig config) {
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int surfaceY = sampleSurfaceY(chunkGenerator, randomState, level, x, z);
        Identifier biomeId = sampleBiomeId(chunkGenerator, randomState, x, surfaceY, z);

        double biomeScore = biomeScore(biomeId.getPath());
        double heightScore = heightScore(surfaceY);
        double slopeScore = slopeScore(chunkGenerator, randomState, level, x, z, surfaceY, config);
        double waterScore = waterScore(chunkGenerator, randomState, x, z, biomeId, config);

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

        SuitabilityScore score = new SuitabilityScore(
            clamp01(biomeScore),
            clamp01(heightScore),
            clamp01(slopeScore),
            clamp01(waterScore),
            clamp01(weighted)
        );
        return new SuitabilitySample(surfaceY, biomeId, score);
    }

    public static boolean isLandPlacementBiome(String biomePathOrId) {
        return !isOceanicOrCoastalBiome(biomePathOrId);
    }

    public static boolean isOceanicOrCoastalBiome(String biomePathOrId) {
        String biomePath = pathFromIdOrPath(biomePathOrId);
        return containsAny(
            biomePath,
            "ocean",
            "deep_ocean",
            "beach",
            "shore",
            "river",
            "swamp",
            "mangrove"
        );
    }

    private static double biomeScore(String biomePath) {
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

    private static double slopeScore(
        ChunkGenerator chunkGenerator,
        RandomState randomState,
        ServerLevel level,
        int x,
        int z,
        int centerY,
        CivPlacementConfig config
    ) {
        int offset = config.surfaceSampleOffsetBlocks();

        int east = sampleSurfaceY(chunkGenerator, randomState, level, x + offset, z);
        int west = sampleSurfaceY(chunkGenerator, randomState, level, x - offset, z);
        int south = sampleSurfaceY(chunkGenerator, randomState, level, x, z + offset);
        int north = sampleSurfaceY(chunkGenerator, randomState, level, x, z - offset);

        int maxDelta = Math.max(
            Math.max(Math.abs(centerY - east), Math.abs(centerY - west)),
            Math.max(Math.abs(centerY - south), Math.abs(centerY - north))
        );

        return 1.0D - clamp01(maxDelta / config.maxSlopeDelta());
    }

    private static double waterScore(
        ChunkGenerator chunkGenerator,
        RandomState randomState,
        int x,
        int z,
        Identifier centerBiome,
        CivPlacementConfig config
    ) {
        int radius = config.waterSearchRadiusBlocks();
        if (radius <= 0) {
            return 0.0D;
        }

        int radialStep = Math.max(CHUNK_PROBE_STEP_BLOCKS, config.waterSearchStepBlocks() * CHUNK_PROBE_STEP_BLOCKS);
        int probeY = chunkGenerator.getSeaLevel();
        double bestDistanceSq = Double.MAX_VALUE;

        if (isOceanicOrCoastalBiome(centerBiome.getPath())) {
            return 1.0D;
        }

        for (int distance = radialStep; distance <= radius; distance += radialStep) {
            int angularSamples = Math.max(8, (int) Math.ceil((2.0D * Math.PI * distance) / CHUNK_PROBE_STEP_BLOCKS));
            boolean foundThisRing = false;
            for (int i = 0; i < angularSamples; i++) {
                double angle = (Math.PI * 2.0D * i) / angularSamples;
                int dx = (int) Math.round(Math.cos(angle) * distance);
                int dz = (int) Math.round(Math.sin(angle) * distance);

                int sx = x + dx;
                int sz = z + dz;
                Identifier biomeId = sampleBiomeId(chunkGenerator, randomState, sx, probeY, sz);
                if (isOceanicOrCoastalBiome(biomeId.getPath())) {
                    bestDistanceSq = Math.min(bestDistanceSq, (dx * dx) + (dz * dz));
                    foundThisRing = true;
                }
            }
            // Distances increase by ring, so once a ring has water we already found the nearest ring.
            if (foundThisRing) {
                break;
            }
        }

        if (bestDistanceSq == Double.MAX_VALUE) {
            return 0.0D;
        }

        double distance = Math.sqrt(bestDistanceSq);
        return 1.0D - clamp01(distance / radius);
    }

    private static String pathFromIdOrPath(String biomePathOrId) {
        if (biomePathOrId == null || biomePathOrId.isBlank()) {
            return "";
        }

        int separator = biomePathOrId.indexOf(':');
        if (separator >= 0 && separator < biomePathOrId.length() - 1) {
            return biomePathOrId.substring(separator + 1);
        }
        return biomePathOrId;
    }

    private static int sampleSurfaceY(
        ChunkGenerator chunkGenerator,
        RandomState randomState,
        ServerLevel level,
        int x,
        int z
    ) {
        return chunkGenerator.getBaseHeight(
            x,
            z,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            level,
            randomState
        );
    }

    private static Identifier sampleBiomeId(
        ChunkGenerator chunkGenerator,
        RandomState randomState,
        int x,
        int y,
        int z
    ) {
        return chunkGenerator.getBiomeSource()
            .getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(y),
                QuartPos.fromBlock(z),
                randomState.sampler()
            )
            .unwrapKey()
            .map(key -> key.identifier())
            .orElse(Identifier.parse("minecraft:plains"));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record SuitabilitySample(int surfaceY, Identifier biomeId, SuitabilityScore score) {
    }
}
