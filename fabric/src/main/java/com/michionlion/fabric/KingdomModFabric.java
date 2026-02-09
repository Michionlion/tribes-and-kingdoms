package com.michionlion.fabric;

import net.fabricmc.api.ModInitializer;

import com.michionlion.KingdomMod;

public final class KingdomModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        KingdomMod.init();
    }
}
