package net.prason.xaeronav.dev;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 開発クライアントで、表示中の経路をなぞってプレイヤーを動かす計測用の自動操縦。配布jarには入らない（devmodのソースセットは
 * runClientにだけ載る）。{@code -Pxaeronav.autopilot=dx,dz}で、入った場所から(dx, dz)ずらした列を目的地にする。
 *
 * <p>位置はクライアントで動かす（サーバー側で位置を指定してもクライアントの位置は変わらない）。掘る・置く手は、そこへ
 * 向かうときに内蔵サーバー側で実際に掘る・置くので、地形が変わる——ワールドは複製して使うこと。チャンクの更新による組み直しも
 * 本番どおり起きる。物理（ジャンプの溜め・滑り）は再現しないので、経路の質ではなく組み直し・探索の重さを本番のチャンクで測るためのもの。
 */
@Mod(value = "xaeronavdev", dist = Dist.CLIENT)
public final class Autopilot {

    /**
     * 1手進む間隔（tick）。1手は1〜1.4ブロックなので疾走（約0.28ブロック/tick）と同じくらい。中心から中心へ飛ばすのは、
     * 間を少しずつ動かすと角を横切る所で体がブロックに掛かり、クライアントの物理が毎tick押し戻して進まなかったため。
     */
    private static final int STEP_TICKS = 4;
    /** 入ってから目的地を置くまで。周りのチャンクが読み込まれるのを待つ。 */
    private static final int SETTLE_TICKS = 100;

    private final int dx;
    private final int dz;
    private final long limitTicks;
    private int ticks;
    private BlockPos goal;
    private boolean finished;

    public Autopilot() {
        String spec = System.getProperty("xaeronav.autopilot");
        if (spec == null) {
            this.dx = 0;
            this.dz = 0;
            this.limitTicks = 0;
            return;
        }
        String[] parts = spec.split(",");
        this.dx = Integer.parseInt(parts[0].trim());
        this.dz = Integer.parseInt(parts[1].trim());
        this.limitTicks = 20L * 60 * Long.getLong("xaeronav.autopilotMinutes", 10L);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        XaeroNav.LOGGER.info("XaeroNav autopilot: armed (offset {},{}, limit {} ticks)", dx, dz, limitTicks);
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (finished || mc.level == null || mc.player == null || server == null) {
            return;
        }
        ticks++;
        if (goal == null) {
            if (ticks < SETTLE_TICKS) {
                return;
            }
            BlockPos at = mc.player.blockPosition();
            goal = PathfindingState.INSTANCE.setGoal(at.offset(dx, 0, dz));
            XaeroNav.LOGGER.info("XaeroNav autopilot: start {} -> goal {}", at.toShortString(),
                    goal == null ? "none" : goal.toShortString());
            if (goal == null) {
                finish(mc, "could not set the goal");
            }
            return;
        }
        PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
        if (view.arrived()) {
            finish(mc, "arrived");
            return;
        }
        if (view.goal() == null) {
            finish(mc, "goal cleared");
            return;
        }
        if (ticks >= limitTicks) {
            finish(mc, "time limit");
            return;
        }
        PathResult result = view.currentResult();
        boolean report = ticks % 200 == 0;
        if (result == null || result.steps().isEmpty()) {
            if (report) {
                XaeroNav.LOGGER.info("XaeroNav autopilot: no path (result={}, flying={}, computing={}) at {}",
                        result == null ? "null" : "empty", view.flying(), view.computing(),
                        mc.player.blockPosition().toShortString());
            }
            return;
        }
        List<PathStep> steps = result.steps();
        int target = targetStep(steps, mc.player.position());
        Vec3 next = center(steps.get(target).pos());
        if (report) {
            XaeroNav.LOGGER.info("XaeroNav autopilot: step {}/{} at {}, player {}", target, steps.size(),
                    steps.get(target).pos().toShortString(), mc.player.position());
        }
        if (ticks % STEP_TICKS != 0) {
            mc.player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        PathStep work = steps.get(target);
        float yaw = (float) Math.toDegrees(Math.atan2(-(next.x - mc.player.getX()), next.z - mc.player.getZ()));
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            for (BlockPos cell : work.digCells()) {
                level.destroyBlock(cell, false);
            }
            if (work.placedBlockPos() != null && level.getBlockState(work.placedBlockPos()).canBeReplaced()) {
                level.setBlockAndUpdate(work.placedBlockPos(), Blocks.COBBLESTONE.defaultBlockState());
            }
            player.setInvulnerable(true);
            player.resetFallDistance();
        });
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot(yaw);
        mc.player.setPos(next.x, next.y, next.z);
    }

    /** いちばん近い手の次の手。 */
    private static int targetStep(List<PathStep> steps, Vec3 at) {
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < steps.size(); i++) {
            double d = center(steps.get(i).pos()).distanceToSqr(at);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        return Math.min(steps.size() - 1, nearest + 1);
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private void finish(Minecraft mc, String reason) {
        finished = true;
        XaeroNav.LOGGER.info(String.format(Locale.ROOT, "XaeroNav autopilot: finished (%s) after %.1fs at %s",
                reason, ticks / 20.0, mc.player.blockPosition().toShortString()));
        mc.stop();
    }
}
