package com.michionlion.kingdom.civ.config;

import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import dev.architectury.platform.Platform;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;

import java.nio.file.Path;

public final class KingdomPlacementConfigManager {
    private static volatile boolean initialized;

    private KingdomPlacementConfigManager() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

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
        return AutoConfig.getConfigHolder(KingdomPlacementConfig.class).load();
    }

    public static void save() {
        ensureInitialized();
        AutoConfig.getConfigHolder(KingdomPlacementConfig.class).save();
    }

    public static Path configPath() {
        return Platform.getConfigFolder().resolve("kingdom-placement.toml");
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
}
