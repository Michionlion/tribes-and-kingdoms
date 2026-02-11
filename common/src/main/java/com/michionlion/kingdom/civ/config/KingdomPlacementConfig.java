package com.michionlion.kingdom.civ.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = "kingdom-placement")
public final class KingdomPlacementConfig implements ConfigData {
    public Region region = new Region();
    public Candidate candidate = new Candidate();
    public Weights weights = new Weights();
    public Thresholds thresholds = new Thresholds();
    public Cluster cluster = new Cluster();
    public Command command = new Command();
    public Visualization visualization = new Visualization();

    @Override
    public void validatePostLoad() throws ValidationException {
        region.regionSizeBlocks = clamp(region.regionSizeBlocks, 512, 8192);
        region.minAnchorSpacingBlocks = clamp(region.minAnchorSpacingBlocks, 64, 4096);

        candidate.cellSizeBlocks = clamp(candidate.cellSizeBlocks, 64, 1024);
        candidate.maxPerRegion = clamp(candidate.maxPerRegion, 1, 128);
        candidate.surfaceSampleOffsetBlocks = clamp(candidate.surfaceSampleOffsetBlocks, 2, 32);
        candidate.waterSearchRadiusBlocks = clamp(candidate.waterSearchRadiusBlocks, 8, 256);
        candidate.waterSearchStepBlocks = clamp(candidate.waterSearchStepBlocks, 2, 16);
        candidate.maxSlopeDelta = clamp(candidate.maxSlopeDelta, 1.0, 64.0);

        weights.biome = clamp(weights.biome, 0.0, 1.0);
        weights.height = clamp(weights.height, 0.0, 1.0);
        weights.slope = clamp(weights.slope, 0.0, 1.0);
        weights.water = clamp(weights.water, 0.0, 1.0);

        thresholds.wood = clamp(thresholds.wood, 0.0, 1.0);
        thresholds.stone = clamp(thresholds.stone, thresholds.wood, 1.0);
        thresholds.iron = clamp(thresholds.iron, thresholds.stone, 1.0);
        thresholds.diamond = clamp(thresholds.diamond, thresholds.iron, 1.0);
        thresholds.netherite = clamp(thresholds.netherite, thresholds.diamond, 1.0);

        cluster.woodRadius = clamp(cluster.woodRadius, 16, 512);
        cluster.stoneRadius = clamp(cluster.stoneRadius, 16, 512);
        cluster.ironRadius = clamp(cluster.ironRadius, 16, 512);
        cluster.diamondRadius = clamp(cluster.diamondRadius, 16, 512);
        cluster.netheriteRadius = clamp(cluster.netheriteRadius, 16, 512);

        cluster.stoneSatelliteMin = clamp(cluster.stoneSatelliteMin, 0, 8);
        cluster.stoneSatelliteMax = clamp(cluster.stoneSatelliteMax, cluster.stoneSatelliteMin, 12);
        cluster.ironSatelliteMin = clamp(cluster.ironSatelliteMin, 0, 8);
        cluster.ironSatelliteMax = clamp(cluster.ironSatelliteMax, cluster.ironSatelliteMin, 12);
        cluster.diamondSatelliteMin = clamp(cluster.diamondSatelliteMin, 0, 8);
        cluster.diamondSatelliteMax = clamp(cluster.diamondSatelliteMax, cluster.diamondSatelliteMin, 12);
        cluster.netheriteSatelliteMin = clamp(cluster.netheriteSatelliteMin, 0, 8);
        cluster.netheriteSatelliteMax = clamp(cluster.netheriteSatelliteMax, cluster.netheriteSatelliteMin, 12);

        cluster.satelliteMinDistanceBlocks = clamp(cluster.satelliteMinDistanceBlocks, 16, 512);
        cluster.satelliteMaxDistanceBlocks = clamp(cluster.satelliteMaxDistanceBlocks, cluster.satelliteMinDistanceBlocks, 1024);

        command.defaultAroundRegionRadius = clamp(command.defaultAroundRegionRadius, 0, 32);
        command.defaultSummaryRegionRadius = clamp(command.defaultSummaryRegionRadius, 0, 64);
        command.defaultExportRegionRadius = clamp(command.defaultExportRegionRadius, 0, 64);

        visualization.defaultRadiusBlocks = clamp(visualization.defaultRadiusBlocks, 64, 8192);
        visualization.particlesPerAnchor = clamp(visualization.particlesPerAnchor, 2, 128);
        visualization.verticalMarkerHeight = clamp(visualization.verticalMarkerHeight, 1, 64);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Region {
        public int regionSizeBlocks = 2048;
        public int minAnchorSpacingBlocks = 384;
    }

    public static final class Candidate {
        public int cellSizeBlocks = 256;
        public int maxPerRegion = 12;
        public int surfaceSampleOffsetBlocks = 8;
        public int waterSearchRadiusBlocks = 96;
        public int waterSearchStepBlocks = 4;
        public double maxSlopeDelta = 16.0;
    }

    public static final class Weights {
        public double biome = 0.40;
        public double height = 0.15;
        public double slope = 0.25;
        public double water = 0.20;
    }

    public static final class Thresholds {
        public double wood = 0.52;
        public double stone = 0.58;
        public double iron = 0.66;
        public double diamond = 0.74;
        public double netherite = 0.82;
    }

    public static final class Cluster {
        public int woodRadius = 48;
        public int stoneRadius = 64;
        public int ironRadius = 80;
        public int diamondRadius = 96;
        public int netheriteRadius = 112;

        public int stoneSatelliteMin = 2;
        public int stoneSatelliteMax = 3;
        public int ironSatelliteMin = 3;
        public int ironSatelliteMax = 5;
        public int diamondSatelliteMin = 5;
        public int diamondSatelliteMax = 7;
        public int netheriteSatelliteMin = 6;
        public int netheriteSatelliteMax = 8;

        public int satelliteMinDistanceBlocks = 96;
        public int satelliteMaxDistanceBlocks = 240;
    }

    public static final class Command {
        public int defaultAroundRegionRadius = 2;
        public int defaultSummaryRegionRadius = 3;
        public int defaultExportRegionRadius = 4;
    }

    public static final class Visualization {
        public int defaultRadiusBlocks = 2048;
        public int particlesPerAnchor = 28;
        public int verticalMarkerHeight = 12;
    }
}
