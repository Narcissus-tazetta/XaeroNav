package net.prason.xaeronav.pathfinding.world;

/**
 * いま乗っている操れる乗り物。ボート・トロッコは{@link Kind#NONE}——どちらも経路の中で乗る手として計画済みなので、
 * 乗った瞬間を経路の前提の変化として扱うと計画した乗車区間ごと捨てることになる。
 *
 * <p>鞍（ハッピーガストはハーネス）の無い乗り物も{@link Kind#NONE}。操れないので歩くのと同じ経路になるうえ、
 * 馬を慣らす間は乗る・振り落とされるが1〜2秒おきに続くので、区別するとそのたびに全部引き直す。
 *
 * @param movementSpeed 乗り物の{@code MOVEMENT_SPEED}の基礎値（ハッピーガストは飛ぶ速さを決める{@code FLYING_SPEED}）。
 *                      一時的な修飾を含めると揺れるたびに前提が変わったことになる
 * @param jumpStrength  乗り物の{@code JUMP_STRENGTH}の基礎値
 * @param width         車（{@link Kind#VEHICLE}）の幅（ブロック）。足場は幅×幅の正方形。他の乗り物は0
 * @param height        車の高さ（ブロック、足場から乗り手の目まで）。他の乗り物は0
 */
public record MountState(Kind kind, double movementSpeed, double jumpStrength, int width, int height) {

    public static final MountState NONE = new MountState(Kind.NONE, 0.0, 0.0);

    public MountState(Kind kind, double movementSpeed, double jumpStrength) {
        this(kind, movementSpeed, jumpStrength, 0, 0);
    }

    /** 車輪で地面を走る乗り物（Create Aeronauticsの車）。 */
    public static MountState vehicle(int width, int height) {
        return new MountState(Kind.VEHICLE, 0.0, 0.0, width, height);
    }

    /** 地面を歩く乗り物（馬の仲間・ラクダ）。探索は2×2の足場で乗ったまま進む。 */
    public boolean walks() {
        return kind == Kind.HORSE || kind == Kind.CAMEL;
    }

    /** 水中を進む乗り物（オウムガイ）。探索は水のセルで乗ったまま進む。 */
    public boolean swims() {
        return kind == Kind.NAUTILUS;
    }

    /** 車輪で地面を走る乗り物（{@link Kind#VEHICLE}）。探索は幅×幅の足場で乗ったまま進む。 */
    public boolean drives() {
        return kind == Kind.VEHICLE;
    }

    /** 歩きの探索が乗ったままの手を持つ乗り物。ハッピーガストは空中経路が受け持つので入らない。 */
    public boolean ridden() {
        return walks() || swims() || drives();
    }

    public enum Kind {
        NONE,
        /** 馬・ロバ・ラバ・スケルトンホース・ゾンビホース */
        HORSE,
        CAMEL,
        HAPPY_GHAST,
        NAUTILUS,
        /**
         * Create Aeronautics（Sable）の構造物のうち、Offroadの車輪が付いたもの。座っていても上に立っていても同じ。
         * 車輪の無い構造物（飛行機・船）は地上では{@link #NONE}（歩きの経路）、空中ではエリトラと同じ案内になる
         */
        VEHICLE
    }
}
