package com.michionlion.kingdom.civ.model;

public record RegionKey(int x, int z) {
    public long asLong() {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static RegionKey fromLong(long packed) {
        int unpackedX = (int) (packed >> 32);
        int unpackedZ = (int) packed;
        return new RegionKey(unpackedX, unpackedZ);
    }
}
