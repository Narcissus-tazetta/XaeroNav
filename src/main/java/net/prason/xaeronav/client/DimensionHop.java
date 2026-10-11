package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * 地上とネザーをまたいだときに、目的地と経由地の座標を移った先の次元へ換算したもの。ポータルの位置・行き先は扱わない。
 *
 * <p>換算した座標から元の座標を引けるように持つ。×8で戻すと丸めで最大7ブロックずれるので、元の次元へ戻ったら
 * 元の正確な座標を使う。移った先で足した経由地は対応が無いので、換算で戻す。
 */
final class DimensionHop {

    /** 換算した座標（指定そのまま。立てる高さへ寄せる前）→元の次元での座標。 */
    private final Map<BlockPos, BlockPos> originals;
    private final double backScale;
    private final boolean fromNether;

    private DimensionHop(Map<BlockPos, BlockPos> originals, double backScale, boolean fromNether) {
        this.originals = originals;
        this.backScale = backScale;
        this.fromNether = fromNether;
    }

    /** 移った先の座標の列と、それを覚えた換算。 */
    record Converted(List<BlockPos> points, DimensionHop hop) {
    }

    /**
     * 元の次元から移った先へ換算する。{@code scale}は{@code DimensionType#getTeleportationScale}、Yは移った先の
     * {@code [minY, maxY]}へ収める。
     */
    static Converted leave(List<BlockPos> points, double scale, int minY, int maxY, boolean fromNether) {
        Map<BlockPos, BlockPos> originals = new HashMap<>();
        List<BlockPos> converted = new ArrayList<>(points.size());
        for (BlockPos point : points) {
            BlockPos to = convert(point, scale, minY, maxY);
            converted.add(to);
            originals.putIfAbsent(to, point);
        }
        return new Converted(converted, new DimensionHop(originals, 1.0 / scale, fromNether));
    }

    /** 元の次元へ戻す。Yは元の次元の{@code [minY, maxY]}へ収める。 */
    List<BlockPos> back(List<BlockPos> points, int minY, int maxY) {
        List<BlockPos> back = new ArrayList<>(points.size());
        for (BlockPos point : points) {
            BlockPos original = originals.get(point);
            back.add(original != null ? original : convert(point, backScale, minY, maxY));
        }
        return back;
    }

    /** {@code point}の元の次元での座標。表示用で、Yは収めない。 */
    BlockPos originOf(BlockPos point) {
        BlockPos original = originals.get(point);
        return original != null ? original : convert(point, backScale, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** 元の次元がネザーか（でなければ地上）。 */
    boolean fromNether() {
        return fromNether;
    }

    /**
     * バニラのポータルと同じく、ブロックの中心に倍率を掛けて切り捨てる。×8では8ブロックの真ん中へ、÷8では
     * 負の座標も含めて切り捨てで同じ区画へ落ちる。Yは倍率を掛けない（バニラも掛けない）。
     */
    static BlockPos convert(BlockPos pos, double scale, int minY, int maxY) {
        return new BlockPos(Mth.floor((pos.getX() + 0.5) * scale), Mth.clamp(pos.getY(), minY, maxY),
                Mth.floor((pos.getZ() + 0.5) * scale));
    }
}
