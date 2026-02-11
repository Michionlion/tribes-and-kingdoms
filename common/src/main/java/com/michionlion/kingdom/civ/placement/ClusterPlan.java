package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.SettlementAnchor;

import java.util.List;

public record ClusterPlan(
    SettlementAnchor capital,
    List<SettlementAnchor> satellites
) {
    public List<SettlementAnchor> allAnchors() {
        if (satellites.isEmpty()) {
            return List.of(capital);
        }

        java.util.ArrayList<SettlementAnchor> combined = new java.util.ArrayList<>(satellites.size() + 1);
        combined.add(capital);
        combined.addAll(satellites);
        return List.copyOf(combined);
    }
}
