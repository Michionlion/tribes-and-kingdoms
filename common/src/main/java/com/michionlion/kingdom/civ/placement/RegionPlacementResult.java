package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.SettlementAnchor;

import java.util.List;

public record RegionPlacementResult(
    RegionKey regionKey,
    List<SettlementAnchor> anchors,
    List<PlacementCandidate> candidates
) {
}
