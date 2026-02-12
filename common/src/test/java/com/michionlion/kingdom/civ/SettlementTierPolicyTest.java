package com.michionlion.kingdom.civ;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfig;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import com.michionlion.kingdom.civ.placement.SettlementTierPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementTierPolicyTest {
    @Test
    void capitalDistributionStaysNearTargetBands() {
        CivPlacementConfig config = CivPlacementConfig.from(new KingdomPlacementConfig());
        Map<TechTier, Integer> counts = new EnumMap<>(TechTier.class);
        for (TechTier tier : TechTier.values()) {
            counts.put(tier, 0);
        }

        Random random = new Random(42L);
        int samples = 40_000;
        for (int i = 0; i < samples; i++) {
            double score = config.stoneThreshold() + (random.nextDouble() * (1.0D - config.stoneThreshold()));
            long key = random.nextLong() ^ i;
            TechTier tier = SettlementTierPolicy.chooseCapitalTier(score, key, config);
            counts.merge(tier, 1, Integer::sum);
        }

        double stonePct = counts.get(TechTier.STONE) / (double) samples;
        double ironPct = counts.get(TechTier.IRON) / (double) samples;
        double diamondPct = counts.get(TechTier.DIAMOND) / (double) samples;
        double netheritePct = counts.get(TechTier.NETHERITE) / (double) samples;

        assertTrue(ironPct > stonePct, "Capitals should skew iron over stone.");
        assertTrue(ironPct > diamondPct, "Capitals should skew iron over diamond.");
        assertTrue(diamondPct >= 0.12D && diamondPct <= 0.27D, "Diamond capitals should stay near ~20%.");
        assertTrue(netheritePct >= 0.005D && netheritePct <= 0.04D, "Netherite capitals should stay very rare.");
    }

    @Test
    void townsNeverUseNetheriteAndKeepDiamondLow() {
        int total = 30_000;
        int diamondTowns = 0;

        for (int i = 0; i < total; i++) {
            double proximity = (i % 101) / 100.0D;
            TechTier tier = SettlementTierPolicy.chooseSatelliteTier(
                TechTier.DIAMOND,
                SettlementType.KINGDOM_TOWN,
                proximity,
                0x9E3779B97F4A7C15L ^ i
            );
            assertTrue(tier != TechTier.NETHERITE, "Kingdom towns should never be netherite tier.");
            if (tier == TechTier.DIAMOND) {
                diamondTowns++;
            }
        }

        double diamondPct = diamondTowns / (double) total;
        assertTrue(diamondPct > 0.0D && diamondPct < 0.10D, "Kingdom towns should have a very low diamond chance.");
    }

    @Test
    void outpostsTrendTowardIronForHigherTierCapitals() {
        int samples = 30_000;
        int ironWithStoneCapital = 0;
        int ironWithIronCapital = 0;
        int ironWithDiamondCapital = 0;

        for (int i = 0; i < samples; i++) {
            long key = 0xC2B2AE3D27D4EB4FL ^ i;
            if (SettlementTierPolicy.chooseSatelliteTier(TechTier.STONE, SettlementType.OUTPOST, 0.5D, key) == TechTier.IRON) {
                ironWithStoneCapital++;
            }
            if (SettlementTierPolicy.chooseSatelliteTier(TechTier.IRON, SettlementType.OUTPOST, 0.5D, key) == TechTier.IRON) {
                ironWithIronCapital++;
            }
            if (SettlementTierPolicy.chooseSatelliteTier(TechTier.DIAMOND, SettlementType.OUTPOST, 0.5D, key) == TechTier.IRON) {
                ironWithDiamondCapital++;
            }
        }

        assertTrue(ironWithIronCapital > ironWithStoneCapital, "Outposts should be more likely iron when the capital is iron.");
        assertTrue(ironWithDiamondCapital > ironWithStoneCapital, "Outposts should be more likely iron when the capital is diamond.");
        assertEquals(0, countNetheriteOutposts(samples), "Outposts should never be netherite tier.");
    }

    private static int countNetheriteOutposts(int samples) {
        int count = 0;
        for (int i = 0; i < samples; i++) {
            TechTier tier = SettlementTierPolicy.chooseSatelliteTier(
                TechTier.NETHERITE,
                SettlementType.OUTPOST,
                0.5D,
                0x94D049BB133111EBL ^ i
            );
            if (tier == TechTier.NETHERITE) {
                count++;
            }
        }
        return count;
    }
}
