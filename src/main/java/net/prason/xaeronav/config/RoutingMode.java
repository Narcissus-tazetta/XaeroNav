package net.prason.xaeronav.config;

/**
 * 経路探索の重さと質の釣り合い。どのモードでも航法グラフの窓の広さ・窓の外の推定・見直しは同じで、大局（どちらへ向かうか）は
 * 変わらない。変わるのは歩きながら航法グラフを組み直す頻度と並列度だけ——細部（数ブロックの寄り道）が組み直しの遅れぶん粗くなる。
 *
 * <p>中身の数字は{@code NavGraphGuide}にある。
 */
public enum RoutingMode {
    /** 8ブロック歩くごとに組み直す。 */
    QUALITY("gui.xaeronav.config.routing_mode.quality"),
    /** 48ブロックごと。 */
    BALANCED("gui.xaeronav.config.routing_mode.balanced"),
    /** 96ブロックごと、組み直しは1スレッド。 */
    LIGHT("gui.xaeronav.config.routing_mode.light");

    // キーを組み立てずに書くのは、langの未使用・未定義キーを探すテストがコード中の文字列で突き合わせるため
    private final String translationKey;

    RoutingMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}
