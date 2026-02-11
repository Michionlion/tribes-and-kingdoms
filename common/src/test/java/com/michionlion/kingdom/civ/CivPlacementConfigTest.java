package com.michionlion.kingdom.civ;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfig;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import com.michionlion.kingdom.civ.placement.CivPlacementPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivPlacementConfigTest {
    @Test
    void tierSelectionFollowsThresholdOrder() {
        CivPlacementConfig config = CivPlacementConfig.from(new KingdomPlacementConfig());

        assertEquals(TechTier.WOOD, config.tierForScore(config.woodThreshold()));
        assertEquals(TechTier.STONE, config.tierForScore(config.stoneThreshold()));
        assertEquals(TechTier.IRON, config.tierForScore(config.ironThreshold()));
        assertEquals(TechTier.DIAMOND, config.tierForScore(config.diamondThreshold()));
        assertEquals(TechTier.NETHERITE, config.tierForScore(config.netheriteThreshold()));

        assertFalse(config.passesMinimumThreshold(config.woodThreshold() - 0.01D));
        assertTrue(config.passesMinimumThreshold(config.woodThreshold()));
    }

    @Test
    void regionAtUsesConfiguredRegionSize() {
        CivPlacementPlanner planner = new CivPlacementPlanner();
        CivPlacementConfig config = CivPlacementConfig.from(new KingdomPlacementConfig());

        assertEquals(new RegionKey(0, 0), planner.regionAt(0, 0, config));
        assertEquals(new RegionKey(0, 0), planner.regionAt(2047, 2047, config));
        assertEquals(new RegionKey(1, 0), planner.regionAt(2048, 0, config));
        assertEquals(new RegionKey(-1, -1), planner.regionAt(-1, -1, config));
        assertEquals(new RegionKey(-2, 1), planner.regionAt(-4096, 3000, config));
    }
}
