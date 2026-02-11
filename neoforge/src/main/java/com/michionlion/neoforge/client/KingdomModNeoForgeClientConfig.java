package com.michionlion.neoforge.client;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfig;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class KingdomModNeoForgeClientConfig {
    private KingdomModNeoForgeClientConfig() {
    }

    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
            IConfigScreenFactory.class,
            () -> (modContainer, parent) -> AutoConfigClient.getConfigScreen(KingdomPlacementConfig.class, parent).get()
        );
    }
}
