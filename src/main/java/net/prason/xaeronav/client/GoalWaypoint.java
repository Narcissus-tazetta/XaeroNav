package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.xaero.XaeroWaypoints;

/**
 * 目的地をXaeroのミニマップのウェイポイントとして出す。出せた間は{@link MapPathOverlay}の自前のピンを
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

    /** いまウェイポイントを置いてある目的地。置いていなければ{@code null}。 */
    private static volatile BlockPos placedAt;

    private GoalWaypoint() {
    }

    /** Xaeroのウェイポイントで目的地を示せているか。自前のピンを出すかどうかの判断に使う。 */
    static boolean placed() {
        return placedAt != null;
    }

    /**
     * 今の目的地に合わせて置き直す。目的地が変わっていなければ何もしない。
     *
     * <p>毎tick呼ぶこと。設定を切り替えた・ワールドに入り直した場合もここで追いつく——
     * 置く場所（{@link PathfindingState#setGoal}）だけで面倒を見ると、設定を切った後も
     * 目的地に着くまでウェイポイントが残る。
     */
    static void sync(BlockPos goal) {
        BlockPos wanted = goal != null && XaeroNavConfig.INSTANCE.goalMarkerEnabled()
                && !unavailable && XaeroPresence.minimapPresent()
                ? goal : null;
        BlockPos current = placedAt;
        if (wanted == null ? current == null : wanted.equals(current)) {
            return;
        }
        try {
            if (wanted == null) {
                XaeroWaypoints.clearDestination();
                placedAt = null;
            } else {
                placedAt = XaeroWaypoints.setDestination(wanted,
                        Component.translatable("gui.xaeronav.destination_waypoint").getString()) ? wanted : null;
            }
        } catch (LinkageError incompatible) {
            unavailable = true;
            placedAt = null;
            XaeroNav.LOGGER.warn("XaeroNav: Xaeroのウェイポイントに目的地を置けないため、この連携を無効にします"
                    + "（Xaeroの版が対応範囲の外にある可能性があります）", incompatible);
        }
    }
}
