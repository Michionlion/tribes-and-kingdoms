package com.michionlion.kingdom.civ.model;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

public record RoadEdge(
    long id,
    long fromAnchorId,
    long toAnchorId,
    int width,
    Identifier paletteId,
    List<BlockPos> controlPoints
) {
    public RoadEdge {
        Objects.requireNonNull(paletteId, "paletteId");
        Objects.requireNonNull(controlPoints, "controlPoints");
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }

        controlPoints = List.copyOf(controlPoints);
    }
}
