package com.michionlion.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import com.michionlion.KingdomMod;

@Mod(KingdomMod.MOD_ID)
public final class KingdomModNeoForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public KingdomModNeoForge(IEventBus modEventBus) {
        KingdomMod.init();
        bootstrapGameTestsIfEnabled(modEventBus);
    }

    private static void bootstrapGameTestsIfEnabled(IEventBus modEventBus) {
        if (!Boolean.getBoolean("neoforge.enableGameTest")) {
            return;
        }

        try {
            Class<?> bootstrapClass = Class.forName("com.michionlion.neoforge.gametest.NeoForgeWorldBootAndTraversalTests");
            bootstrapClass.getMethod("bootstrap", IEventBus.class).invoke(null, modEventBus);
            LOGGER.info("Registered NeoForge game tests for {}.", KingdomMod.MOD_ID);
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("NeoForge game test classes are not on the classpath; skipping test bootstrap.");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Failed to bootstrap NeoForge game tests.", error);
        }
    }
}
