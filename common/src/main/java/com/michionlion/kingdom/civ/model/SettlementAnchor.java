package com.michionlion.kingdom.civ.model;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record SettlementAnchor(
    long id,
    BlockPos center,
    TechTier tier,
    SettlementType type,
    int radius,
    long civId,
    Set<Identifier> biomeTags
) {
    public SettlementAnchor {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(biomeTags, "biomeTags");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0");
        }

        biomeTags = Set.copyOf(new LinkedHashSet<>(biomeTags));
    }
}
