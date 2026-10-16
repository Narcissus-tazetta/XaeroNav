package net.prason.xaeronav.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.config.XaeroNavConfig;

/**
 * {@code -Pxaeronav.netherHintCheck=<名前>}で、HUDのネザー換算距離の行を場面ごとに撮ってクライアントを閉じる。
 * 撮るのは{@code run/screenshots/<名前>-far|near|off|nether.png}（遠い・近い・設定off・ネザーの中）。
 */
final class NetherHintCheck {

    private static final int WAIT_TICKS = 100;
    private static final BlockPos FAR = new BlockPos(3000, 0, 1000);
    private static final BlockPos NEAR = new BlockPos(500, 0, 0);

    private final String name;
    private int stage;
    private int ticks;
    private BlockPos home;

    private NetherHintCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.netherHintCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new NetherHintCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || ++ticks < WAIT_TICKS) {
            return;
        }
        ticks = 0;
        PathfindingState state = PathfindingState.INSTANCE;
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;
        switch (stage++) {
            case 0 -> {
                mc.options.pauseOnLostFocus = false;
                cfg.setNetherShortcutHintEnabled(true);
                teleport(mc, Level.OVERWORLD, new BlockPos(mc.player.getBlockX(), 0, mc.player.getBlockZ()), true);
            }
            case 1 -> {
                home = mc.player.blockPosition();
                state.setGoal(home.offset(FAR));
            }
            case 2 -> shot(mc, "far");
            case 3 -> state.setGoal(home.offset(NEAR));
            case 4 -> shot(mc, "near");
            case 5 -> {
                state.setGoal(home.offset(FAR));
                cfg.setNetherShortcutHintEnabled(false);
            }
            case 6 -> {
                shot(mc, "off");
                cfg.setNetherShortcutHintEnabled(true);
                teleport(mc, Level.NETHER, new BlockPos(home.getX() / 8, 64, home.getZ() / 8), false);
            }
            case 7 -> state.setGoal(mc.player.blockPosition().offset(FAR));
            case 8 -> shot(mc, "nether");
            default -> mc.stop();
        }
    }

    private void shot(Minecraft mc, String scene) {
        XaeroNav.LOGGER.info("nether hint check: {} — dimension {}, player {}, goal {}", scene, mc.level.dimension(),
                mc.player.blockPosition().toShortString(), PathfindingState.INSTANCE.goal());
        Screenshot.grab(mc.gameDirectory, name + "-" + scene + ".png", mc.getMainRenderTarget(),
                message -> XaeroNav.LOGGER.info("nether hint check: {}", message.getString()));
    }

    /** {@code onSurface}なら列の地表へ置く。落下・窒息で死なないよう無敵にする。 */
    private static void teleport(Minecraft mc, ResourceKey<Level> dimension, BlockPos at, boolean onSurface) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
            ServerLevel level = server.getLevel(dimension);
            int y = onSurface ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) : at.getY();
            player.setInvulnerable(true);
            player.teleportTo(level, at.getX() + 0.5, y, at.getZ() + 0.5, player.getYRot(), player.getXRot());
        });
    }
}
