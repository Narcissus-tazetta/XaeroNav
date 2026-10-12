package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.util.GameCompat;
import org.jspecify.annotations.Nullable;

/**
 * チャットに出た座標を、押すと目的地になるリンクにする。
 *
 * <p>リンクの中身は{@code /xaeronav goto x y z}のクリックコマンドだが、押したときの処理は
 * コマンドを送らずに{@link #click}で直接行う。Shift+クリックはvanillaだとクリックコマンドを
 * 走らせない（文字をチャット欄へ入れる側に回る）ので、経由地への追加はコマンド経由では作れない。
 * 1.21.6以降はクリックコマンドに確認画面が挟まる場合もある。
 */
public final class ChatCoordinateLinks {

    private static final String COMMAND_PREFIX = "/xaeronav goto ";

    private ChatCoordinateLinks() {
    }

    private record Segment(int start, String text, Style style) {
        int end() {
            return start + text.length();
        }
    }

    /** 座標が無い、設定でoff、ワールドに居ないときは受け取ったものをそのまま返す。 */
    public static Component decorate(Component message) {
        if (!XaeroNavConfig.INSTANCE.chatCoordinateLinksEnabled()) {
            return message;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return message;
        }
        List<Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        message.visit((style, content) -> {
            segments.add(new Segment(text.length(), content, style));
            text.append(content);
            return Optional.empty();
        }, Style.EMPTY);
        List<ChatCoordinates.Match> matches = new ArrayList<>();
        for (ChatCoordinates.Match match : ChatCoordinates.find(text.toString(),
                GameCompat.minBuildHeight(level), GameCompat.maxBuildHeight(level))) {
            // 既にクリックの動作が付いた部分（Xaeroの地点共有・他modのリンク）は奪わない
            if (!overlapsClickable(segments, match)) {
                matches.add(match);
            }
        }
        if (matches.isEmpty()) {
            return message;
        }
        Component hover = XaeroNavConfig.INSTANCE.chatCoordinateShiftViaEnabled()
                ? TextCompat.translatable("gui.xaeronav.chat_link.hover_via")
                : TextCompat.translatable("gui.xaeronav.chat_link.hover");
        MutableComponent rebuilt = TextCompat.empty();
        for (Segment segment : segments) {
            int pos = segment.start();
            while (pos < segment.end()) {
                ChatCoordinates.Match inside = matchContaining(matches, pos);
                int next;
                Style style;
                if (inside != null) {
                    next = Math.min(segment.end(), inside.end());
                    style = segment.style()
                            .withClickEvent(runCommand(COMMAND_PREFIX + inside.x() + " " + inside.y() + " " + inside.z()))
                            .withHoverEvent(showText(hover))
                            .withUnderlined(true)
                            .withColor(ChatFormatting.AQUA);
                } else {
                    next = Math.min(segment.end(), nextMatchStart(matches, pos));
                    style = segment.style();
                }
                rebuilt.append(TextCompat.literal(segment.text().substring(pos - segment.start(), next - segment.start()))
                        .setStyle(style));
                pos = next;
            }
        }
        return rebuilt;
    }

    /**
     * 押されたのがこのリンクなら目的地（Shiftかつ設定onなら経由地）にしてtrue。
     * それ以外はfalseを返し、vanillaの処理に任せる。
     */
    public static boolean click(@Nullable Style style, boolean shift) {
        if (style == null) {
            return false;
        }
        String command = runCommandOf(style.getClickEvent());
        if (command == null || !command.startsWith(COMMAND_PREFIX)) {
            return false;
        }
        BlockPos pos = parsePos(command.substring(COMMAND_PREFIX.length()));
        Player player = Minecraft.getInstance().player;
        if (pos == null || player == null) {
            return false;
        }
        NavCommandSink out = chatSink(player);
        if (shift && XaeroNavConfig.INSTANCE.chatCoordinateShiftViaEnabled()) {
            XaeroNavCommands.reportStop(out, PathfindingState.INSTANCE.addStop(pos));
        } else {
            XaeroNavCommands.reportGoal(out, PathfindingState.INSTANCE.setGoal(pos));
        }
        return true;
    }

    // サーバーや他modが同じ形のクリックコマンドを送ってくることもあるので、崩れていたらvanillaへ返す
    private static @Nullable BlockPos parsePos(String args) {
        String[] parts = args.trim().split(" ");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException notOurs) {
            return null;
        }
    }

    private static boolean overlapsClickable(List<Segment> segments, ChatCoordinates.Match match) {
        for (Segment segment : segments) {
            if (segment.start() < match.end() && match.start() < segment.end()
                    && segment.style().getClickEvent() != null) {
                return true;
            }
        }
        return false;
    }

    private static ChatCoordinates.@Nullable Match matchContaining(List<ChatCoordinates.Match> matches, int pos) {
        for (ChatCoordinates.Match match : matches) {
            if (match.start() <= pos && pos < match.end()) {
                return match;
            }
        }
        return null;
    }

    private static int nextMatchStart(List<ChatCoordinates.Match> matches, int pos) {
        for (ChatCoordinates.Match match : matches) {
            if (match.start() > pos) {
                return match.start();
            }
        }
        return Integer.MAX_VALUE;
    }

    private static NavCommandSink chatSink(Player player) {
        return new NavCommandSink() {
            @Override
            public void success(Component message) {
                GameCompat.tell(player, message, false);
            }

            @Override
            public void failure(Component message) {
                GameCompat.tell(player, message.copy().withStyle(ChatFormatting.RED), false);
            }
        };
    }

    private static ClickEvent runCommand(String command) {
        //? if >=1.21.5 {
        /*return new ClickEvent.RunCommand(command);
        *///?} else {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        //?}
    }

    private static HoverEvent showText(Component text) {
        //? if >=1.21.5 {
        /*return new HoverEvent.ShowText(text);
        *///?} else {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        //?}
    }

    private static @Nullable String runCommandOf(@Nullable ClickEvent event) {
        //? if >=1.21.5 {
        /*return event instanceof ClickEvent.RunCommand run ? run.command() : null;
        *///?} else {
        return event != null && event.getAction() == ClickEvent.Action.RUN_COMMAND ? event.getValue() : null;
        //?}
    }
}
