package com.michionlion.fabric.client;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfig;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfigClient;

public final class KingdomModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> AutoConfigClient.getConfigScreen(KingdomPlacementConfig.class, parent).get();
    }
}
