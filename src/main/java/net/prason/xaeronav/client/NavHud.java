package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.xaero.XaeroHookHealth;

/**
 * 画面上部の案内表示。「次にどちらへ曲がるか」「残りの道のり・所要時間」を出す。
 *
 * <p>探索は展開ノード数の上限で打ち切られるので、遠い目的地では経路が途中で終わる。そのことを
 * ここで明示しないと、線が何も無い場所で切れているようにしか見えない。
 */
public final class NavHud {

    private static final int MARGIN_TOP = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 4;

    private static final int BACKGROUND_COLOR = 0x90000000;
    private static final int PRIMARY_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFB0B0B0;
    private static final int WARNING_COLOR = 0xFFFFC24D;

    private final List<Component> lines = new ArrayList<>(4);
    private final List<Integer> colors = new ArrayList<>(4);

    // 警告すべき区間があるかは経路が変わったときにしか変わらない。HUDは毎フレーム描かれるので、
    // 全ステップの走査を経路1本につき1度で済ませる
    private final PathCache<PathSuffixes> suffixes = new PathCache<>();

    public void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !XaeroNavConfig.INSTANCE.hudEnabled()) {
            return;
        }
        if (PathfindingState.INSTANCE.goal() == null) {
            return;
        }

        lines.clear();
        colors.clear();
        PathResult result = PathfindingState.INSTANCE.currentResult();
        PathfindingState.StuckReason stuck = PathfindingState.INSTANCE.stuckReason();
        if (PathfindingState.INSTANCE.arrived()) {
            add(Component.translatable("hud.xaeronav.arrived"), PRIMARY_COLOR);
        } else if (PathfindingState.INSTANCE.flying()) {
            // 空中経路が引けなかったこと（読み込み済みの範囲に抜け道が無い）と、そもそも案内が
            // 出ていないことは別。前者を「経路なし」と同じ文言にすると、地上と同じ失敗に見える
            add(PathfindingState.INSTANCE.flightRoute().isEmpty()
                    ? Component.translatable("hud.xaeronav.flying_no_route")
                    : Component.translatable("hud.xaeronav.flying"), SECONDARY_COLOR);
            add(Component.translatable("hud.xaeronav.direct_distance",
                    straightDistance(mc, PathfindingState.INSTANCE.goal())), SECONDARY_COLOR);
            int climb = upcomingClimb(PathfindingState.INSTANCE.flightRoute());
            if (climb >= CLIMB_NOTICE_BLOCKS) {
                // 上昇はプレイヤーが行動を要求される唯一の点。ロケットが無ければ速度と高度を
                // 交換するしかなく、線だけ見て「登れ」と分かっても間に合わないことがある
                add(Component.translatable("hud.xaeronav.flight_climb", climb), WARNING_COLOR);
            }
        } else if (result == null || result.steps().isEmpty()) {
            if (stuck != null) {
                addUnreachable(stuck);
            } else {
                // 「経路なし」は今回の探索の結果でしかない。次の探索では出るかもしれないので、
                // 結論（addUnreachable）とは違う言い方にする
                add(PathfindingState.INSTANCE.computing()
                        ? Component.translatable("hud.xaeronav.searching")
                        : Component.translatable("hud.xaeronav.no_route"), SECONDARY_COLOR);
            }
            add(Component.translatable("hud.xaeronav.direct_distance",
                    straightDistance(mc, PathfindingState.INSTANCE.goal())), SECONDARY_COLOR);
        } else {
            // 部分経路が出ていても、それが目的地へ通じていないと分かったなら先に言う。この経路は
            // 「行ける所まで」であって案内の続きではないので、黙って曲がり角だけ出すと、
            // 行き止まりまで歩いてから初めて気付くことになる
            if (stuck != null) {
                addUnreachable(stuck);
            }
            boolean climbing = PathfindingState.INSTANCE.climbingToSurface();
            if (climbing) {
                // 本来の目的地ではなく、まず地上へ出るまでの中継経路であることを示す。
                // 出さないと、なぜ目的地と違う方向へ案内されるのか分からなくなる
                add(Component.translatable("hud.xaeronav.climbing_to_surface"), SECONDARY_COLOR);
            }
            if (PathfindingState.INSTANCE.rerouted()) {
                // 案内が急に変わった理由を出す。出さないと、それまで歩いていた道が
                // 突然消えたようにしか見えない
                add(Component.translatable("hud.xaeronav.rerouted"), WARNING_COLOR);
            }
            NavGuidance guidance = NavGuidance.forPath(result, mc.player.blockPosition());
            PathSuffixes ahead = suffixes.get(result, PathSuffixes::new);
            int from = PathProgress.INSTANCE.indexFor(result) + 1;
            boolean endsAtDestination = PathfindingState.INSTANCE.currentPathEndsAtDestination();
            add(instruction(guidance, climbing, endsAtDestination), PRIMARY_COLOR);
            add(Component.translatable(remainingKey(endsAtDestination),
                    guidance.remainingBlocks, time(guidance.remainingSeconds)), SECONDARY_COLOR);
            // 経路の色だけでは「ここでボートを出す」ことまでは伝わらない。岸に着いてから
            // 気付いたのでは、そこまでの案内が前提ごと成立していない。
            // 乗っている間は出さない——すでに済んでいる支度を促し続けることになる
            if (ahead.usesBoat(from) && !ChunkView.ridingBoat(mc.player)) {
                add(Component.translatable("hud.xaeronav.boat_ahead"), SECONDARY_COLOR);
            }
            // 持ち物で足りない経路は、予算を外した緩和の梯子を通って出てくる（他に道が無い場合）。
            // 足りているうちは黙っている——設置を含む経路はエンドではほぼ全てなので、常に出すと
            // 警告として意味を失う。クリエイティブは持ち物が空でも置けるので数えない
            if (!mc.player.getAbilities().instabuild) {
                int needed = ahead.placements(from);
                int available = ChunkView.countPlaceableBlocks(mc.player);
                if (needed > available) {
                    add(Component.translatable("hud.xaeronav.blocks_short", needed, available), WARNING_COLOR);
                }
            }
            if (ahead.hasRisk(from, PathRisk.DROWNING)) {
                // 線の色だけでは「息が続かない」ことまでは伝わらない。潜る前に分かる必要がある
                add(Component.translatable("hud.xaeronav.drowning"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.MLG_REQUIRED)) {
                // 着地の瞬間に操作が要る区間なので、辿り着いてから気付いたのでは間に合わない
                add(Component.translatable("hud.xaeronav.mlg_required"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.FALL_DAMAGE)) {
                add(Component.translatable("hud.xaeronav.fall_damage"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.SNEAK_OVER_MAGMA)) {
                // 踏んでから気付くのでは遅い（走って乗ると即座に燃える）
                add(Component.translatable("hud.xaeronav.sneak_over_magma"), WARNING_COLOR);
            }
            // 詰みと判断済みなら「点線をたどってください」は嘘になる（その先に道が無いと
            // 分かっているから詰みなので）。結論の方だけを残す
            if (!guidance.complete && stuck == null) {
                add(Component.translatable("hud.xaeronav.incomplete"), WARNING_COLOR);
            }
        }

        // mixinは当たっているのに地図へ描かれていないことに気付けるのはここだけ。
        // 経路は出ているので、黙っていると「地図連携だけ壊れた」ではなく「そういうもの」に見える
        if (XaeroHookHealth.worldMapRenderBroken()) {
            add(Component.translatable("hud.xaeronav.hook_render_missing"), WARNING_COLOR);
        }

        draw(graphics, mc.font);
    }

    private void add(Component line, int color) {
        lines.add(line);
        colors.add(color);
    }

    /**
     * 「目的地へ行けない」という結論と、その理由・打つ手を出す。
     *
     * <p>結論と理由を分けるのが要点。結論だけでは何をすればいいか分からず、理由だけでは
     * 「探索が続いているのか止まっているのか」が分からない。止まっていること（と再開の条件）は
     * 結論の側に含める——止まっていると知らないまま待ち続けるのが一番損をする。
     */
    private void addUnreachable(PathfindingState.StuckReason reason) {
        add(Component.translatable("hud.xaeronav.unreachable"), WARNING_COLOR);
        add(Component.translatable(PathfindingState.stuckHintKey(reason)), SECONDARY_COLOR);
    }

    /** 経路変更時に一度だけ作る、各添字から末尾までのHUD集計。 */
    static final class PathSuffixes {
        private final int[] riskMasks;
        private final boolean[] boats;
        private final int[] placements;

        PathSuffixes(PathResult result) {
            List<PathStep> steps = result.steps();
            riskMasks = new int[steps.size() + 1];
            boats = new boolean[steps.size() + 1];
            placements = new int[steps.size() + 1];
            for (int i = steps.size() - 1; i >= 0; i--) {
                PathStep step = steps.get(i);
                riskMasks[i] = riskMasks[i + 1] | (1 << step.risk().ordinal());
                boats[i] = boats[i + 1] || step.boating();
                placements[i] = placements[i + 1] + (step.bridging() ? 1 : 0);
            }
        }

        boolean hasRisk(int from, PathRisk risk) {
            return (riskMasks[index(from)] & (1 << risk.ordinal())) != 0;
        }

        boolean usesBoat(int from) {
            return boats[index(from)];
        }

        int placements(int from) {
            return placements[index(from)];
        }

        private int index(int from) {
            return Math.max(0, Math.min(from, riskMasks.length - 1));
        }
    }

    private static Component instruction(NavGuidance guidance, boolean climbing, boolean endsAtDestination) {
        String key = instructionKey(guidance.turn, climbing, endsAtDestination);
        return guidance.turn == NavGuidance.Turn.LEFT || guidance.turn == NavGuidance.Turn.RIGHT
                ? Component.translatable(key, guidance.turnDistance)
                : Component.translatable(key);
    }

    /** 表示中の実線が本来の目的地まで届くときだけ、距離を単に「残り」と呼べる。 */
    static String remainingKey(boolean endsAtDestination) {
        return endsAtDestination ? "hud.xaeronav.remaining" : "hud.xaeronav.path_remaining";
    }

    /**
     * 経路末端の意味を取り違えない案内文を選ぶ。到達済みの中継経路でも、その末端は目的地ではない。
     */
    static String instructionKey(NavGuidance.Turn turn, boolean climbing, boolean endsAtDestination) {
        return switch (turn) {
            case ARRIVE -> climbing
                    ? "hud.xaeronav.surface_ahead"
                    : endsAtDestination ? "hud.xaeronav.arriving" : "hud.xaeronav.route_continues";
            case STRAIGHT -> "hud.xaeronav.straight";
            case LEFT -> "hud.xaeronav.turn_left";
            case RIGHT -> "hud.xaeronav.turn_right";
        };
    }

    /** 目的地までの直線距離。経路が出せないときでも、せめて遠いのか近いのかは分かるようにする。 */
    /** これ以上の上昇が控えているなら知らせる（ブロック）。 */
    private static final int CLIMB_NOTICE_BLOCKS = 12;

    /** 経路の残りで登ることになる高さの合計。降下は差し引かない（降りた分は登り返さないため）。 */
    private static int upcomingClimb(FlightRoute route) {
        List<Vec3> points = route.points();
        double climb = 0.0;
        for (int i = FlightProgress.INSTANCE.segmentFor(route); i + 1 < points.size(); i++) {
            climb += Math.max(0.0, points.get(i + 1).y - points.get(i).y);
        }
        return (int) Math.round(climb);
    }

    private static int straightDistance(Minecraft mc, BlockPos goal) {
        if (goal == null) {
            return 0;
        }
        double dx = goal.getX() + 0.5 - mc.player.getX();
        double dy = goal.getY() - mc.player.getY();
        double dz = goal.getZ() + 0.5 - mc.player.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static Component time(int seconds) {
        return seconds >= 60
                ? Component.translatable("hud.xaeronav.minutes_seconds", seconds / 60, seconds % 60)
                : Component.translatable("hud.xaeronav.seconds", seconds);
    }

    private void draw(GuiGraphics graphics, Font font) {
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int centerX = graphics.guiWidth() / 2;
        int boxWidth = width + PADDING_X * 2;
        int boxHeight = (lines.size() - 1) * LINE_HEIGHT + font.lineHeight + PADDING_Y * 2;
        graphics.fill(centerX - boxWidth / 2, MARGIN_TOP, centerX + boxWidth / 2, MARGIN_TOP + boxHeight,
                BACKGROUND_COLOR);

        int y = MARGIN_TOP + PADDING_Y;
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawCenteredString(font, lines.get(i), centerX, y, colors.get(i));
            y += LINE_HEIGHT;
        }
    }
}
