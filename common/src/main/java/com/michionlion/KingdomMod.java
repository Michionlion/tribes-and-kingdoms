package com.michionlion;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfigManager;
import com.michionlion.kingdom.dev.KingdomCommand;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class KingdomMod {
    public static final String MOD_ID = "kingdom";
    private static final Logger LOGGER = LogUtils.getLogger();

    private KingdomMod() {
    }

    public static void init() {
        KingdomPlacementConfigManager.initialize();
        KingdomCommand.bootstrap();
        LOGGER.info("Initializing {}.", MOD_ID);
    }
}
