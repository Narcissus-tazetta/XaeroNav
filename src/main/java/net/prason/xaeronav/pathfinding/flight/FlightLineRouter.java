package net.prason.xaeronav.pathfinding.flight;

import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 滑空中に出す「目的地までの点線」を、間にある山や丘を避ける形に曲げる。
 *
 * <p>徒歩のA*とは目的がまるで違う。これは<b>辿るための経路ではなく、どちらへ機首を向ければいいかを
 * 示す線</b>なので、最短性も到達保証も要らない。求めるのは「見て自然で、地形を突き抜けていないこと」
 * だけ——そのぶん探索は1つの曲がり点を試すだけに留め、失敗したら素の直線へ落とす。
 *
 * <p>曲がり点は始点と終点の中点を、進行方向に直交する向きへずらした1点。ずらす向きは上・左右・
 * 斜め上の5方向で、半径を小さい方から広げながら全方向を試し、最初に地形を貫かなくなったものを採る。
 * <b>V字の余分な距離は{@code 2*sqrt((L/2)^2 + r^2) - L}で、ずらす向きに依らず半径rだけで決まる</b>。
 * つまり半径の小さい順に見ていけば、最初に見つかったものが最も安い——「幅の狭い峰なら横に、
 * 幅の広い山地なら上に」が優先順位を決め打ちせずに地形の側から決まる。
 */
public final class FlightLineRouter {

    /**
     * 曲がり点を探す半径の下限・上限と、1段ごとの倍率（ブロック）。等差ではなく等比にするのは、
     * 数マスの丘から100マス級の山地まで同じ手数で届かせるため。倍率1.5なら8マスから128マスまで
     * 8段で済み、最小半径を最大1.5倍だけ超過する（線の見た目には影響しない粗さ）。
     */
    private static final double BEND_MIN_RADIUS = 8.0;

    private static final double BEND_MAX_RADIUS = 128.0;

    private static final double BEND_RADIUS_GROWTH = 1.5;

    /**
     * 探索が触りうる範囲。曲がり点は最大{@link #BEND_MAX_RADIUS}だけ横へ振れるので、
     * 呼び出し側が用意する{@link SearchBounds}はこれだけの水平マージンを要る（狭いと横へ振った
     * 先が範囲外＝データ無しになり、避けられるはずの山を避けられなくなる）。
     */
    public static final int HORIZONTAL_MARGIN_BLOCKS = (int) BEND_MAX_RADIUS + 16;

    /** 垂直マージン。飛行高度は出発点・目的地のYではなく途中の山の高さで決まるので水平より厚く取る。 */
    public static final int VERTICAL_MARGIN_BLOCKS = 192;

    private final CellSource view;

    /**
     * 水を障害物として扱わないか。潜水中の追尾線（{@code SwimNavState}）用——水没して進むときは
     * 水は通り道であって避けるものではなく、避けたいのは海底の張り出しや洞窟の壁だけ。
     */
    private final boolean waterPassable;

    public FlightLineRouter(CellSource view) {
        this(view, false);
    }

    public FlightLineRouter(CellSource view, boolean waterPassable) {
        this.view = view;
        this.waterPassable = waterPassable;
    }

    /**
     * 始点から終点までの点線を組む。地形を貫かないなら2点、避ける必要があれば曲がり点を挟んだ3点。
     * どう曲げても避けられなければ2点のまま返す（見た目が理想的でなくても、行き先を示すという
     * 本来の役目は果たせる。ここで線ごと消す方が困る）。
     */
    public List<Vec3> findGuideLine(Vec3 start, Vec3 goal) {
        if (!intersectsTerrain(start, goal)) {
            return List.of(start, goal);
        }

        double dx = goal.x - start.x;
        double dz = goal.z - start.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-4) {
            // 目的地が真上か真下。横へずらす向きが定まらないうえ、そもそも曲げても意味が無い
            return List.of(start, goal);
        }
        // 進行方向に直交する水平ベクトル（単位長）
        double perpX = -dz / horizontal;
        double perpZ = dx / horizontal;
        Vec3 middle = start.add(goal).scale(0.5);

        for (double radius = BEND_MIN_RADIUS; radius <= BEND_MAX_RADIUS; radius *= BEND_RADIUS_GROWTH) {
            for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
                Vec3 bend = bendPoint(middle, perpX, perpZ, radius, direction);
                if (!intersectsTerrain(start, bend) && !intersectsTerrain(bend, goal)) {
                    return List.of(start, bend, goal);
                }
            }
        }
        return List.of(start, goal);
    }

    /**
     * 左・右・左斜め上・右斜め上・上の順。真下へ曲げても地形は避けられないので持たない。
     *
     * <p>同じ半径ならどの向きも余分距離は同じ（半径だけで決まる）ため、複数方向が同時に地形を
     * 避けられる際どい間合いでは並び順がそのままタイブレークになる。水平成分の大きい向きから
     * 試すことで、際どい場面では「上へ抜ける」より「横へ逸れる」を優先する。
     */
    private static final int DIRECTION_COUNT = 5;

    private static final double DIAGONAL = Math.sqrt(0.5);

    /**
     * 中点を{@code direction}の向きへ{@code radius}だけずらした曲がり点。Yはワールドの上限で
     * 頭打ちにする（超えた高さの点を返しても、そこは必ず範囲外＝データ無しになる）。
     */
    private Vec3 bendPoint(Vec3 middle, double perpX, double perpZ, double radius, int direction) {
        double lateral = switch (direction) {
            case 0 -> 1.0;
            case 1 -> -1.0;
            case 2 -> DIAGONAL;
            case 3 -> -DIAGONAL;
            default -> 0.0;
        };
        double vertical = switch (direction) {
            case 0, 1 -> 0.0;
            case 2, 3 -> DIAGONAL;
            default -> 1.0;
        };
        SearchBounds bounds = view.bounds();
        return new Vec3(
                middle.x + perpX * lateral * radius,
                Mth.clamp(middle.y + vertical * radius, bounds.minY(), bounds.maxY()),
                middle.z + perpZ * lateral * radius);
    }

    /**
     * 線分が地形を貫いているか。走査は{@link VoxelRay}が持つ。
     */
    private boolean intersectsTerrain(Vec3 a, Vec3 b) {
        return !VoxelRay.traverse(a, b, (x, y, z) -> !isSolid(x, y, z));
    }

    /**
     * 範囲外・未読み込みチャンク（{@code ABSENT}）は障害物として扱わない。目的地は描画距離の
     * 遥か先にあるのが普通で、そこを壁とみなすと線は必ず「貫いている」判定になり、どう曲げても
     * 直らないまま毎回全方向を試すだけになる。見えている地形だけを避け、見えていない先は
     * 素通りさせるのがこの線の役目に合う。
     */
    private boolean isSolid(int x, int y, int z) {
        long cell = view.cell(x, y, z);
        if (!CellData.present(cell) || CellData.passableEmpty(cell)) {
            return false;
        }
        return !(waterPassable && CellData.water(cell));
    }
}
