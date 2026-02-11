package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.model.RegionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public final class PlacementCandidate {
    private final RegionKey regionKey;
    private final long deterministicKey;
    private final BlockPos center;
    private final Identifier biomeId;
    private final SuitabilityScore score;
    private TechTier assignedTier;
    private boolean accepted;
    private String rejectionReason;

    public PlacementCandidate(
        RegionKey regionKey,
        long deterministicKey,
        BlockPos center,
        Identifier biomeId,
        SuitabilityScore score,
        TechTier assignedTier,
        boolean accepted,
        String rejectionReason
    ) {
        this.regionKey = Objects.requireNonNull(regionKey, "regionKey");
        this.deterministicKey = deterministicKey;
        this.center = Objects.requireNonNull(center, "center");
        this.biomeId = Objects.requireNonNull(biomeId, "biomeId");
        this.score = Objects.requireNonNull(score, "score");
        this.assignedTier = Objects.requireNonNull(assignedTier, "assignedTier");
        this.accepted = accepted;
        this.rejectionReason = Objects.requireNonNullElse(rejectionReason, "");
    }

    public RegionKey regionKey() {
        return regionKey;
    }

    public long deterministicKey() {
        return deterministicKey;
    }

    public BlockPos center() {
        return center;
    }

    public Identifier biomeId() {
        return biomeId;
    }

    public SuitabilityScore score() {
        return score;
    }

    public TechTier assignedTier() {
        return assignedTier;
    }

    public boolean accepted() {
        return accepted;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public void setAssignedTier(TechTier assignedTier) {
        this.assignedTier = Objects.requireNonNull(assignedTier, "assignedTier");
    }

    public void accept() {
        this.accepted = true;
        this.rejectionReason = "";
    }

    public void reject(String reason) {
        this.accepted = false;
        this.rejectionReason = Objects.requireNonNullElse(reason, "rejected");
    }
}
