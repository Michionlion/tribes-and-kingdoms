package com.michionlion.kingdom.civ.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CivGraph {
    private final Map<Long, SettlementAnchor> anchorsById;
    private final Map<Long, RoadEdge> edgesById;

    public CivGraph() {
        this.anchorsById = new LinkedHashMap<>();
        this.edgesById = new LinkedHashMap<>();
    }

    public CivGraph(Map<Long, SettlementAnchor> anchorsById, Map<Long, RoadEdge> edgesById) {
        this.anchorsById = new LinkedHashMap<>(Objects.requireNonNull(anchorsById, "anchorsById"));
        this.edgesById = new LinkedHashMap<>(Objects.requireNonNull(edgesById, "edgesById"));
    }

    public Map<Long, SettlementAnchor> anchorsById() {
        return Collections.unmodifiableMap(anchorsById);
    }

    public Map<Long, RoadEdge> edgesById() {
        return Collections.unmodifiableMap(edgesById);
    }

    public SettlementAnchor anchor(long anchorId) {
        return anchorsById.get(anchorId);
    }

    public RoadEdge edge(long edgeId) {
        return edgesById.get(edgeId);
    }

    public SettlementAnchor putAnchor(SettlementAnchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        return anchorsById.put(anchor.id(), anchor);
    }

    public RoadEdge putEdge(RoadEdge edge) {
        Objects.requireNonNull(edge, "edge");
        return edgesById.put(edge.id(), edge);
    }

    public SettlementAnchor removeAnchor(long anchorId) {
        return anchorsById.remove(anchorId);
    }

    public RoadEdge removeEdge(long edgeId) {
        return edgesById.remove(edgeId);
    }

    public void clear() {
        anchorsById.clear();
        edgesById.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CivGraph civGraph)) {
            return false;
        }
        return anchorsById.equals(civGraph.anchorsById) && edgesById.equals(civGraph.edgesById);
    }

    @Override
    public int hashCode() {
        return Objects.hash(anchorsById, edgesById);
    }
}
