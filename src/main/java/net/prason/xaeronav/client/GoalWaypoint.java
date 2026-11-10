package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.diorama.DioramaPresence;
//? if diorama {
import net.prason.xaeronav.diorama.DioramaWaypoints;
//?}
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.xaero.XaeroWaypoints;

/**
 * 目的地と経由地を地図MOD（Xaeroのミニマップ・Diorama）のウェイポイントとして出す。出せた間は
 * {@link MapPathOverlay}の自前のピンを引っ込める——同じ場所に2つ印が重なるだけなので。
 *
 * <p>このクラスは{@code xaero.*}も{@code dev.diorama.*}も参照しない。参照は{@link XaeroWaypoints}と
 * {@code DioramaWaypoints}に閉じ込め、ここは「導入されているか」「いつ置くか」「壊れていたら諦める」だけを見る。
 *
 * <p><b>{@link LinkageError}を捕まえるのが要点。</b>地図描画のmixinはrequired=falseで、注入先が
 * 変わった版では黙って無効になるが、こちらは相手のクラスを直接呼ぶ。相手が型や引数を変えた版では
 * 呼んだ瞬間に{@link NoSuchMethodError}等が飛び、放っておけばゲームごと落ちる。連携が1つ消えるのと
 * ゲームが落ちるのとでは被害が違うので、ここだけは捕まえて機能を下ろす（一度失敗したら以後呼ばない）。
 */
final class GoalWaypoint {

    /** 相手の版が合わずに呼び出しが失敗したか。一度失敗したら以後は触らない。 */
    private static boolean xaeroUnavailable;
    private static boolean dioramaUnavailable;

    /** いまウェイポイントを置いてある地点。今の目的地から最終目的地まで。置いていなければ空。 */
    private static volatile List<BlockPos> xaeroPlacedAt = List.of();
    private static volatile List<BlockPos> dioramaPlacedAt = List.of();

    private GoalWaypoint() {
    }

    /** 地図MODのウェイポイントで目的地を示せているか。自前のピンを出すかどうかの判断に使う。 */
    static boolean placed() {
        return !xaeroPlacedAt.isEmpty() || !dioramaPlacedAt.isEmpty();
    }

    /**
     * 今の目的地と経由地に合わせて置き直す。変わっていなければ何もしない。{@code points}は今の目的地から最終目的地まで。
     *
     * <p>毎tick呼ぶこと。設定を切り替えた・ワールドに入り直した場合もここで追いつく——
     * 置く場所（{@link PathfindingState#setGoal}）だけで面倒を見ると、設定を切った後も
     * 目的地に着くまでウェイポイントが残る。
     */
    static void sync(List<BlockPos> points) {
        List<BlockPos> wanted = !points.isEmpty() && XaeroNavConfig.INSTANCE.goalMarkerEnabled() ? points : List.of();
        syncXaero(!xaeroUnavailable && XaeroPresence.minimapPresent() ? wanted : List.of());
        syncDiorama(!dioramaUnavailable && DioramaPresence.present() ? wanted : List.of());
    }

    private static void syncXaero(List<BlockPos> wanted) {
        if (wanted.equals(xaeroPlacedAt)) {
            return;
        }
        try {
            if (wanted.isEmpty()) {
                XaeroWaypoints.clearDestination();
                xaeroPlacedAt = List.of();
            } else {
                List<XaeroWaypoints.Marker> markers = new ArrayList<>(wanted.size());
                forEachMarker(wanted, (pos, name, symbol, stop) ->
                        markers.add(new XaeroWaypoints.Marker(pos, name, symbol, stop)));
                xaeroPlacedAt = XaeroWaypoints.setDestination(markers) ? List.copyOf(wanted) : List.of();
            }
        } catch (LinkageError incompatible) {
            xaeroUnavailable = true;
            xaeroPlacedAt = List.of();
            XaeroNav.LOGGER.warn("XaeroNav: cannot place the goal as a Xaero waypoint, disabling this integration"
                    + " (the installed Xaero version may be outside the supported range)", incompatible);
        }
    }

    private static void syncDiorama(List<BlockPos> wanted) {
        //? if diorama {
        if (wanted.equals(dioramaPlacedAt)) {
            return;
        }
        try {
            if (wanted.isEmpty()) {
                DioramaWaypoints.clearDestination();
                dioramaPlacedAt = List.of();
            } else {
                List<DioramaWaypoints.Marker> markers = new ArrayList<>(wanted.size());
                forEachMarker(wanted, (pos, name, symbol, stop) ->
                        markers.add(new DioramaWaypoints.Marker(pos, name, symbol, stop)));
                dioramaPlacedAt = DioramaWaypoints.setDestination(markers) ? List.copyOf(wanted) : List.of();
            }
        } catch (LinkageError incompatible) {
            dioramaUnavailable = true;
            dioramaPlacedAt = List.of();
            XaeroNav.LOGGER.warn("XaeroNav: cannot place the goal as a Diorama waypoint, disabling this integration"
                    + " (the installed Diorama version may be outside the supported range)", incompatible);
        }
        //?}
    }

    @FunctionalInterface
    private interface MarkerSink {
        void accept(BlockPos pos, String name, String symbol, boolean stop);
    }

    /** 経由地には番号、最終目的地には「X」を振る。 */
    private static void forEachMarker(List<BlockPos> points, MarkerSink sink) {
        int last = points.size() - 1;
        for (int i = 0; i < last; i++) {
            sink.accept(points.get(i), TextCompat.translatable("gui.xaeronav.stop_waypoint", i + 1).getString(),
                    Integer.toString(i + 1), true);
        }
        sink.accept(points.get(last), TextCompat.translatable("gui.xaeronav.destination_waypoint").getString(),
                "X", false);
    }
}
