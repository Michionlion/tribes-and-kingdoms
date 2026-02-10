package com.michionlion.neoforge.client;

import com.michionlion.KingdomMod;
import com.michionlion.kingdom.dev.DevCommandBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = KingdomMod.MOD_ID, value = Dist.CLIENT)
public final class KingdomModNeoForgeClient {
    private KingdomModNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        DevCommandBridge.tick(Minecraft.getInstance());
    }
}
