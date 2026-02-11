package com.michionlion.kingdom.civ.placement;

public record SuitabilityScore(
    double biome,
    double height,
    double slope,
    double water,
    double total
) {
}
