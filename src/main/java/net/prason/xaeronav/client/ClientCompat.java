package net.prason.xaeronav.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.world.phys.Vec3;

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

    public static Vec3 cameraPosition(Camera camera) {
        //? if >=1.21.11 {
        /*return camera.position();
        *///?} else {
        return camera.getPosition();
        //?}
    }
}
