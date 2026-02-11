package com.michionlion.kingdom.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.placement.PlacementCandidate;
import com.michionlion.kingdom.civ.state.CivWorldState;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GeoJsonExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        .withZone(ZoneOffset.UTC);
    private static final int CONTINENT_BLOCK_SPAN = 8192;

    private GeoJsonExporter() {
    }

    public static Path exportRegionRadius(
        ServerLevel level,
        CivWorldState state,
        RegionKey center,
        int radiusRegions,
        boolean includeRejected,
        String fileName
    ) throws IOException {
        Map<Long, PlacementCandidate> acceptedCandidatesByPos = new LinkedHashMap<>();
        List<PlacementDebugSnapshotStore.RegionSnapshot> snapshots = PlacementDebugSnapshotStore.getInRadius(level.getServer(), center, radiusRegions);
        for (PlacementDebugSnapshotStore.RegionSnapshot snapshot : snapshots) {
            for (PlacementCandidate candidate : snapshot.candidates()) {
                if (candidate.accepted()) {
                    acceptedCandidatesByPos.put(candidate.center().asLong(), candidate);
                }
            }
        }

        JsonArray features = new JsonArray();
        for (Map.Entry<Long, LongList> entry : state.anchorsByRegion().entrySet()) {
            RegionKey regionKey = RegionKey.fromLong(entry.getKey());
            if (Math.abs(regionKey.x() - center.x()) > radiusRegions || Math.abs(regionKey.z() - center.z()) > radiusRegions) {
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
            }
        }

        if (includeRejected) {
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
                }
            }
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
        Files.writeString(outputFile, GSON.toJson(collection), StandardCharsets.UTF_8);
        return outputFile;
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
        Identifier knownBiomeId
    ) {
        Identifier biomeId = knownBiomeId;
        if (biomeId == null) {
            biomeId = level.getBiome(center)
                .unwrapKey()
                .map(key -> key.identifier())
                .orElse(Identifier.parse("minecraft:plains"));
        }

        properties.addProperty("biome", biomeId.toString());
        addContinentProperties(properties, center, biomeId.toString());
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
