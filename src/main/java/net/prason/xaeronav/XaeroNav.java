package net.prason.xaeronav;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.common.Mod;

@Mod(XaeroNav.MOD_ID)
public final class XaeroNav {
    public static final String MOD_ID = "xaeronav";
    public static final Logger LOGGER = LogUtils.getLogger();

    public XaeroNav() {
        LOGGER.info("XaeroNav initialized");
    }
}
