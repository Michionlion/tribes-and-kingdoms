package com.michionlion.kingdom.civ.util;

import com.michionlion.kingdom.civ.model.RegionKey;

public final class AnchorIdGenerator {
    private AnchorIdGenerator() {
    }

    public static long deterministicId(long worldSeedHash, RegionKey region, int localOrdinal) {
        long mixed = mix64(worldSeedHash);
        mixed = mix64(mixed ^ region.asLong());
        mixed = mix64(mixed ^ Integer.toUnsignedLong(localOrdinal));
        return mixed;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
