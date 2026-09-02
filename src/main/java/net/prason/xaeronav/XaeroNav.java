package net.prason.xaeronav;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/** MOD全体で使う識別子とロガー。ローダーごとの起動処理は{@code net.prason.xaeronav.platform}にある。 */
public final class XaeroNav {

    public static final String MOD_ID = "xaeronav";
    public static final Logger LOGGER = LogUtils.getLogger();

    private XaeroNav() {
    }
}
