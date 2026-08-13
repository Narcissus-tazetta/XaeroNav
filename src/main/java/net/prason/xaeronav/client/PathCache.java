package net.prason.xaeronav.client;

import java.util.function.Function;

import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * 「経路が変わったときにだけ組み直す」を1箇所にまとめたもの。
 *
 * <p>案内表示・地図の点・ワールド内描画・HUDの警告は、どれも経路から何かを導いて毎フレーム使う。
 * 導いた結果は経路が同じ限り変わらないので、フレームごとに作り直す意味はない。同じ判断を4箇所が
 * それぞれの書き方で持っていたため、新しい派生物を足すたびに「前の経路と同じか比べる」を
 * 書き起こす必要があり、比較を忘れれば毎フレーム再計算に静かに戻る。
 *
 * <p>比較は{@code ==}で行う。{@link PathResult}は探索のたびに新しく作られ、内容が同じでも
 * 別インスタンスになるので、参照の一致がそのまま「同じ経路か」の答えになる。
 *
 * <p>スレッド安全ではない。描画スレッドまたはクライアントスレッドのどちらか一方から使うこと。
 */
final class PathCache<T> {

    private PathResult source;
    private T value;

    T get(PathResult result, Function<PathResult, T> build) {
        if (source != result) {
            source = result;
            value = build.apply(result);
        }
        return value;
    }
}
