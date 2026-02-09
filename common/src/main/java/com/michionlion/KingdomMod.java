package com.michionlion;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class KingdomMod {
    public static final String MOD_ID = "kingdom";
    private static final Logger LOGGER = LogUtils.getLogger();

    private KingdomMod() {
    }

    public static void init() {
        LOGGER.info("Initializing {}.", MOD_ID);
    }
}
