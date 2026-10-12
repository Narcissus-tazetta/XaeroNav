package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * チャットの文から「X Y Z」の座標を探す。ゲームに依存しない部分だけを持つ。
 *
 * <p>拾うのは数字がちょうど3つ並んだものだけ。「10 20」や「1 2 3 4」のような会話の数字まで
 * リンクになると、押し間違いで目的地が消える。Yがワールドの高さの外なら座標とみなさない。
 */
public final class ChatCoordinates {

    /** ワールドの縁（±3000万）。これを超える数は座標ではない。 */
    private static final int WORLD_BORDER = 30_000_000;

    private static final String NUMBER = "(-?\\d{1,8})";
    private static final String SEPARATOR = "(?:\\s*,\\s*|\\s+)";

    private static final Pattern LABELED = Pattern.compile(
            "(?<![\\w-])[xX]\\s*[:=]?\\s*" + NUMBER + SEPARATOR
                    + "[yY]\\s*[:=]?\\s*" + NUMBER + SEPARATOR
                    + "[zZ]\\s*[:=]?\\s*" + NUMBER + "(?![\\w.])");

    // 数字の並びを丸ごと取ってから個数を数える。3つの並びだけを正規表現で狙うと、
    // 「1 2 3 4」の途中の「2 3 4」まで拾ってしまう
    private static final Pattern NUMBER_RUN = Pattern.compile(
            "(?<![\\w.\\-])-?\\d+(?:" + SEPARATOR + "-?\\d+)*(?![\\w.])");
    private static final Pattern RUN_SPLIT = Pattern.compile(SEPARATOR);

    private ChatCoordinates() {
    }

    /** {@code start}〜{@code end}（含まない）が文中の座標の範囲。 */
    public record Match(int start, int end, int x, int y, int z) {
    }

    /** 見つかった座標を文中の出現順に返す。{@code maxY}は含まない。 */
    public static List<Match> find(String text, int minY, int maxY) {
        List<Match> found = new ArrayList<>();
        Matcher labeled = LABELED.matcher(text);
        while (labeled.find()) {
            addIfCoordinate(found, labeled.start(), labeled.end(),
                    labeled.group(1), labeled.group(2), labeled.group(3), minY, maxY);
        }
        Matcher run = NUMBER_RUN.matcher(text);
        while (run.find()) {
            if (overlaps(found, run.start(), run.end())) {
                continue;
            }
            String[] numbers = RUN_SPLIT.split(run.group());
            if (numbers.length == 3) {
                addIfCoordinate(found, run.start(), run.end(), numbers[0], numbers[1], numbers[2], minY, maxY);
            }
        }
        found.sort(Comparator.comparingInt(Match::start));
        return found;
    }

    private static void addIfCoordinate(List<Match> found, int start, int end, String xs, String ys, String zs,
            int minY, int maxY) {
        if (xs.length() > 9 || ys.length() > 9 || zs.length() > 9) {
            return;
        }
        int x = Integer.parseInt(xs);
        int y = Integer.parseInt(ys);
        int z = Integer.parseInt(zs);
        if (Math.abs(x) > WORLD_BORDER || Math.abs(z) > WORLD_BORDER || y < minY || y >= maxY) {
            return;
        }
        found.add(new Match(start, end, x, y, z));
    }

    private static boolean overlaps(List<Match> found, int start, int end) {
        for (Match match : found) {
            if (start < match.end() && match.start() < end) {
                return true;
            }
        }
        return false;
    }
}
