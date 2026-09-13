package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * 水中・水面の移動候補生成（泳ぎ・浮上・潜降・ボート）。{@link AStarPathfinder}の分割の一部
 * （ARCH-02）——{@link GroundMoves}のクラスJavadoc参照。
 */
final class WaterMoves {

    private final AStarPathfinder owner;

    WaterMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    /**
     * 水中を泳いで進む。足場を要求しないのが{@link GroundMoves#addTraverse}との違いで、これが無いと
     * 海は「水底まで降りて歩く」か「水面の上にブロックを置いて渡る」でしか越えられない。
     */
    void addSwim(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をTraverse側が作る。2種類のMoveKindで二重に作らない
            return;
        }
        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.SWIM_ONE_BLOCK, MoveKind.SWIM);
    }

    /**
     * 水中を斜めに泳ぐ。{@link GroundMoves#addDiagonalTraverse}は足場を要求するので水中では成立せず、
     * これが無いと泳ぎだけがカーディナル4方向に縛られる——斜めに進むのに2手（実コストの1.41倍）
     * 払うことになり、海を渡る経路が実際より高く見積もられるうえ展開ノード数も増える。
     *
     * <p>角2セルの通行可能性を求めるのは{@link GroundMoves#addDiagonalTraverse}と同じ理由
     * （体が壁の角をすり抜けないように）。
     */
    void addDiagonalSwim(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をDiagonalTraverse側が作る。2種類のMoveKindで二重に作らない
            return;
        }
        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.SWIM_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE, MoveKind.SWIM);
    }

    /** 水中を浮上する。水面まで上がってから水平に泳ぐ経路を作るために要る。 */
    void addSwimUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.water(owner.view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(from.x, y + 1, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.SWIM_UP_ONE_BLOCK, MoveKind.SWIM_UP);
    }

    /**
     * 水中を進みながら1マス浮上する。{@link #addSwimUp}が真上にしか上がれないので、これが無いと
     * 浮上が「その場で上がってから横へ」というL字になる——泳いでいる人間は目的地を向いたまま
     * 斜めに上がるので、案内としても不自然に見える。
     *
     * <p>陸の{@link GroundMoves#addAscend}と同じく、踏み切り地点の頭上（＝上がっていく途中で体が
     * 通るセル）の通行可能性を求める。掘削は許可しない（水中で掘って上がるくらいなら、開いている
     * 所まで泳いだ方が速い）。
     */
    void addSwimAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.SWIM_ASCEND_ONE_BLOCK, MoveKind.SWIM_ASCEND);
    }

    /**
     * 斜めに進みながら1マス浮上する。{@link #addSwimAscend}がカーディナル4方向にしか無いと、
     * 水面へ向かう区間だけ「真っ直ぐ進んでから上がる」か「上がってから斜めに進む」に分解され、
     * そこだけ経路が直角に折れる。
     *
     * <p>角2セルの通行可能性を求めるのは{@link #addDiagonalSwim}と同じ理由（体が壁の角を
     * すり抜けないように）。
     */
    void addDiagonalSwimAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をDiagonalAscend側が作る。2種類のMoveKindで二重に作らない
            return;
        }
        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.DIAGONAL_SWIM_ASCEND_ONE_BLOCK, MoveKind.SWIM_ASCEND);
    }

    /** 水中を潜る。水底の地形沿いに進む方が近い場合に使う。 */
    void addSwimDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.water(owner.view.cell(from.x, y, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.SWIM_DOWN_ONE_BLOCK, MoveKind.SWIM_DOWN);
    }

    /**
     * 水面をボートで進む。1マスあたりは泳ぎの半分以下。乗っている状態からしか出ないので、
     * 乗り降りの手間（{@link ActionCosts#BOAT_OVERHEAD_TICKS}）は{@link #addBoatEnter}で必ず先に払う。
     *
     * <p>水面から降りる移動は既存のTraverse/Ascendがそのまま担う——降りる手間は入口の
     * オーバーヘッドに畳み込んである。
     */
    void addBoatPaddle(PathNode from, int dx, int dz, boolean diagonal) {
        if (!from.boating) {
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        if (!owner.isBoatSurface(x, from.y, z)) {
            return;
        }
        if (diagonal && (!owner.clearWithoutDigging(x, from.y, from.z)
                || !owner.clearWithoutDigging(from.x, from.y, z))) {
            return;
        }
        double cost = ActionCosts.PADDLE_ONE_BLOCK * (diagonal ? ActionCosts.DIAGONAL_DISTANCE : 1.0);
        owner.relaxBoating(from, x, from.y, z, cost, MoveKind.BOAT_PADDLE);
    }

    /**
     * ボートを出して乗り込む。乗り降りの手間をここで1度だけ払うので、短い水路では泳いで渡る方が
     * 安いままになる（損益分岐は{@link ActionCosts#BOAT_OVERHEAD_TICKS}参照）。
     *
     * <p>岸から漕ぎ出す場合と、泳いでいる途中で出す場合の両方がある。水面は岸より1マス低いのが
     * 普通なので、同じ高さと1つ下の両方を試す。
     */
    void addBoatEnter(PathNode from, int dx, int dz) {
        if (!owner.view.boatAvailable() || from.boating) {
            return;
        }
        // 岸に立っているか、水面に浮いているか。水中で潜ったままボートは出せない
        boolean onShore = CellData.standable(owner.view.cell(from.x, from.y - 1, from.z))
                && !CellData.water(owner.view.cell(from.x, from.y, from.z));
        if (!onShore && !owner.isBoatSurface(from.x, from.y, from.z)) {
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        for (int y = from.y; y >= from.y - 1; y--) {
            if (owner.isBoatSurface(x, y, z)) {
                owner.relaxBoating(from, x, y, z,
                        ActionCosts.PADDLE_ONE_BLOCK + ActionCosts.BOAT_OVERHEAD_TICKS, MoveKind.BOAT_ENTER);
                return;
            }
        }
    }
}
