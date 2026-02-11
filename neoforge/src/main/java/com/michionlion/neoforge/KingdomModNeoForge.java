package com.michionlion.neoforge;

import com.mojang.logging.LogUtils;
import com.michionlion.KingdomMod;
import com.michionlion.neoforge.gametest.NeoForgeWorldBootAndTraversalTests;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(KingdomMod.MOD_ID)
public final class KingdomModNeoForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public KingdomModNeoForge(IEventBus modEventBus) {
        KingdomMod.init();
        registerClientConfigScreenIfNeeded();
        registerGameTestsIfEnabled(modEventBus);
    }

    private static void registerGameTestsIfEnabled(IEventBus modEventBus) {
        if (!Boolean.getBoolean("neoforge.enableGameTest")) {
            return;
        }

        modEventBus.addListener(NeoForgeWorldBootAndTraversalTests::registerGameTests);
        LOGGER.info("Registered NeoForge game tests for {}.", KingdomMod.MOD_ID);
    }

    private static void registerClientConfigScreenIfNeeded() {
        if (Platform.getEnvironment() != Env.CLIENT) {
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName("com.michionlion.neoforge.client.KingdomModNeoForgeClientConfig");
            bridgeClass.getMethod("registerConfigScreen").invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Failed to register NeoForge config screen extension point.", error);
        }
    }
}
