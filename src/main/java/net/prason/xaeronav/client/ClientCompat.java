package net.prason.xaeronav.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
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

    public static Camera mainCamera(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gameRenderer.mainCamera();
        *///?} else {
        return mc.gameRenderer.getMainCamera();
        //?}
    }

    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.screen();
        *///?} else {
        return mc.screen;
        //?}
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        /*mc.gui.setScreen(screen);
        *///?} else {
        mc.setScreen(screen);
        //?}
    }

    public static boolean hudHidden(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.hud.isHidden();
        *///?} else {
        return mc.options.hideGui;
        //?}
    }
}
