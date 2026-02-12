package com.michionlion.kingdom.civ;

import com.michionlion.kingdom.civ.placement.SuitabilitySampler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuitabilitySamplerBiomeGateTest {
    @Test
    void oceanicAndCoastalBiomesAreRejectedFromPlacement() {
        assertTrue(SuitabilitySampler.isOceanicOrCoastalBiome("minecraft:ocean"));
        assertTrue(SuitabilitySampler.isOceanicOrCoastalBiome("deep_lukewarm_ocean"));
        assertTrue(SuitabilitySampler.isOceanicOrCoastalBiome("minecraft:beach"));
        assertTrue(SuitabilitySampler.isOceanicOrCoastalBiome("stony_shore"));
        assertTrue(SuitabilitySampler.isOceanicOrCoastalBiome("minecraft:river"));
        assertTrue(SuitabilitySampler.isOceanicOrCoastalBiome("mangrove_swamp"));
        assertFalse(SuitabilitySampler.isLandPlacementBiome("minecraft:ocean"));
    }

    @Test
    void inlandBiomesRemainEligibleForPlacement() {
        assertFalse(SuitabilitySampler.isOceanicOrCoastalBiome("minecraft:plains"));
        assertFalse(SuitabilitySampler.isOceanicOrCoastalBiome("forest"));
        assertFalse(SuitabilitySampler.isOceanicOrCoastalBiome("badlands"));
        assertFalse(SuitabilitySampler.isOceanicOrCoastalBiome("cherry_grove"));
        assertTrue(SuitabilitySampler.isLandPlacementBiome("minecraft:plains"));
    }
}
