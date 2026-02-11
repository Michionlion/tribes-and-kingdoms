package com.michionlion.kingdom.dev;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.placement.PlacementCandidate;
import com.michionlion.kingdom.civ.placement.RegionPlacementResult;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class PlacementDebugSnapshotStore {
    private static final Map<MinecraftServer, Map<Long, RegionSnapshot>> SNAPSHOTS_BY_SERVER = new WeakHashMap<>();

    private PlacementDebugSnapshotStore() {
    }

    public static synchronized void put(MinecraftServer server, RegionPlacementResult result) {
        Map<Long, RegionSnapshot> byRegion = SNAPSHOTS_BY_SERVER.computeIfAbsent(server, unused -> new LinkedHashMap<>());
        byRegion.put(result.regionKey().asLong(), new RegionSnapshot(result.regionKey(), result.candidates(), System.currentTimeMillis()));
    }

    public static synchronized List<RegionSnapshot> getInRadius(MinecraftServer server, RegionKey center, int radiusRegions) {
        Map<Long, RegionSnapshot> byRegion = SNAPSHOTS_BY_SERVER.get(server);
        if (byRegion == null || byRegion.isEmpty()) {
            return List.of();
        }

        List<RegionSnapshot> snapshots = new ArrayList<>();
        for (RegionSnapshot snapshot : byRegion.values()) {
            int dx = Math.abs(snapshot.regionKey().x() - center.x());
            int dz = Math.abs(snapshot.regionKey().z() - center.z());
            if (dx <= radiusRegions && dz <= radiusRegions) {
                snapshots.add(snapshot);
            }
        }

        snapshots.sort((left, right) -> Long.compare(left.regionKey().asLong(), right.regionKey().asLong()));
        return snapshots;
    }

    public record RegionSnapshot(RegionKey regionKey, List<PlacementCandidate> candidates, long timestampMs) {
        public RegionSnapshot {
            candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }
    }
}
