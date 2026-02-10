package com.michionlion.fabric.client;

import com.michionlion.kingdom.dev.DevCommandBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class KingdomModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(DevCommandBridge::tick);
    }
}
