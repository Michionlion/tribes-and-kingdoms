package com.michionlion.kingdom.civ.config;

import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import dev.architectury.platform.Platform;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class KingdomPlacementConfigManager {
    private static final String BUNDLED_DEFAULTS_RESOURCE = "kingdom.toml";
    private static final String LEGACY_CONFIG_FILE_NAME = "kingdom-placement.toml";
    private static final String CONFIG_FILE_NAME = "kingdom.toml";
    private static volatile boolean initialized;

    private KingdomPlacementConfigManager() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        ensureConfigFileExistsWithBundledDefaults();
        AutoConfig.register(KingdomPlacementConfig.class, Toml4jConfigSerializer::new);
        ConfigHolder<KingdomPlacementConfig> holder = AutoConfig.getConfigHolder(KingdomPlacementConfig.class);
        holder.load();
        holder.save();

        initialized = true;
    }

    public static CivPlacementConfig snapshot() {
        ensureInitialized();
        return CivPlacementConfig.from(raw());
    }

    public static KingdomPlacementConfig raw() {
        ensureInitialized();
        return AutoConfig.getConfigHolder(KingdomPlacementConfig.class).getConfig();
    }

    public static boolean reload() {
        ensureInitialized();
        ensureConfigFileExistsWithBundledDefaults();
        return AutoConfig.getConfigHolder(KingdomPlacementConfig.class).load();
    }

    public static void save() {
        ensureInitialized();
        AutoConfig.getConfigHolder(KingdomPlacementConfig.class).save();
    }

    public static Path configPath() {
        return Platform.getConfigFolder().resolve(CONFIG_FILE_NAME);
    }

    public static boolean setValue(String path, String rawValue) {
        ensureInitialized();
        KingdomPlacementConfig config = raw();

        try {
            switch (path) {
                case "region.size" -> config.region.regionSizeBlocks = Integer.parseInt(rawValue);
                case "region.min_spacing" -> config.region.minAnchorSpacingBlocks = Integer.parseInt(rawValue);
                case "candidate.cell_size" -> config.candidate.cellSizeBlocks = Integer.parseInt(rawValue);
                case "candidate.max_per_region" -> config.candidate.maxPerRegion = Integer.parseInt(rawValue);
                case "candidate.water_radius" -> config.candidate.waterSearchRadiusBlocks = Integer.parseInt(rawValue);
                case "weights.biome" -> config.weights.biome = Double.parseDouble(rawValue);
                case "weights.height" -> config.weights.height = Double.parseDouble(rawValue);
                case "weights.slope" -> config.weights.slope = Double.parseDouble(rawValue);
                case "weights.water" -> config.weights.water = Double.parseDouble(rawValue);
                case "thresholds.wood" -> config.thresholds.wood = Double.parseDouble(rawValue);
                case "thresholds.stone" -> config.thresholds.stone = Double.parseDouble(rawValue);
                case "thresholds.iron" -> config.thresholds.iron = Double.parseDouble(rawValue);
                case "thresholds.diamond" -> config.thresholds.diamond = Double.parseDouble(rawValue);
                case "thresholds.netherite" -> config.thresholds.netherite = Double.parseDouble(rawValue);
                case "cluster.min_satellite_distance" -> config.cluster.satelliteMinDistanceBlocks = Integer.parseInt(rawValue);
                case "cluster.max_satellite_distance" -> config.cluster.satelliteMaxDistanceBlocks = Integer.parseInt(rawValue);
                case "performance.parallel_region_planning" -> config.performance.parallelRegionPlanning = Boolean.parseBoolean(rawValue);
                case "performance.parallel_region_threads" -> config.performance.parallelRegionThreads = Integer.parseInt(rawValue);
                default -> {
                    return false;
                }
            }

            try {
                config.validatePostLoad();
            } catch (ConfigData.ValidationException error) {
                return false;
            }
            save();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private static void ensureConfigFileExistsWithBundledDefaults() {
        Path configPath = configPath();
        if (Files.isRegularFile(configPath)) {
            return;
        }

        migrateLegacyConfigIfPresent(configPath);
        if (Files.isRegularFile(configPath)) {
            return;
        }

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (InputStream stream = KingdomPlacementConfigManager.class
                .getClassLoader()
                .getResourceAsStream(BUNDLED_DEFAULTS_RESOURCE)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing bundled defaults resource: " + BUNDLED_DEFAULTS_RESOURCE);
                }
                Files.copy(stream, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to initialize config defaults at " + configPath.toAbsolutePath(), error);
        }
    }

    private static void migrateLegacyConfigIfPresent(Path newConfigPath) {
        Path legacyPath = Platform.getConfigFolder().resolve(LEGACY_CONFIG_FILE_NAME);
        if (!Files.isRegularFile(legacyPath)) {
            return;
        }
        if (Files.isRegularFile(newConfigPath)) {
            return;
        }

        try {
            Path parent = newConfigPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(legacyPath, newConfigPath);
        } catch (IOException error) {
            throw new IllegalStateException(
                "Failed to migrate legacy config from " + legacyPath.toAbsolutePath() + " to " + newConfigPath.toAbsolutePath(),
                error
            );
        }
    }
}
