package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.util.GameCompat;
import org.jspecify.annotations.Nullable;

/**
 * 画面上部の案内表示。近くで必要になる操作と、残りの道のり・所要時間を出す。
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
    private static final double ACTION_NOTICE_BLOCKS = 12.0;

    private final List<Component> lines = new ArrayList<>(4);
    private final List<Integer> colors = new ArrayList<>(4);

    // 警告すべき区間があるかは経路が変わったときにしか変わらない。HUDは毎フレーム描かれるので、
    // 全ステップの走査を経路1本につき1度で済ませる
    private final PathCache<PathSuffixes> suffixes = new PathCache<>();
    private final GoalEta eta = new GoalEta();

    public void render(
            //? if >=1.20 {
            GuiGraphics graphics
            //?} else {
            /*PoseStack graphics
            *///?}
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || ClientCompat.hudHidden(mc) || !XaeroNavConfig.INSTANCE.hudEnabled()) {
            return;
        }
        // ここで1度だけ取得し、以降はこのインスタンスだけを読む。個々のgetterを描画中に何度も
        // 呼ぶと、その間にワーカーcallbackが割り込んで「どの瞬間にも存在しなかった組み合わせ」
        // （例: 新しいgoalと古いstuck理由）を1フレームだけ表示しうる
        PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
        if (view.goal() == null) {
            return;
        }

        lines.clear();
        colors.clear();
        PathResult result = view.currentResult();
        PathfindingState.StuckReason stuck = view.stuckReason();
        PathfindingState.StopProgress stop = PathfindingState.INSTANCE.stopProgress();
        if (stop != null && !view.arrived()) {
            add(TextCompat.translatable("hud.xaeronav.via_progress", stop.index(), stop.total()), SECONDARY_COLOR);
        }
        if (view.arrived()) {
            add(TextCompat.translatable("hud.xaeronav.arrived"), PRIMARY_COLOR);
        } else if (view.flying() && view.skyPillar() != null) {
            // 空の下では線を引かず、降りる地点（柱）の方角と距離だけを出す
            BlockPos pillar = view.skyPillar();
            boolean atGoal = pillar.getX() == view.goal().getX() && pillar.getZ() == view.goal().getZ();
            add(TextCompat.translatable(atGoal ? "hud.xaeronav.sky_goal" : "hud.xaeronav.sky_descent",
                    bearingArrow(mc, pillar), horizontalDistance(mc, pillar)), PRIMARY_COLOR);
            if (!atGoal) {
                add(TextCompat.translatable("hud.xaeronav.direct_distance",
                        straightDistance(mc, view.goal())), SECONDARY_COLOR);
            }
        } else if (view.flying()) {
            // 空中経路が引けなかったこと（読み込み済みの範囲に抜け道が無い）と、そもそも案内が
            // 出ていないことは別。前者を「経路なし」と同じ文言にすると、地上と同じ失敗に見える
            boolean ghast = ChunkView.mount(mc.player).kind() == MountState.Kind.HAPPY_GHAST;
            boolean noRoute = view.flightRoute().isEmpty();
            add(TextCompat.translatable(ghast
                    ? noRoute ? "hud.xaeronav.flying_ghast_no_route" : "hud.xaeronav.flying_ghast"
                    : noRoute ? "hud.xaeronav.flying_no_route" : "hud.xaeronav.flying"), SECONDARY_COLOR);
            add(TextCompat.translatable("hud.xaeronav.direct_distance",
                    straightDistance(mc, view.goal())), SECONDARY_COLOR);
            int climb = upcomingClimb(view.flightRoute());
            // ハッピーガストは自分で上がれて、その時間は値段に入っている
            if (!ghast && climb >= CLIMB_NOTICE_BLOCKS) {
                // 上昇はプレイヤーが行動を要求される唯一の点。ロケットが無ければ速度と高度を
                // 交換するしかなく、線だけ見て「登れ」と分かっても間に合わないことがある
                add(TextCompat.translatable("hud.xaeronav.flight_climb", climb), WARNING_COLOR);
            }
        } else if (result == null || result.steps().isEmpty()) {
            addMountChange(view.mountChange());
            if (stuck != null) {
                addUnreachable(stuck);
            } else {
                // 「経路なし」は今回の探索の結果でしかない。次の探索では出るかもしれないので、
                // 結論（addUnreachable）とは違う言い方にする
                add(view.computing()
                        ? TextCompat.translatable("hud.xaeronav.searching")
                        : TextCompat.translatable("hud.xaeronav.no_route"), SECONDARY_COLOR);
            }
            if (stuck == null) {
                // 経路が出る前から所要時間の目安を出す。探索は初回に数秒かかる
                double ticks = eta.ticks(mc.player.blockPosition(), view.goal(), view.coarseRouteWaypoints());
                add(TextCompat.translatable("hud.xaeronav.direct_distance_eta",
                        straightDistance(mc, view.goal()), time(NavGuidance.estimateSeconds(ticks))), SECONDARY_COLOR);
            } else {
                add(TextCompat.translatable("hud.xaeronav.direct_distance",
                        straightDistance(mc, view.goal())), SECONDARY_COLOR);
            }
        } else {
            // 部分経路が出ていても、それが目的地へ通じていないと分かったなら先に言う。この経路は
            // 「行ける所まで」であって案内の続きではないので、黙って曲がり角だけ出すと、
            // 行き止まりまで歩いてから初めて気付くことになる
            if (stuck != null) {
                addUnreachable(stuck);
            }
            boolean climbing = view.climbingToSurface();
            if (climbing) {
                // 本来の目的地ではなく、まず地上へ出るまでの中継経路であることを示す。
                // 出さないと、なぜ目的地と違う方向へ案内されるのか分からなくなる
                add(TextCompat.translatable("hud.xaeronav.climbing_to_surface"), SECONDARY_COLOR);
            }
            MountState mount = ChunkView.mount(mc.player);
            PathSuffixes ahead = suffixes.get(result, PathSuffixes::new);
            int from = PathProgress.INSTANCE.indexFor(result) + 1;
            if (view.mountChange() != null) {
                addMountChange(view.mountChange());
            } else if (mount.ridden() && ahead.rides(from)) {
                // 線の色は歩きと同じなので、乗り物の通れる道に変わったことはここでしか分からない
                add(TextCompat.translatable(switch (mount.kind()) {
                    case CAMEL -> "hud.xaeronav.riding_camel";
                    case NAUTILUS -> "hud.xaeronav.riding_nautilus";
                    default -> "hud.xaeronav.riding";
                }), SECONDARY_COLOR);
            }
            if (view.rerouted()) {
                // 案内が急に変わった理由を出す。出さないと、それまで歩いていた道が
                // 突然消えたようにしか見えない
                add(TextCompat.translatable("hud.xaeronav.rerouted"), WARNING_COLOR);
            }
            boolean endsAtDestination = view.currentPathEndsAtDestination();
            // 行けないと分かった目的地までの時間は出さない。実線の終点までの時間だけにする
            boolean estimateBeyond = !endsAtDestination && stuck == null;
            double beyondTicks = estimateBeyond
                    ? eta.ticks(result.steps().get(result.steps().size() - 1).pos(), view.goal(),
                    view.coarseRouteWaypoints())
                    : 0.0;
            NavGuidance guidance = NavGuidance.forPath(result, mc.player.blockPosition(), beyondTicks);
            PathSuffixes.Action next = ahead.nextAction(from);
            String endpoint = guidance.nearEnd ? endpointKey(climbing, endsAtDestination, stuck != null) : null;
            if (next != null && ahead.distanceToAction(from) <= ACTION_NOTICE_BLOCKS) {
                boolean camel = mount.kind() == MountState.Kind.CAMEL;
                add(TextCompat.translatable(switch (next) {
                    case DISMOUNT -> switch (mount.kind()) {
                        case CAMEL -> "hud.xaeronav.action_dismount_camel";
                        case NAUTILUS -> "hud.xaeronav.action_dismount_nautilus";
                        default -> next.key();
                    };
                    // ラクダのジャンプキーは突進
                    case MOUNT_JUMP -> camel ? "hud.xaeronav.action_mount_jump_camel" : next.key();
                    default -> next.key();
                }), PRIMARY_COLOR);
                endpoint = null;
            } else if (endpoint != null) {
                add(TextCompat.translatable(endpoint), PRIMARY_COLOR);
            }
            add(TextCompat.translatable(remainingKey(endsAtDestination, estimateBeyond),
                    guidance.remainingBlocks, time(guidance.remainingSeconds)), SECONDARY_COLOR);
            // 経路の色だけでは「ここでボートを出す」ことまでは伝わらない。岸に着いてから
            // 気付いたのでは、そこまでの案内が前提ごと成立していない。
            // 乗っている間は出さない——すでに済んでいる支度を促し続けることになる
            // 降りるのは乗っていては通れない所の手前。近づいてからでは、そこまで乗って来た理由が分からない
            double toDismount = ahead.distanceToDismount(from);
            if (Double.isFinite(toDismount) && toDismount > ACTION_NOTICE_BLOCKS && mount.ridden()) {
                add(TextCompat.translatable(switch (mount.kind()) {
                    case CAMEL -> "hud.xaeronav.dismount_ahead_camel";
                    case NAUTILUS -> "hud.xaeronav.dismount_ahead_nautilus";
                    default -> "hud.xaeronav.dismount_ahead";
                }, (int) Math.round(toDismount)), SECONDARY_COLOR);
            }
            if (ahead.usesBoat(from) && !ChunkView.ridingBoat(mc.player)) {
                add(TextCompat.translatable("hud.xaeronav.boat_ahead"), SECONDARY_COLOR);
            }
            // 持っていなければ、経路は線路上に置いてあるトロッコに乗るものしか無い
            if (ahead.usesCart(from) && !ChunkView.ridingMinecart(mc.player)) {
                add(TextCompat.translatable(ChunkView.carryingMinecart(mc.player)
                        ? "hud.xaeronav.cart_ahead" : "hud.xaeronav.cart_ahead_parked"), SECONDARY_COLOR);
            }
            // 持ち物で足りない経路は、予算を外した緩和の梯子を通って出てくる（他に道が無い場合）。
            // 足りているうちは黙っている——設置を含む経路はエンドではほぼ全てなので、常に出すと
            // 警告として意味を失う。クリエイティブは持ち物が空でも置けるので数えない
            if (!GameCompat.abilities(mc.player).instabuild) {
                int needed = ahead.placements(from);
                int available = ChunkView.countPlaceableBlocks(mc.player);
                if (needed > available) {
                    add(TextCompat.translatable("hud.xaeronav.blocks_short", needed, available), WARNING_COLOR);
                }
            }
            if (ahead.hasRisk(from, PathRisk.DROWNING)) {
                // 線の色だけでは「息が続かない」ことまでは伝わらない。潜る前に分かる必要がある
                add(TextCompat.translatable("hud.xaeronav.drowning"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.MLG_REQUIRED)) {
                // 着地の瞬間に操作が要る区間なので、辿り着いてから気付いたのでは間に合わない
                add(TextCompat.translatable("hud.xaeronav.mlg_required"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.FALL_DAMAGE)) {
                add(TextCompat.translatable("hud.xaeronav.fall_damage"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.SNEAK_OVER_MAGMA)) {
                // 踏んでから気付くのでは遅い（走って乗ると即座に燃える）
                add(TextCompat.translatable("hud.xaeronav.sneak_over_magma"), WARNING_COLOR);
            }
            // 詰みと判断済みなら「続きが計算される」は嘘になる（その先に道が無いと分かっているから
            // 詰みなので）。末端の手前で「案内が続く」を出している間も、同じことを2行で言うことになる
            if (!guidance.complete && stuck == null && !"hud.xaeronav.route_continues".equals(endpoint)) {
                add(TextCompat.translatable("hud.xaeronav.incomplete"), SECONDARY_COLOR);
            }
        }

        // mixinは当たっているのに地図へ描かれていないことに気付けるのはここだけ。
        // 経路は出ているので、黙っていると「地図連携だけ壊れた」ではなく「そういうもの」に見える
        if (XaeroHookHealth.worldMapRenderBroken()) {
            add(TextCompat.translatable("hud.xaeronav.hook_render_missing"), WARNING_COLOR);
        }

        draw(graphics, mc.font);
    }

    /** 乗り物が変わって引き直した理由。乗ったのか降りたのかで、経路の何が変わるかが違う。 */
    private void addMountChange(@Nullable MountState now) {
        if (now == null) {
            return;
        }
        add(TextCompat.translatable(switch (now.kind()) {
            case HORSE -> "hud.xaeronav.mount_changed_horse";
            case CAMEL -> "hud.xaeronav.mount_changed_camel";
            case NAUTILUS -> "hud.xaeronav.mount_changed_nautilus";
            // ハッピーガストに乗ったときは飛行の行が出る
            case HAPPY_GHAST, NONE -> "hud.xaeronav.mount_changed_off";
        }), SECONDARY_COLOR);
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
        add(TextCompat.translatable("hud.xaeronav.unreachable"), WARNING_COLOR);
        add(TextCompat.translatable(PathfindingState.stuckHintKey(reason)), SECONDARY_COLOR);
    }

    /** 経路変更時に一度だけ作る、各添字から末尾までのHUD集計。 */
    static final class PathSuffixes {
        enum Action {
            DIG("hud.xaeronav.action_dig"),
            PLACE("hud.xaeronav.action_place"),
            JUMP("hud.xaeronav.action_jump"),
            CLIMB("hud.xaeronav.action_climb"),
            ALIGHT("hud.xaeronav.action_alight"),
            MOUNT_JUMP("hud.xaeronav.action_mount_jump"),
            DISMOUNT("hud.xaeronav.action_dismount");

            private final String key;

            Action(String key) {
                this.key = key;
            }

            String key() {
                return key;
            }
        }

        private final int[] riskMasks;
        private final boolean[] boats;
        private final boolean[] carts;
        private final int[] placements;
        private final int[] nextActionSteps;
        private final int[] dismountSteps;
        private final boolean[] rides;
        private final Action[] actions;
        private final double[] blocks;

        PathSuffixes(PathResult result) {
            List<PathStep> steps = result.steps();
            riskMasks = new int[steps.size() + 1];
            boats = new boolean[steps.size() + 1];
            carts = new boolean[steps.size() + 1];
            placements = new int[steps.size() + 1];
            nextActionSteps = new int[steps.size() + 1];
            dismountSteps = new int[steps.size() + 1];
            rides = new boolean[steps.size() + 1];
            actions = new Action[steps.size()];
            blocks = new double[steps.size()];
            nextActionSteps[steps.size()] = -1;
            dismountSteps[steps.size()] = -1;
            for (int i = 1; i < steps.size(); i++) {
                blocks[i] = blocks[i - 1] + Math.sqrt(steps.get(i - 1).pos().distSqr(steps.get(i).pos()));
            }
            for (int i = steps.size() - 1; i >= 0; i--) {
                PathStep step = steps.get(i);
                riskMasks[i] = riskMasks[i + 1] | (1 << step.risk().ordinal());
                boats[i] = boats[i + 1] || step.boating();
                boolean riding = step.movement() == MovementType.CART;
                carts[i] = carts[i + 1] || riding;
                // 降りるのは乗車の最後のセル。経路の末尾で降りる場合も、着いた所で降りる操作が要る
                boolean alight = riding && (i + 1 == steps.size() || steps.get(i + 1).movement() != MovementType.CART);
                placements[i] = placements[i + 1] + (step.bridging() ? 1 : 0);
                boolean dismount = step.movement() == MovementType.DISMOUNT;
                dismountSteps[i] = dismount ? i : dismountSteps[i + 1];
                rides[i] = rides[i + 1] || step.movement().rides();
                actions[i] = dismount ? Action.DISMOUNT
                        : alight ? Action.ALIGHT
                        : step.movement() == MovementType.MOUNT_JUMP ? Action.MOUNT_JUMP
                        : step.digging() ? Action.DIG
                        : step.bridging() ? Action.PLACE
                        : step.movement() == MovementType.JUMP ? Action.JUMP
                        : step.climbing() ? Action.CLIMB : null;
                nextActionSteps[i] = actions[i] != null ? i : nextActionSteps[i + 1];
            }
        }

        boolean hasRisk(int from, PathRisk risk) {
            return (riskMasks[index(from)] & (1 << risk.ordinal())) != 0;
        }

        boolean usesBoat(int from) {
            return boats[index(from)];
        }

        /** この先に乗り物に乗ったまま進む段があるか。 */
        boolean rides(int from) {
            return rides[index(from)];
        }

        boolean usesCart(int from) {
            return carts[index(from)];
        }

        int placements(int from) {
            return placements[index(from)];
        }

        Action nextAction(int from) {
            int step = nextActionSteps[index(from)];
            return step < 0 ? null : actions[step];
        }

        double distanceToAction(int from) {
            return distanceTo(nextActionSteps[index(from)], from);
        }

        /** この先で乗り物を降りる段までの距離。降りなければ無限大。 */
        double distanceToDismount(int from) {
            return distanceTo(dismountSteps[index(from)], from);
        }

        private double distanceTo(int step, int from) {
            return step < 0 ? Double.POSITIVE_INFINITY : blocks[step] - blocks[Math.max(0, index(from) - 1)];
        }

        private int index(int from) {
            return Math.max(0, Math.min(from, riskMasks.length - 1));
        }
    }

    /**
     * 表示中の実線が本来の目的地まで届くときだけ、距離を単に「残り」と呼べる。届いていなければ距離は実線の終点までで、
     * 時間は{@code toDestination}なら目的地まで（実線の先は見積もり）、そうでなければ実線の終点まで。
     */
    static String remainingKey(boolean endsAtDestination, boolean toDestination) {
        return endsAtDestination ? "hud.xaeronav.remaining"
                : toDestination ? "hud.xaeronav.path_remaining_eta" : "hud.xaeronav.path_remaining";
    }

    /**
     * 経路末端の意味を取り違えない案内文を選ぶ。到達済みの中継経路でも、その末端は目的地ではない。
     * 詰みと判断した経路の末端は行き止まりなので、「案内が続く」とは言わない（{@code null}）。
     */
    static String endpointKey(boolean climbing, boolean endsAtDestination, boolean stuck) {
        if (climbing) {
            return "hud.xaeronav.surface_ahead";
        }
        if (endsAtDestination) {
            return "hud.xaeronav.arriving";
        }
        return stuck ? null : "hud.xaeronav.route_continues";
    }

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

    /** 向いている方向から見た{@code target}の方角。8方向の矢印で、真上が正面。 */
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    static String bearingArrow(double fromX, double fromZ, float yaw, double toX, double toZ) {
        // Minecraftのヨーは南(+Z)が0で、右回り（西が90）に増える
        double targetYaw = Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
        double relative = ((targetYaw - yaw) % 360.0 + 360.0) % 360.0;
        return ARROWS[(int) Math.round(relative / 45.0) % ARROWS.length];
    }

    private static String bearingArrow(Minecraft mc, BlockPos target) {
        return bearingArrow(mc.player.getX(), mc.player.getZ(), GameCompat.yaw(mc.player),
                target.getX() + 0.5, target.getZ() + 0.5);
    }

    private static int horizontalDistance(Minecraft mc, BlockPos target) {
        return (int) Math.round(Math.hypot(target.getX() + 0.5 - mc.player.getX(),
                target.getZ() + 0.5 - mc.player.getZ()));
    }

    /** 目的地までの直線距離。経路が出せないときでも、せめて遠いのか近いのかは分かるようにする。 */
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
                ? TextCompat.translatable("hud.xaeronav.minutes_seconds", seconds / 60, seconds % 60)
                : TextCompat.translatable("hud.xaeronav.seconds", seconds);
    }

    private void draw(
            //? if >=1.20 {
            GuiGraphics graphics,
            //?} else {
            /*PoseStack graphics,
            *///?}
            Font font) {
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        //? if >=1.20 {
        int centerX = graphics.guiWidth() / 2;
        //?} else {
        /*int centerX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2;
        *///?}
        int boxWidth = width + PADDING_X * 2;
        int boxHeight = (lines.size() - 1) * LINE_HEIGHT + font.lineHeight + PADDING_Y * 2;
        //? if >=1.20 {
        graphics.fill(centerX - boxWidth / 2, MARGIN_TOP, centerX + boxWidth / 2, MARGIN_TOP + boxHeight,
                BACKGROUND_COLOR);
        //?} else {
        /*GuiComponent.fill(graphics, centerX - boxWidth / 2, MARGIN_TOP,
                centerX + boxWidth / 2, MARGIN_TOP + boxHeight, BACKGROUND_COLOR);
        *///?}

        int y = MARGIN_TOP + PADDING_Y;
        for (int i = 0; i < lines.size(); i++) {
            //? if >=26.1 {
            /*graphics.centeredText(font, lines.get(i), centerX, y, colors.get(i));
            *///?} else if >=1.20 {
            graphics.drawCenteredString(font, lines.get(i), centerX, y, colors.get(i));
            //?} else {
            /*GuiComponent.drawCenteredString(graphics, font, lines.get(i), centerX, y, colors.get(i));
            *///?}
            y += LINE_HEIGHT;
        }
    }
}
