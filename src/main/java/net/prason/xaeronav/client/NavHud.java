package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;

/**
 * 画面上部の案内表示。「次にどちらへ曲がるか」「残りの道のり・所要時間」を出す。
 *
 * <p>探索は展開ノード数の上限で打ち切られるので、遠い目的地では経路が途中で終わる。そのことを
 * ここで明示しないと、線が何も無い場所で切れているようにしか見えない（design doc §4-4の暫定経路）。
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

    // 溺れる区間があるかは経路が変わったときにしか変わらない。HUDは毎フレーム描かれるので、
    // 全ステップの走査を経路1本につき1度で済ませる
    private PathResult scannedResult;
    private boolean drowningAhead;

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
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
        if (PathfindingState.INSTANCE.arrived()) {
            add(Component.translatable("hud.xaeronav.arrived"), PRIMARY_COLOR);
        } else if (result == null || result.steps().isEmpty()) {
            add(PathfindingState.INSTANCE.computing()
                    ? Component.translatable("hud.xaeronav.searching")
                    : Component.translatable("hud.xaeronav.no_route"), SECONDARY_COLOR);
            add(Component.translatable("hud.xaeronav.direct_distance",
                    straightDistance(mc, PathfindingState.INSTANCE.goal())), SECONDARY_COLOR);
        } else {
            boolean climbing = PathfindingState.INSTANCE.climbingToSurface();
            if (climbing) {
                // 本来の目的地ではなく、まず地上へ出るまでの中継経路であることを示す。
                // 出さないと、なぜ目的地と違う方向へ案内されるのか分からなくなる
                add(Component.translatable("hud.xaeronav.climbing_to_surface"), SECONDARY_COLOR);
            }
            NavGuidance guidance = NavGuidance.forPath(result, mc.player.blockPosition());
            add(instruction(guidance, climbing), PRIMARY_COLOR);
            add(Component.translatable("hud.xaeronav.remaining",
                    guidance.remainingBlocks, time(guidance.remainingSeconds)), SECONDARY_COLOR);
            if (drowningAhead(result)) {
                // 線の色だけでは「息が続かない」ことまでは伝わらない。潜る前に分かる必要がある
                add(Component.translatable("hud.xaeronav.drowning"), WARNING_COLOR);
            }
            if (!guidance.complete) {
                add(Component.translatable("hud.xaeronav.incomplete"), WARNING_COLOR);
            }
        }

        draw(event.getGuiGraphics(), mc.font);
    }

    private void add(Component line, int color) {
        lines.add(line);
        colors.add(color);
    }

    private boolean drowningAhead(PathResult result) {
        if (result != scannedResult) {
            scannedResult = result;
            drowningAhead = result.steps().stream().anyMatch(step -> step.risk() == PathRisk.DROWNING);
        }
        return drowningAhead;
    }

    private static Component instruction(NavGuidance guidance, boolean climbing) {
        return switch (guidance.turn) {
            // 中継区間の終わりは地上への出口であって目的地ではない。ここで「まもなく到着」と出すと、
            // 目的地はまだ遠いのに着いたと思わせてしまう
            case ARRIVE -> Component.translatable(climbing
                    ? "hud.xaeronav.surface_ahead"
                    : "hud.xaeronav.arriving");
            case STRAIGHT -> Component.translatable("hud.xaeronav.straight");
            case LEFT -> Component.translatable("hud.xaeronav.turn_left", guidance.turnDistance);
            case RIGHT -> Component.translatable("hud.xaeronav.turn_right", guidance.turnDistance);
        };
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
