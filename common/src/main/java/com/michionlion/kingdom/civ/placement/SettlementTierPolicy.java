package com.michionlion.kingdom.civ.placement;

import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;

import java.util.Objects;

public final class SettlementTierPolicy {
    private SettlementTierPolicy() {
    }

    public static TechTier chooseCapitalTier(double score, long deterministicKey, CivPlacementConfig config) {
        Objects.requireNonNull(config, "config");

        double score01 = clamp01((score - config.stoneThreshold()) / Math.max(0.01D, 1.0D - config.stoneThreshold()));
        double lowBias = 1.0D - score01;
        double highBias = score01;

        double stoneWeight = 0.22D + (0.14D * lowBias);
        double ironWeight = 0.56D + (0.10D * highBias);
        double diamondWeight = 0.20D + (0.03D * highBias) - (0.03D * lowBias);
        double netheriteWeight = 0.0075D + (0.003D * highBias) - (0.005D * lowBias);

        return chooseTierFromWeights(
            deterministicRoll01(deterministicKey ^ 0xA9E3779B97F4A7C1L),
            Math.max(0.0D, stoneWeight),
            Math.max(0.0D, ironWeight),
            Math.max(0.0D, diamondWeight),
            Math.max(0.0D, netheriteWeight)
        );
    }

    public static TechTier chooseSatelliteTier(
        TechTier capitalTier,
        SettlementType type,
        double proximity01,
        long deterministicKey
    ) {
        double proximity = clamp01(proximity01);
        double stoneWeight;
        double ironWeight;
        double diamondWeight;

        if (type == SettlementType.KINGDOM_TOWN) {
            switch (capitalTier) {
                case STONE -> {
                    stoneWeight = lerp(0.85D, 0.95D, proximity);
                    ironWeight = 0.14D;
                    diamondWeight = 0.01D;
                }
                case IRON -> {
                    stoneWeight = lerp(0.60D, 0.36D, proximity);
                    ironWeight = lerp(0.38D, 0.62D, proximity);
                    diamondWeight = lerp(0.02D, 0.03D, proximity);
                }
                case DIAMOND -> {
                    stoneWeight = lerp(0.34D, 0.25D, proximity);
                    ironWeight = lerp(0.60D, 0.67D, proximity);
                    diamondWeight = lerp(0.06D, 0.08D, proximity);
                }
                case NETHERITE -> {
                    stoneWeight = lerp(0.50D, 0.30D, proximity);
                    ironWeight = lerp(0.48D, 0.65D, proximity);
                    diamondWeight = lerp(0.01D, 0.05D, proximity);
                }
                case WOOD -> {
                    stoneWeight = 0.90D;
                    ironWeight = 0.09D;
                    diamondWeight = 0.01D;
                }
                default -> throw new IllegalStateException("Unhandled capital tier: " + capitalTier);
            }
        } else if (type == SettlementType.OUTPOST) {
            switch (capitalTier) {
                case STONE -> {
                    stoneWeight = 0.88D;
                    ironWeight = 0.11D;
                    diamondWeight = 0.01D;
                }
                case IRON -> {
                    stoneWeight = 0.58D;
                    ironWeight = 0.40D;
                    diamondWeight = 0.02D;
                }
                case DIAMOND -> {
                    stoneWeight = 0.30D;
                    ironWeight = 0.65D;
                    diamondWeight = 0.05D;
                }
                case NETHERITE -> {
                    stoneWeight = 0.48D;
                    ironWeight = 0.46D;
                    diamondWeight = 0.06D;
                }
                case WOOD -> {
                    stoneWeight = 0.90D;
                    ironWeight = 0.09D;
                    diamondWeight = 0.01D;
                }
                default -> throw new IllegalStateException("Unhandled capital tier: " + capitalTier);
            }
        } else {
            return TechTier.WOOD;
        }

        return chooseTierFromWeights(
            deterministicRoll01(deterministicKey ^ 0xC3A5C85C97CB3127L),
            stoneWeight,
            ironWeight,
            diamondWeight,
            0.0D
        );
    }

    static double deterministicRoll01(long deterministicKey) {
        long mixed = mix64(deterministicKey);
        long positive = mixed >>> 1;
        return positive / (double) Long.MAX_VALUE;
    }

    private static TechTier chooseTierFromWeights(double roll01, double stoneWeight, double ironWeight, double diamondWeight, double netheriteWeight) {
        double total = stoneWeight + ironWeight + diamondWeight + netheriteWeight;
        if (total <= 0.0D) {
            return TechTier.STONE;
        }

        double cursor = clamp01(roll01) * total;
        if (cursor < stoneWeight) {
            return TechTier.STONE;
        }
        cursor -= stoneWeight;
        if (cursor < ironWeight) {
            return TechTier.IRON;
        }
        cursor -= ironWeight;
        if (cursor < diamondWeight) {
            return TechTier.DIAMOND;
        }
        return netheriteWeight > 0.0D ? TechTier.NETHERITE : TechTier.IRON;
    }

    private static double lerp(double start, double end, double t) {
        return start + ((end - start) * clamp01(t));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
