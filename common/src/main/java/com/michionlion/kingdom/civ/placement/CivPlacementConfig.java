package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfig;
import com.michionlion.kingdom.civ.model.TechTier;

public record CivPlacementConfig(
    int regionSizeBlocks,
    int minAnchorSpacingBlocks,
    int candidateCellSizeBlocks,
    int maxCandidatesPerRegion,
    int surfaceSampleOffsetBlocks,
    int waterSearchRadiusBlocks,
    int waterSearchStepBlocks,
    double maxSlopeDelta,
    double biomeWeight,
    double heightWeight,
    double slopeWeight,
    double waterWeight,
    double woodThreshold,
    double stoneThreshold,
    double ironThreshold,
    double diamondThreshold,
    double netheriteThreshold,
    int woodRadius,
    int stoneRadius,
    int ironRadius,
    int diamondRadius,
    int netheriteRadius,
    int stoneSatelliteMin,
    int stoneSatelliteMax,
    int ironSatelliteMin,
    int ironSatelliteMax,
    int diamondSatelliteMin,
    int diamondSatelliteMax,
    int netheriteSatelliteMin,
    int netheriteSatelliteMax,
    int satelliteMinDistanceBlocks,
    int satelliteMaxDistanceBlocks,
    int defaultAroundRegionRadius,
    int defaultSummaryRegionRadius,
    int defaultExportRegionRadius,
    int defaultVisualizationRadiusBlocks,
    int visualizationParticlesPerAnchor,
    int visualizationVerticalMarkerHeight
) {
    public static CivPlacementConfig from(KingdomPlacementConfig source) {
        return new CivPlacementConfig(
            source.region.regionSizeBlocks,
            source.region.minAnchorSpacingBlocks,
            source.candidate.cellSizeBlocks,
            source.candidate.maxPerRegion,
            source.candidate.surfaceSampleOffsetBlocks,
            source.candidate.waterSearchRadiusBlocks,
            source.candidate.waterSearchStepBlocks,
            source.candidate.maxSlopeDelta,
            source.weights.biome,
            source.weights.height,
            source.weights.slope,
            source.weights.water,
            source.thresholds.wood,
            source.thresholds.stone,
            source.thresholds.iron,
            source.thresholds.diamond,
            source.thresholds.netherite,
            source.cluster.woodRadius,
            source.cluster.stoneRadius,
            source.cluster.ironRadius,
            source.cluster.diamondRadius,
            source.cluster.netheriteRadius,
            source.cluster.stoneSatelliteMin,
            source.cluster.stoneSatelliteMax,
            source.cluster.ironSatelliteMin,
            source.cluster.ironSatelliteMax,
            source.cluster.diamondSatelliteMin,
            source.cluster.diamondSatelliteMax,
            source.cluster.netheriteSatelliteMin,
            source.cluster.netheriteSatelliteMax,
            source.cluster.satelliteMinDistanceBlocks,
            source.cluster.satelliteMaxDistanceBlocks,
            source.command.defaultAroundRegionRadius,
            source.command.defaultSummaryRegionRadius,
            source.command.defaultExportRegionRadius,
            source.visualization.defaultRadiusBlocks,
            source.visualization.particlesPerAnchor,
            source.visualization.verticalMarkerHeight
        );
    }

    public int radiusForTier(TechTier tier) {
        return switch (tier) {
            case WOOD -> woodRadius;
            case STONE -> stoneRadius;
            case IRON -> ironRadius;
            case DIAMOND -> diamondRadius;
            case NETHERITE -> netheriteRadius;
        };
    }

    public int satelliteMinForTier(TechTier tier) {
        return switch (tier) {
            case WOOD -> 0;
            case STONE -> stoneSatelliteMin;
            case IRON -> ironSatelliteMin;
            case DIAMOND -> diamondSatelliteMin;
            case NETHERITE -> netheriteSatelliteMin;
        };
    }

    public int satelliteMaxForTier(TechTier tier) {
        return switch (tier) {
            case WOOD -> 0;
            case STONE -> stoneSatelliteMax;
            case IRON -> ironSatelliteMax;
            case DIAMOND -> diamondSatelliteMax;
            case NETHERITE -> netheriteSatelliteMax;
        };
    }

    public TechTier tierForScore(double score) {
        if (score >= netheriteThreshold) {
            return TechTier.NETHERITE;
        }
        if (score >= diamondThreshold) {
            return TechTier.DIAMOND;
        }
        if (score >= ironThreshold) {
            return TechTier.IRON;
        }
        if (score >= stoneThreshold) {
            return TechTier.STONE;
        }
        return TechTier.WOOD;
    }

    public boolean passesMinimumThreshold(double score) {
        return score >= woodThreshold;
    }
}
