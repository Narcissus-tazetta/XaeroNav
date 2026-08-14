package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 探索内部での移動の種類。{@link MovementType}が表示用の粗い分類なのに対し、こちらは
 * 到着ノードから「身体が通過したセル」と「設置したブロック」を復元できる粒度を持つ。
 *
 * <p>探索中はこの列挙値だけをノードに持たせ、{@link BlockPos}のリストは最終経路を組み立てる
 * ときにだけ生成する。探索中に作ると、展開したノードの数だけ捨てられるリストが生まれる。
 */
enum MoveKind {

    TRAVERSE(MovementType.TRAVERSE),
    DIAGONAL(MovementType.TRAVERSE),
    BRIDGE(MovementType.TRAVERSE),
    /** 足元にブロックを置いて真上へ登る（design doc §4-1のPillar）。{@link #BRIDGE}の垂直版。 */
    PILLAR(MovementType.ASCEND),
    SWIM(MovementType.SWIM),
    SWIM_UP(MovementType.SWIM),
    SWIM_DOWN(MovementType.SWIM),
    SWIM_DESCEND(MovementType.SWIM),
    CLIMB(MovementType.CLIMB),
    CLIMB_UP(MovementType.CLIMB),
    CLIMB_DOWN(MovementType.CLIMB),
    ASCEND(MovementType.ASCEND),
    DESCEND(MovementType.DESCEND),
    /** 斜め1マスで1段登る（design doc外・近距離レパートリー拡充）。カーディナル2手の分解を1手に短縮する。 */
    DIAGONAL_ASCEND(MovementType.ASCEND),
    /** 斜め1マスで1段降りる。{@link #DIAGONAL_ASCEND}と同じ狙い。 */
    DIAGONAL_DESCEND(MovementType.DESCEND),
    FALL(MovementType.DESCEND),
    FALL_TO_WATER(MovementType.SWIM),
    JUMP(MovementType.JUMP);

    private final MovementType movementType;

    MoveKind(MovementType movementType) {
        this.movementType = movementType;
    }

    MovementType movementType() {
        return movementType;
    }

    /** この移動で身体が通過する（＝掘削が必要になりうる）セル。両端の座標から復元する。 */
    List<BlockPos> bodyCells(int fromX, int fromY, int fromZ, int x, int y, int z) {
        return switch (this) {
            // 一段降りる移動は、降りる手前の2マスと降りた先の1マスを通過する
            case DESCEND, SWIM_DESCEND, DIAGONAL_DESCEND ->
                    List.of(new BlockPos(x, y + 1, z), new BlockPos(x, y + 2, z), new BlockPos(x, y, z));
            // ジャンプ中は踏み切り地点の頭上1マスも通る
            case ASCEND, DIAGONAL_ASCEND -> List.of(new BlockPos(x, y, z), new BlockPos(x, y + 1, z),
                    new BlockPos(fromX, fromY + 2, fromZ));
            // 落下は着地点から踏み切り地点の頭上までの縦一列を通り抜ける
            case FALL, FALL_TO_WATER -> column(x, y, fromY + 1, z);
            // 跳び越える隙間も身体が通る。ここが塞がれたら経路は成立しない
            case JUMP -> jumpCells(fromX, fromZ, x, y, z);
            default -> List.of(new BlockPos(x, y, z), new BlockPos(x, y + 1, z));
        };
    }

    /**
     * 踏み切り地点の次のマスから着地点までの、身体2セル分。跳躍はカーディナル方向限定なので
     * dx・dzのどちらかは必ず0で、歩数は水平距離の和で求まる。
     */
    private static List<BlockPos> jumpCells(int fromX, int fromZ, int x, int y, int z) {
        int stepX = Integer.signum(x - fromX);
        int stepZ = Integer.signum(z - fromZ);
        int steps = Math.abs(x - fromX) + Math.abs(z - fromZ);
        List<BlockPos> cells = new ArrayList<>(steps * 2);
        for (int i = 1; i <= steps; i++) {
            cells.add(new BlockPos(fromX + stepX * i, y, fromZ + stepZ * i));
            cells.add(new BlockPos(fromX + stepX * i, y + 1, fromZ + stepZ * i));
        }
        return List.copyOf(cells);
    }

    private static List<BlockPos> column(int x, int bottomY, int topY, int z) {
        List<BlockPos> cells = new ArrayList<>(topY - bottomY + 1);
        for (int y = bottomY; y <= topY; y++) {
            cells.add(new BlockPos(x, y, z));
        }
        return List.copyOf(cells);
    }

    /**
     * 移動のためにブロックを置く座標。それ以外の移動では{@code null}。
     * Bridgeは進行先の床、Pillarは踏み台にする元の足元で、どちらも到着地点の1つ下になる。
     */
    BlockPos placedBlockPos(int x, int y, int z) {
        return this == BRIDGE || this == PILLAR ? new BlockPos(x, y - 1, z) : null;
    }
}
