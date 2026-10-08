package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.xaero.XaeroWaypoints;

/**
 * 目的地と経由地をXaeroのミニマップのウェイポイントとして出す。出せた間は{@link MapPathOverlay}の自前のピンを
 * 引っ込める——同じ場所に2つ印が重なるだけなので。
 *
 * <p>このクラスは{@code xaero.*}を参照しない。参照は{@link XaeroWaypoints}に閉じ込め、ここは
 * 「導入されているか」「いつ置くか」「壊れていたら諦める」だけを見る。
 *
 * <p><b>{@link LinkageError}を捕まえるのが要点。</b>地図描画のmixinはrequired=falseで、注入先が
 * 変わった版では黙って無効になるが、こちらはXaeroのクラスを直接呼ぶ。Xaeroが型や引数を変えた版では
 * 呼んだ瞬間に{@link NoSuchMethodError}等が飛び、放っておけばゲームごと落ちる。連携が1つ消えるのと
 * ゲームが落ちるのとでは被害が違うので、ここだけは捕まえて機能を下ろす（一度失敗したら以後呼ばない）。
 */
final class GoalWaypoint {

    /** Xaeroの版が合わずに呼び出しが失敗したか。一度失敗したら以後は触らない。 */
    private static boolean unavailable;

    /** いまウェイポイントを置いてある地点。今の目的地から最終目的地まで。置いていなければ空。 */
    private static volatile List<BlockPos> placedAt = List.of();

    private GoalWaypoint() {
    }

    /** Xaeroのウェイポイントで目的地を示せているか。自前のピンを出すかどうかの判断に使う。 */
    static boolean placed() {
        return !placedAt.isEmpty();
    }

    /**
     * 今の目的地と経由地に合わせて置き直す。変わっていなければ何もしない。{@code points}は今の目的地から最終目的地まで。
     *
     * <p>毎tick呼ぶこと。設定を切り替えた・ワールドに入り直した場合もここで追いつく——
     * 置く場所（{@link PathfindingState#setGoal}）だけで面倒を見ると、設定を切った後も
     * 目的地に着くまでウェイポイントが残る。
     */
    static void sync(List<BlockPos> points) {
        List<BlockPos> wanted = !points.isEmpty() && XaeroNavConfig.INSTANCE.goalMarkerEnabled()
                && !unavailable && XaeroPresence.minimapPresent()
                ? points : List.of();
        if (wanted.equals(placedAt)) {
            return;
        }
        try {
            if (wanted.isEmpty()) {
                XaeroWaypoints.clearDestination();
                placedAt = List.of();
            } else {
                placedAt = XaeroWaypoints.setDestination(markers(wanted)) ? List.copyOf(wanted) : List.of();
            }
        } catch (LinkageError incompatible) {
            unavailable = true;
            placedAt = List.of();
            XaeroNav.LOGGER.warn("XaeroNav: cannot place the goal as a Xaero waypoint, disabling this integration"
                    + " (the installed Xaero version may be outside the supported range)", incompatible);
        }
    }

    private static List<XaeroWaypoints.Marker> markers(List<BlockPos> points) {
        List<XaeroWaypoints.Marker> markers = new ArrayList<>(points.size());
        int last = points.size() - 1;
        for (int i = 0; i < last; i++) {
            markers.add(new XaeroWaypoints.Marker(points.get(i),
                    TextCompat.translatable("gui.xaeronav.stop_waypoint", i + 1).getString(), Integer.toString(i + 1),
                    true));
        }
        markers.add(new XaeroWaypoints.Marker(points.get(last),
                TextCompat.translatable("gui.xaeronav.destination_waypoint").getString(), "X", false));
        return markers;
    }
}
