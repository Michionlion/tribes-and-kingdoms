package com.michionlion.kingdom.dev;

import com.michionlion.kingdom.civ.model.SettlementAnchor;
import com.michionlion.kingdom.civ.model.SettlementType;
import com.michionlion.kingdom.civ.model.TechTier;
import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class AnchorDebugVisualizer {
    private AnchorDebugVisualizer() {
    }

    public static void visualize(ServerLevel level, List<SettlementAnchor> anchors, CivPlacementConfig config) {
        for (SettlementAnchor anchor : anchors) {
            ParticleOptions tierParticle = particleForTier(anchor.tier());
            level.sendParticles(
                tierParticle,
                anchor.center().getX() + 0.5D,
                anchor.center().getY() + 1.0D,
                anchor.center().getZ() + 0.5D,
                config.visualizationParticlesPerAnchor(),
                0.35D,
                0.8D,
                0.35D,
                0.0D
            );

            int markerStep = 3;
            for (int yOffset = 0; yOffset <= config.visualizationVerticalMarkerHeight(); yOffset += markerStep) {
                level.sendParticles(
                    tierParticle,
                    anchor.center().getX() + 0.5D,
                    anchor.center().getY() + yOffset,
                    anchor.center().getZ() + 0.5D,
                    2,
                    0.05D,
                    0.05D,
                    0.05D,
                    0.0D
                );
            }

            if (anchor.type() == SettlementType.KINGDOM_CAPITAL) {
                level.sendParticles(
                    ParticleTypes.END_ROD,
                    anchor.center().getX() + 0.5D,
                    anchor.center().getY() + config.visualizationVerticalMarkerHeight() + 1.5D,
                    anchor.center().getZ() + 0.5D,
                    12,
                    0.2D,
                    0.2D,
                    0.2D,
                    0.0D
                );
            }
        }
    }

    private static ParticleOptions particleForTier(TechTier tier) {
        return switch (tier) {
            case WOOD -> ParticleTypes.COMPOSTER;
            case STONE -> ParticleTypes.CLOUD;
            case IRON -> ParticleTypes.CRIT;
            case DIAMOND -> ParticleTypes.GLOW;
            case NETHERITE -> ParticleTypes.FLAME;
        };
    }
}
