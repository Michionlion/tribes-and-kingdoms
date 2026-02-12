package com.michionlion.kingdom.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.michionlion.kingdom.civ.config.KingdomPlacementConfigManager;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import com.michionlion.kingdom.civ.placement.PlacementCandidate;
import com.michionlion.kingdom.civ.state.CivWorldState;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import org.slf4j.Logger;

public final class GeoJsonExporter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final int CONTINENT_BLOCK_SPAN = 8192;
    private static final int CHUNK_BLOCK_SPAN = 16;
    private static final String TERRAIN_CACHE_DIR_PROPERTY = "kingdom.gametest.terrainCacheDir";
    private static final String TERRAIN_PROGRESS_ENABLED_PROPERTY = "kingdom.gametest.terrainProgress";
    private static final String TERRAIN_PROGRESS_STEP_PERCENT_PROPERTY = "kingdom.gametest.terrainProgressStepPercent";
    private static final int TERRAIN_CACHE_FORMAT_VERSION = 2;

    private GeoJsonExporter() {
    }

    public static Path exportRegionRadius(
            ServerLevel level,
            CivWorldState state,
            RegionKey center,
            int radiusRegions,
            boolean includeRejected,
            String fileName) throws IOException {
        long exportStartNanos = System.nanoTime();
        CivPlacementConfig placementConfig = KingdomPlacementConfigManager.snapshot();

        long snapshotScanStartNanos = System.nanoTime();
        Map<Long, PlacementCandidate> acceptedCandidatesByPos = new LinkedHashMap<>();
        List<PlacementDebugSnapshotStore.RegionSnapshot> snapshots = PlacementDebugSnapshotStore
                .getInRadius(level.getServer(), center, radiusRegions);
        for (PlacementDebugSnapshotStore.RegionSnapshot snapshot : snapshots) {
            for (PlacementCandidate candidate : snapshot.candidates()) {
                if (candidate.accepted()) {
                    acceptedCandidatesByPos.put(candidate.center().asLong(), candidate);
                }
            }
        }
        long snapshotScanNanos = System.nanoTime() - snapshotScanStartNanos;

        JsonArray features = new JsonArray();
        int acceptedFeatureCount = 0;
        long acceptedFeatureStartNanos = System.nanoTime();
        for (Map.Entry<Long, LongList> entry : state.anchorsByRegion().entrySet()) {
            RegionKey regionKey = RegionKey.fromLong(entry.getKey());
            if (Math.abs(regionKey.x() - center.x()) > radiusRegions
                    || Math.abs(regionKey.z() - center.z()) > radiusRegions) {
                continue;
            }

            for (long anchorId : entry.getValue()) {
                SettlementAnchor anchor = state.anchor(anchorId);
                if (anchor == null) {
                    continue;
                }

                JsonObject feature = new JsonObject();
                feature.addProperty("type", "Feature");
                feature.add("geometry", point(anchor.center().getX(), anchor.center().getZ()));

                JsonObject properties = new JsonObject();
                properties.addProperty("id", anchor.id());
                properties.addProperty("civ_id", anchor.civId());
                properties.addProperty("tier", anchor.tier().name());
                properties.addProperty("type", anchor.type().name());
                properties.addProperty("region_x", regionKey.x());
                properties.addProperty("region_z", regionKey.z());
                properties.addProperty("accepted", true);
                properties.addProperty("is_capital", anchor.type().name().contains("CAPITAL"));
                addBiomeAndContinentProperties(properties, level, anchor.center(), null);

                PlacementCandidate acceptedCandidate = acceptedCandidatesByPos.get(anchor.center().asLong());
                if (acceptedCandidate != null) {
                    properties.addProperty("biome", acceptedCandidate.biomeId().toString());
                    addContinentProperties(properties, anchor.center(), acceptedCandidate.biomeId().toString());
                    addScoreProperties(properties, acceptedCandidate);
                }

                feature.add("properties", properties);
                features.add(feature);
                acceptedFeatureCount++;
            }
        }
        long acceptedFeatureNanos = System.nanoTime() - acceptedFeatureStartNanos;

        int rejectedFeatureCount = 0;
        long rejectedFeatureNanos = 0L;
        if (includeRejected) {
            long rejectedFeatureStartNanos = System.nanoTime();
            for (PlacementDebugSnapshotStore.RegionSnapshot snapshot : snapshots) {
                for (PlacementCandidate candidate : snapshot.candidates()) {
                    if (candidate.accepted()) {
                        continue;
                    }

                    JsonObject feature = new JsonObject();
                    feature.addProperty("type", "Feature");
                    feature.add("geometry", point(candidate.center().getX(), candidate.center().getZ()));

                    JsonObject properties = new JsonObject();
                    properties.addProperty("accepted", false);
                    properties.addProperty("region_x", candidate.regionKey().x());
                    properties.addProperty("region_z", candidate.regionKey().z());
                    properties.addProperty("tier", candidate.assignedTier().name());
                    properties.addProperty("biome", candidate.biomeId().toString());
                    properties.addProperty("rejection_reason", candidate.rejectionReason());
                    addContinentProperties(properties, candidate.center(), candidate.biomeId().toString());
                    addScoreProperties(properties, candidate);

                    feature.add("properties", properties);
                    features.add(feature);
                    rejectedFeatureCount++;
                }
            }
            rejectedFeatureNanos = System.nanoTime() - rejectedFeatureStartNanos;
        }

        // Keep radius=0 exports snapshot-stable; chunk terrain samples are for
        // wider-area review workflows.
        TerrainSamplingMetrics terrainMetrics = TerrainSamplingMetrics.empty();
        if (radiusRegions > 0) {
            terrainMetrics = appendTerrainSamples(features, level, center, radiusRegions, placementConfig);
        }

        JsonObject collection = new JsonObject();
        collection.addProperty("type", "FeatureCollection");
        collection.add("features", features);

        Path outputDir = Path.of("debug", "kingdom");
        Files.createDirectories(outputDir);

        String safeName = Optional.ofNullable(fileName)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> defaultFileName(level.dimension().identifier().getPath()));
        if (!safeName.endsWith(".geojson")) {
            safeName = safeName + ".geojson";
        }

        Path outputFile = outputDir.resolve(safeName);
        long writeStartNanos = System.nanoTime();
        Files.writeString(outputFile, GSON.toJson(collection), StandardCharsets.UTF_8);
        long writeNanos = System.nanoTime() - writeStartNanos;

        LOGGER.info(
                "[kingdom] export_geojson center_region={},{} radius={} include_rejected={} "
                        + "features(total={} accepted={} rejected={} terrain={}) snapshots={} "
                        + "terrain_cache_hit={} terrain_chunks={} terrain_grid_samples={} "
                        + "terrain_mode={} terrain_parallelism={} "
                        + "stage_ms(snapshot_scan={} accepted_features={} rejected_features={} terrain={} write={}) total_ms={} output={}",
                center.x(),
                center.z(),
                radiusRegions,
                includeRejected,
                features.size(),
                acceptedFeatureCount,
                rejectedFeatureCount,
                terrainMetrics.terrainFeatureCount(),
                snapshots.size(),
                terrainMetrics.cacheHit(),
                terrainMetrics.terrainChunkCount(),
                terrainMetrics.terrainGridSampleCount(),
                terrainMetrics.mode(),
                terrainMetrics.parallelism(),
                nanosToMillis(snapshotScanNanos),
                nanosToMillis(acceptedFeatureNanos),
                nanosToMillis(rejectedFeatureNanos),
                nanosToMillis(terrainMetrics.durationNanos()),
                nanosToMillis(writeNanos),
                nanosToMillis(System.nanoTime() - exportStartNanos),
                outputFile.toAbsolutePath());
        return outputFile;
    }

    private static TerrainSamplingMetrics appendTerrainSamples(
            JsonArray features,
            ServerLevel level,
            RegionKey center,
            int radiusRegions,
            CivPlacementConfig placementConfig) {
        long startNanos = System.nanoTime();
        int regionSizeBlocks = placementConfig.regionSizeBlocks();
        int minRegionX = center.x() - radiusRegions;
        int maxRegionX = center.x() + radiusRegions;
        int minRegionZ = center.z() - radiusRegions;
        int maxRegionZ = center.z() + radiusRegions;

        int minBlockX = Math.multiplyExact(minRegionX, regionSizeBlocks);
        int minBlockZ = Math.multiplyExact(minRegionZ, regionSizeBlocks);
        int maxBlockXExclusive = Math.multiplyExact(maxRegionX + 1, regionSizeBlocks);
        int maxBlockZExclusive = Math.multiplyExact(maxRegionZ + 1, regionSizeBlocks);

        int minChunkX = Math.floorDiv(minBlockX, CHUNK_BLOCK_SPAN);
        int minChunkZ = Math.floorDiv(minBlockZ, CHUNK_BLOCK_SPAN);
        int maxChunkX = Math.floorDiv(maxBlockXExclusive - 1, CHUNK_BLOCK_SPAN);
        int maxChunkZ = Math.floorDiv(maxBlockZExclusive - 1, CHUNK_BLOCK_SPAN);

        // Expand terrain sampling to cover all exported feature chunks so the
        // topography raster
        // and settlement points share the same footprint.
        for (int i = 0; i < features.size(); i++) {
            JsonObject feature = features.get(i).getAsJsonObject();
            JsonObject geometry = feature.getAsJsonObject("geometry");
            if (geometry == null || !geometry.has("type") || !"Point".equals(geometry.get("type").getAsString())) {
                continue;
            }
            JsonArray coordinates = geometry.getAsJsonArray("coordinates");
            if (coordinates == null || coordinates.size() < 2) {
                continue;
            }

            int blockX = (int) Math.floor(coordinates.get(0).getAsDouble());
            int blockZ = (int) Math.floor(coordinates.get(1).getAsDouble());
            int featureChunkX = Math.floorDiv(blockX, CHUNK_BLOCK_SPAN);
            int featureChunkZ = Math.floorDiv(blockZ, CHUNK_BLOCK_SPAN);
            minChunkX = Math.min(minChunkX, featureChunkX);
            maxChunkX = Math.max(maxChunkX, featureChunkX);
            minChunkZ = Math.min(minChunkZ, featureChunkZ);
            maxChunkZ = Math.max(maxChunkZ, featureChunkZ);
        }

        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int minSampleChunkX = minChunkX - 1;
        int maxSampleChunkX = maxChunkX + 1;
        int minSampleChunkZ = minChunkZ - 1;
        int maxSampleChunkZ = maxChunkZ + 1;

        TerrainCacheKey cacheKey = new TerrainCacheKey(
                TERRAIN_CACHE_FORMAT_VERSION,
                level.getSeed(),
                level.dimension().identifier().toString(),
                center.x(),
                center.z(),
                radiusRegions,
                regionSizeBlocks,
                CHUNK_BLOCK_SPAN,
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ);
        Optional<Path> cacheFile = terrainCacheFile(cacheKey);
        Optional<List<TerrainSample>> cachedSamples = cacheFile.flatMap(path -> readTerrainCache(path, cacheKey));
        if (cachedSamples.isPresent()) {
            int terrainFeatureCount = 0;
            for (TerrainSample sample : cachedSamples.get()) {
                appendTerrainSampleFeature(features, sample, regionSizeBlocks);
                terrainFeatureCount++;
            }
            LOGGER.info(
                    "[kingdom] terrain_export cache_hit=true chunks={} samples={} cache_file={}",
                    ((maxChunkX - minChunkX) + 1) * ((maxChunkZ - minChunkZ) + 1),
                    terrainFeatureCount,
                    cacheFile.map(path -> path.toAbsolutePath().toString()).orElse("<disabled>"));
            return new TerrainSamplingMetrics(
                    System.nanoTime() - startNanos,
                    true,
                    terrainFeatureCount,
                    ((maxChunkX - minChunkX) + 1) * ((maxChunkZ - minChunkZ) + 1),
                    0,
                    "cache_hit",
                    0);
        }

        int sampledWidth = (maxSampleChunkX - minSampleChunkX) + 1;
        int sampledHeight = (maxSampleChunkZ - minSampleChunkZ) + 1;
        int[] sampledSurfaceY = new int[sampledWidth * sampledHeight];
        int terrainParallelism = resolveTerrainParallelism(placementConfig);
        int progressStepPercent = resolveTerrainProgressStepPercent();
        String terrainMode = "sequential";
        TerrainProgressTracker surfaceProgress = TerrainProgressTracker.create(
                "surface_grid",
                sampledSurfaceY.length,
                progressStepPercent,
                terrainParallelism);
        LOGGER.info(
                "[kingdom] terrain_export cache_hit=false chunks={} grid_samples={} mode={} parallelism={} progress_step={}%",
                ((maxChunkX - minChunkX) + 1) * ((maxChunkZ - minChunkZ) + 1),
                sampledSurfaceY.length,
                terrainParallelism > 1 ? "parallel" : "sequential",
                terrainParallelism,
                progressStepPercent);
        try {
            runIndexedWork(terrainParallelism, sampledSurfaceY.length, index -> {
                int sampleRow = index / sampledWidth;
                int sampleCol = index - (sampleRow * sampledWidth);
                int sampleChunkX = minSampleChunkX + sampleCol;
                int sampleChunkZ = minSampleChunkZ + sampleRow;
                sampledSurfaceY[index] = sampleSurfaceY(
                        chunkGenerator,
                        randomState,
                        level,
                        centerBlock(sampleChunkX),
                        centerBlock(sampleChunkZ));
                surfaceProgress.increment();
            });
            if (terrainParallelism > 1 && sampledSurfaceY.length > 1) {
                terrainMode = "parallel";
            }
        } catch (RuntimeException error) {
            terrainMode = "fallback_sequential";
            terrainParallelism = 1;
            LOGGER.warn(
                    "[kingdom] terrain sampling parallel execution failed; falling back to sequential mode: {}",
                    error.toString());
            for (int index = 0; index < sampledSurfaceY.length; index++) {
                int sampleRow = index / sampledWidth;
                int sampleCol = index - (sampleRow * sampledWidth);
                int sampleChunkX = minSampleChunkX + sampleCol;
                int sampleChunkZ = minSampleChunkZ + sampleRow;
                sampledSurfaceY[index] = sampleSurfaceY(
                        chunkGenerator,
                        randomState,
                        level,
                        centerBlock(sampleChunkX),
                        centerBlock(sampleChunkZ));
                surfaceProgress.increment();
            }
        }

        int terrainWidth = (maxChunkX - minChunkX) + 1;
        int terrainHeight = (maxChunkZ - minChunkZ) + 1;
        int terrainChunkCount = terrainWidth * terrainHeight;
        final int terrainMinChunkX = minChunkX;
        final int terrainMinChunkZ = minChunkZ;
        TerrainSample[] sampledTerrain = new TerrainSample[terrainChunkCount];
        TerrainProgressTracker chunkProgress = TerrainProgressTracker.create(
                "terrain_chunks",
                terrainChunkCount,
                progressStepPercent,
                terrainParallelism);
        try {
            runIndexedWork(terrainParallelism, terrainChunkCount, index -> {
                int row = index / terrainWidth;
                int col = index - (row * terrainWidth);
                int chunkX = terrainMinChunkX + col;
                int chunkZ = terrainMinChunkZ + row;
                int sampleX = centerBlock(chunkX);
                int sampleZ = centerBlock(chunkZ);
                int sampleRow = chunkZ - minSampleChunkZ;
                int sampleCol = chunkX - minSampleChunkX;
                int surfaceY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow, sampleCol)];
                int eastY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow, sampleCol + 1)];
                int westY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow, sampleCol - 1)];
                int southY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow + 1, sampleCol)];
                int northY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow - 1, sampleCol)];
                int slopeDelta = maxDelta(surfaceY, eastY, westY, southY, northY);

                Identifier biomeId = chunkGenerator.getBiomeSource()
                        .getNoiseBiome(
                                QuartPos.fromBlock(sampleX),
                                QuartPos.fromBlock(surfaceY),
                                QuartPos.fromBlock(sampleZ),
                                randomState.sampler())
                        .unwrapKey()
                        .map(key -> key.identifier())
                        .orElse(Identifier.parse("minecraft:plains"));

                sampledTerrain[index] = new TerrainSample(
                        chunkX,
                        chunkZ,
                        sampleX,
                        sampleZ,
                        surfaceY,
                        slopeDelta,
                        biomeId.toString());
                chunkProgress.increment();
            });
        } catch (RuntimeException error) {
            terrainMode = "fallback_sequential";
            terrainParallelism = 1;
            LOGGER.warn(
                    "[kingdom] terrain feature parallel execution failed; falling back to sequential mode: {}",
                    error.toString());
            for (int index = 0; index < terrainChunkCount; index++) {
                int row = index / terrainWidth;
                int col = index - (row * terrainWidth);
                int chunkX = minChunkX + col;
                int chunkZ = minChunkZ + row;
                int sampleX = centerBlock(chunkX);
                int sampleZ = centerBlock(chunkZ);
                int sampleRow = chunkZ - minSampleChunkZ;
                int sampleCol = chunkX - minSampleChunkX;
                int surfaceY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow, sampleCol)];
                int eastY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow, sampleCol + 1)];
                int westY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow, sampleCol - 1)];
                int southY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow + 1, sampleCol)];
                int northY = sampledSurfaceY[sampleIndex(sampledWidth, sampleRow - 1, sampleCol)];
                int slopeDelta = maxDelta(surfaceY, eastY, westY, southY, northY);

                Identifier biomeId = chunkGenerator.getBiomeSource()
                        .getNoiseBiome(
                                QuartPos.fromBlock(sampleX),
                                QuartPos.fromBlock(surfaceY),
                                QuartPos.fromBlock(sampleZ),
                                randomState.sampler())
                        .unwrapKey()
                        .map(key -> key.identifier())
                        .orElse(Identifier.parse("minecraft:plains"));

                sampledTerrain[index] = new TerrainSample(
                        chunkX,
                        chunkZ,
                        sampleX,
                        sampleZ,
                        surfaceY,
                        slopeDelta,
                        biomeId.toString());
                chunkProgress.increment();
            }
        }

        List<TerrainSample> computedSamples = new ArrayList<>(terrainChunkCount);
        for (TerrainSample sample : sampledTerrain) {
            if (sample == null) {
                continue;
            }
            computedSamples.add(sample);
            appendTerrainSampleFeature(features, sample, regionSizeBlocks);
        }

        cacheFile.ifPresent(path -> writeTerrainCache(path, cacheKey, computedSamples));
        return new TerrainSamplingMetrics(
                System.nanoTime() - startNanos,
                false,
                computedSamples.size(),
                ((maxChunkX - minChunkX) + 1) * ((maxChunkZ - minChunkZ) + 1),
                sampledWidth * sampledHeight,
                terrainMode,
                terrainParallelism);
    }

    private static void appendTerrainSampleFeature(JsonArray features, TerrainSample sample, int regionSizeBlocks) {
        JsonObject feature = new JsonObject();
        feature.addProperty("type", "Feature");
        feature.add("geometry", point(sample.blockX(), sample.blockZ()));

        JsonObject properties = new JsonObject();
        properties.addProperty("feature_kind", "terrain_sample");
        properties.addProperty("chunk_x", sample.chunkX());
        properties.addProperty("chunk_z", sample.chunkZ());
        properties.addProperty("region_x", Math.floorDiv(sample.blockX(), regionSizeBlocks));
        properties.addProperty("region_z", Math.floorDiv(sample.blockZ(), regionSizeBlocks));
        properties.addProperty("sample_resolution_blocks", CHUNK_BLOCK_SPAN);
        properties.addProperty("surface_y", sample.surfaceY());
        properties.addProperty("slope_delta_blocks", sample.slopeDeltaBlocks());
        properties.addProperty("biome", sample.biomeId());

        feature.add("properties", properties);
        features.add(feature);
    }

    private static Optional<Path> terrainCacheFile(TerrainCacheKey key) {
        String rawDir = System.getProperty(TERRAIN_CACHE_DIR_PROPERTY, "").trim();
        if (rawDir.isEmpty()) {
            return Optional.empty();
        }
        String safeDimension = key.dimension()
                .replace(':', '_')
                .replace('/', '_')
                .replace('\\', '_');
        String fileName = "terrain-v" + key.version()
                + "-seed-" + key.seed()
                + "-dim-" + safeDimension
                + "-center-" + key.centerRegionX() + "_" + key.centerRegionZ()
                + "-radius-" + key.radiusRegions()
                + "-region-" + key.regionSizeBlocks()
                + "-cell-" + key.chunkBlockSpan()
                + ".tsv";
        return Optional.of(Path.of(rawDir).resolve(fileName));
    }

    private static Optional<List<TerrainSample>> readTerrainCache(Path cachePath, TerrainCacheKey expectedKey) {
        if (!Files.isRegularFile(cachePath)) {
            return Optional.empty();
        }
        try (BufferedReader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("#key\t")) {
                return Optional.empty();
            }
            String serializedKey = header.substring("#key\t".length());
            if (!serializedKey.equals(expectedKey.serialize())) {
                return Optional.empty();
            }

            List<TerrainSample> samples = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t", 7);
                if (parts.length != 7) {
                    return Optional.empty();
                }
                int chunkX = Integer.parseInt(parts[0]);
                int chunkZ = Integer.parseInt(parts[1]);
                int blockX = Integer.parseInt(parts[2]);
                int blockZ = Integer.parseInt(parts[3]);
                int surfaceY = Integer.parseInt(parts[4]);
                int slopeDeltaBlocks = Integer.parseInt(parts[5]);
                String biomeId = parts[6];
                samples.add(new TerrainSample(chunkX, chunkZ, blockX, blockZ, surfaceY, slopeDeltaBlocks, biomeId));
            }
            return Optional.of(samples);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static void writeTerrainCache(Path cachePath, TerrainCacheKey key, List<TerrainSample> samples) {
        try {
            Path parent = cachePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(cachePath, StandardCharsets.UTF_8)) {
                writer.write("#key\t");
                writer.write(key.serialize());
                writer.newLine();
                for (TerrainSample sample : samples) {
                    writer.write(Integer.toString(sample.chunkX()));
                    writer.write('\t');
                    writer.write(Integer.toString(sample.chunkZ()));
                    writer.write('\t');
                    writer.write(Integer.toString(sample.blockX()));
                    writer.write('\t');
                    writer.write(Integer.toString(sample.blockZ()));
                    writer.write('\t');
                    writer.write(Integer.toString(sample.surfaceY()));
                    writer.write('\t');
                    writer.write(Integer.toString(sample.slopeDeltaBlocks()));
                    writer.write('\t');
                    writer.write(sample.biomeId());
                    writer.newLine();
                }
            }
        } catch (IOException ignored) {
            // Terrain cache is best-effort and must never fail export.
        }
    }

    private static void addScoreProperties(JsonObject properties, PlacementCandidate candidate) {
        properties.addProperty("score_total", candidate.score().total());
        properties.addProperty("score_biome", candidate.score().biome());
        properties.addProperty("score_height", candidate.score().height());
        properties.addProperty("score_slope", candidate.score().slope());
        properties.addProperty("score_water", candidate.score().water());
    }

    private static void addBiomeAndContinentProperties(
            JsonObject properties,
            ServerLevel level,
            BlockPos center,
            Identifier knownBiomeId) {
        Identifier biomeId = knownBiomeId == null ? biomeId(level, center) : knownBiomeId;

        properties.addProperty("biome", biomeId.toString());
        addContinentProperties(properties, center, biomeId.toString());
    }

    private static Identifier biomeId(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos)
                .unwrapKey()
                .map(key -> key.identifier())
                .orElse(Identifier.parse("minecraft:plains"));
    }

    private static void addContinentProperties(JsonObject properties, BlockPos center, String biomeId) {
        int continentX = Math.floorDiv(center.getX(), CONTINENT_BLOCK_SPAN);
        int continentZ = Math.floorDiv(center.getZ(), CONTINENT_BLOCK_SPAN);
        String continent = continentX + "," + continentZ;

        properties.addProperty("continent", continent);
        properties.addProperty("continent_x", continentX);
        properties.addProperty("continent_z", continentZ);
        properties.addProperty("continent_block_span", CONTINENT_BLOCK_SPAN);
        properties.addProperty("continent_class", classifyContinentClass(biomeId));
    }

    private static String classifyContinentClass(String biomeId) {
        String path = biomeId;
        int separator = biomeId.indexOf(':');
        if (separator >= 0 && separator < biomeId.length() - 1) {
            path = biomeId.substring(separator + 1);
        }

        if (containsAny(path, "ocean", "deep_ocean")) {
            return "OCEANIC";
        }
        if (containsAny(path, "beach", "river", "swamp", "mangrove", "shore")) {
            return "COASTAL";
        }
        if (containsAny(path, "mountain", "peaks", "hills", "ridge", "cliffs")) {
            return "HIGHLAND";
        }
        return "CONTINENTAL";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String defaultFileName(String dimensionPath) {
        return "kingdom-placement-" + dimensionPath + "-" + TIMESTAMP_FORMAT.format(Instant.now());
    }

    private static int maxDelta(int center, int east, int west, int south, int north) {
        return Math.max(
                Math.max(Math.abs(center - east), Math.abs(center - west)),
                Math.max(Math.abs(center - south), Math.abs(center - north)));
    }

    private static int centerBlock(int chunkCoordinate) {
        return (chunkCoordinate * CHUNK_BLOCK_SPAN) + (CHUNK_BLOCK_SPAN / 2);
    }

    private static int sampleSurfaceY(
            ChunkGenerator chunkGenerator,
            RandomState randomState,
            ServerLevel level,
            int x,
            int z) {
        return chunkGenerator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static int resolveTerrainProgressStepPercent() {
        int configured = Integer.getInteger(TERRAIN_PROGRESS_STEP_PERCENT_PROPERTY, 10);
        return Math.max(1, Math.min(configured, 50));
    }

    private static boolean isTerrainProgressEnabled() {
        return Boolean.parseBoolean(System.getProperty(TERRAIN_PROGRESS_ENABLED_PROPERTY, "true"));
    }

    private static int resolveTerrainParallelism(CivPlacementConfig config) {
        if (!config.parallelRegionPlanning()) {
            return 1;
        }

        int available = Runtime.getRuntime().availableProcessors();
        int autoThreads = Math.max(1, available - 1);
        int configuredThreads = config.parallelRegionThreads() == 0 ? autoThreads : config.parallelRegionThreads();
        return Math.max(1, configuredThreads);
    }

    private static void runIndexedWork(int parallelism, int itemCount, IntConsumer work) {
        if (parallelism <= 1 || itemCount <= 1) {
            for (int index = 0; index < itemCount; index++) {
                work.accept(index);
            }
            return;
        }

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.submit(() -> IntStream.range(0, itemCount).parallel().forEach(work)).join();
        } finally {
            pool.shutdown();
        }
    }

    private static int sampleIndex(int sampledWidth, int row, int col) {
        return (row * sampledWidth) + col;
    }

    private static final class TerrainProgressTracker {
        private final String stage;
        private final int total;
        private final int stepCount;
        private final int parallelism;
        private final boolean enabled;
        private final long startNanos;
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger nextLogAt;

        private TerrainProgressTracker(
                String stage,
                int total,
                int stepCount,
                int parallelism,
                boolean enabled,
                long startNanos) {
            this.stage = stage;
            this.total = total;
            this.stepCount = stepCount;
            this.parallelism = parallelism;
            this.enabled = enabled;
            this.startNanos = startNanos;
            this.nextLogAt = new AtomicInteger(Math.min(total, stepCount));
        }

        private static TerrainProgressTracker create(
                String stage,
                int total,
                int stepPercent,
                int parallelism) {
            int stepCount = Math.max(1, (int) Math.ceil((total * stepPercent) / 100.0d));
            boolean enabled = isTerrainProgressEnabled() && total > 0;
            long startNanos = System.nanoTime();
            if (enabled) {
                LOGGER.info(
                        "[kingdom] terrain_export_progress stage={} started total={} step_percent={} parallelism={}",
                        stage,
                        total,
                        stepPercent,
                        parallelism);
            }
            return new TerrainProgressTracker(stage, total, stepCount, parallelism, enabled, startNanos);
        }

        private void increment() {
            if (!enabled || total <= 0) {
                return;
            }
            int done = completed.incrementAndGet();
            if (done < nextLogAt.get() && done < total) {
                return;
            }

            while (true) {
                int currentThreshold = nextLogAt.get();
                if (done < currentThreshold && done < total) {
                    return;
                }
                if (currentThreshold == Integer.MAX_VALUE) {
                    return;
                }

                int updatedThreshold;
                if (done >= total) {
                    updatedThreshold = Integer.MAX_VALUE;
                } else {
                    updatedThreshold = currentThreshold + stepCount;
                    if (updatedThreshold <= currentThreshold) {
                        updatedThreshold = currentThreshold + 1;
                    }
                    updatedThreshold = Math.min(updatedThreshold, total);
                }
                if (!nextLogAt.compareAndSet(currentThreshold, updatedThreshold)) {
                    continue;
                }

                int boundedDone = Math.min(done, total);
                int percent = (int) Math.round((boundedDone * 100.0d) / total);
                long elapsedMillis = nanosToMillis(System.nanoTime() - startNanos);
                LOGGER.info(
                        "[kingdom] terrain_export_progress stage={} done={}/{} ({}%) elapsed_ms={} parallelism={}",
                        stage,
                        boundedDone,
                        total,
                        percent,
                        elapsedMillis,
                        parallelism);

                return;
            }
        }
    }

    private record TerrainSamplingMetrics(
        long durationNanos,
        boolean cacheHit,
        int terrainFeatureCount,
        int terrainChunkCount,
        int terrainGridSampleCount,
        String mode,
        int parallelism
    ) {
        private static TerrainSamplingMetrics empty() {
            return new TerrainSamplingMetrics(0L, false, 0, 0, 0, "none", 0);
        }
    }

    private record TerrainCacheKey(
        int version,
        long seed,
        String dimension,
        int centerRegionX,
        int centerRegionZ,
        int radiusRegions,
        int regionSizeBlocks,
        int chunkBlockSpan,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ
    ) {
        private String serialize() {
            return version + "\t"
                + seed + "\t"
                + dimension + "\t"
                + centerRegionX + "\t"
                + centerRegionZ + "\t"
                + radiusRegions + "\t"
                + regionSizeBlocks + "\t"
                + chunkBlockSpan + "\t"
                + minChunkX + "\t"
                + maxChunkX + "\t"
                + minChunkZ + "\t"
                + maxChunkZ;
        }
    }

    private record TerrainSample(
            int chunkX,
            int chunkZ,
            int blockX,
            int blockZ,
            int surfaceY,
            int slopeDeltaBlocks,
            String biomeId) {
    }

    private static JsonObject point(int blockX, int blockZ) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("type", "Point");
        JsonArray coordinates = new JsonArray();
        coordinates.add(blockX);
        coordinates.add(blockZ);
        geometry.add("coordinates", coordinates);
        return geometry;
    }
}
