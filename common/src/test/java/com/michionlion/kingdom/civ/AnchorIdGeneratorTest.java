package com.michionlion.kingdom.civ;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.util.AnchorIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AnchorIdGeneratorTest {
    @Test
    void deterministicIdIsStableForSameInput() {
        RegionKey region = new RegionKey(12, -7);
        long idA = AnchorIdGenerator.deterministicId(12345L, region, 3);
        long idB = AnchorIdGenerator.deterministicId(12345L, region, 3);

        assertEquals(idA, idB);
    }

    @Test
    void deterministicIdChangesWhenInputChanges() {
        RegionKey region = new RegionKey(12, -7);
        long baseline = AnchorIdGenerator.deterministicId(12345L, region, 3);

        assertNotEquals(baseline, AnchorIdGenerator.deterministicId(12346L, region, 3));
        assertNotEquals(baseline, AnchorIdGenerator.deterministicId(12345L, new RegionKey(13, -7), 3));
        assertNotEquals(baseline, AnchorIdGenerator.deterministicId(12345L, region, 4));
    }

    @Test
    void deterministicIdHasNoCollisionsInSampleSet() {
        Set<Long> ids = new HashSet<>();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                for (int ordinal = 0; ordinal < 5; ordinal++) {
                    long id = AnchorIdGenerator.deterministicId(987654321L, new RegionKey(x, z), ordinal);
                    if (!ids.add(id)) {
                        throw new AssertionError("Unexpected collision for id " + id);
                    }
                }
            }
        }
    }
}
