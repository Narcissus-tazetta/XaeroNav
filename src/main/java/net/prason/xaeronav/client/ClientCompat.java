package net.prason.xaeronav.client;

import net.minecraft.client.Options;

/** Minecraft 1.16と新しい版とで呼び方だけが違うクライアントのAPI。 */
public final class ClientCompat {
    private ClientCompat() {
    }

    public static int renderDistance(Options options) {
        //? if >=1.17 {
        return options.getEffectiveRenderDistance();
        //?} else {
        /*return options.renderDistance;
        *///?}
    }
}
