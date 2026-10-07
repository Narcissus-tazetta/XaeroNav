package net.prason.xaeronav.pathfinding.world;

/**
 * いま乗っている操れる乗り物。ボート・トロッコは{@link Kind#NONE}——どちらも経路の中で乗る手として計画済みなので、
 * 乗った瞬間を経路の前提の変化として扱うと計画した乗車区間ごと捨てることになる。
 *
 * <p>鞍（ハッピーガストはハーネス）の無い乗り物も{@link Kind#NONE}。操れないので歩くのと同じ経路になるうえ、
 * 馬を慣らす間は乗る・振り落とされるが1〜2秒おきに続くので、区別するとそのたびに全部引き直す。
 *
 * @param movementSpeed 乗り物の{@code MOVEMENT_SPEED}の基礎値。一時的な修飾を含めると揺れるたびに前提が変わったことになる
 * @param jumpStrength  乗り物の{@code JUMP_STRENGTH}の基礎値
 */
public record MountState(Kind kind, double movementSpeed, double jumpStrength) {

    public static final MountState NONE = new MountState(Kind.NONE, 0.0, 0.0);

    public enum Kind {
        NONE,
        /** 馬・ロバ・ラバ・スケルトンホース・ゾンビホース */
        HORSE,
        CAMEL,
        HAPPY_GHAST,
        NAUTILUS
    }
}
