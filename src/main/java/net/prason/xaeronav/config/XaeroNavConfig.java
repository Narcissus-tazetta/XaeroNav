package net.prason.xaeronav.config;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;

/**
 * design doc §6 Phase3項目15。TOML設定ファイル（{@code config/xaeronav-client.toml}）としてクライアント側に生成される。
 * 現時点では専用の設定画面（GUI）は実装せず、標準のNeoForge設定ファイルとして提供する。
 */
public final class XaeroNavConfig {

    public static final XaeroNavConfig INSTANCE;
    public static final ModConfigSpec SPEC;

    static {
        Pair<XaeroNavConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(XaeroNavConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private final ModConfigSpec.BooleanValue diggingEnabled;
    private final ModConfigSpec.IntValue searchHorizontalMargin;
    private final ModConfigSpec.IntValue searchVerticalMargin;
    private final ModConfigSpec.DoubleValue deviationThresholdBlocks;
    private final ModConfigSpec.IntValue recalcIntervalTicks;
    private final ModConfigSpec.IntValue maxExpandedNodes;
    private final ModConfigSpec.ConfigValue<List<? extends String>> forbiddenBlocks;
    private final ModConfigSpec.BooleanValue hudEnabled;

    private XaeroNavConfig(ModConfigSpec.Builder builder) {
        builder.comment("XaeroNav 経路探索設定").push("pathfinding");

        diggingEnabled = builder
                .comment("掘削を経路に含めることを許可するか（falseなら徒歩のみで到達可能な経路だけ探索する）")
                .define("diggingEnabled", true);

        searchHorizontalMargin = builder
                .comment("探索範囲の水平方向マージン（ブロック数、design doc §4-3）")
                .defineInRange("searchHorizontalMargin", 64, 8, 256);

        searchVerticalMargin = builder
                .comment("探索範囲の垂直方向マージン（ブロック数、design doc §4-3）")
                .defineInRange("searchVerticalMargin", 32, 4, 128);

        deviationThresholdBlocks = builder
                .comment("プレイヤーが経路からこの距離(ブロック)以上離れたら再計算する（design doc §4-6）")
                .defineInRange("deviationThresholdBlocks", 3.0, 1.0, 16.0);

        recalcIntervalTicks = builder
                .comment("経路の再確認間隔（tick）。プレイヤーが動いていない間はこの間隔で経路上のブロック変化だけを調べる")
                .defineInRange("recalcIntervalTicks", 40, 20, 1200);

        maxExpandedNodes = builder
                .comment("1回の探索で展開するノード数の上限。大きいほど遠くまで正確な経路が出るがCPUを使う",
                        "探索はワーカースレッドで走るのでフレームレートには直接影響しない")
                .defineInRange("maxExpandedNodes", AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES, 1_000, 500_000);

        forbiddenBlocks = builder
                .comment("掘削禁止ブロックの追加リスト（例: \"minecraft:chest\"）。デフォルト禁止リストへの追加分")
                .defineListAllowEmpty("additionalForbiddenBlocks", Collections.emptyList(),
                        () -> "minecraft:stone", o -> o instanceof String);

        builder.pop();
        builder.comment("XaeroNav 表示設定").push("display");

        hudEnabled = builder
                .comment("画面上部に案内（次の曲がり角・残りの道のり・所要時間）を表示するか")
                .define("hudEnabled", true);

        builder.pop();
    }

    public boolean diggingEnabled() {
        return diggingEnabled.get();
    }

    public int searchHorizontalMargin() {
        return searchHorizontalMargin.get();
    }

    public int searchVerticalMargin() {
        return searchVerticalMargin.get();
    }

    public double deviationThresholdBlocks() {
        return deviationThresholdBlocks.get();
    }

    public int recalcIntervalTicks() {
        return recalcIntervalTicks.get();
    }

    public int maxExpandedNodes() {
        return maxExpandedNodes.get();
    }

    public List<? extends String> additionalForbiddenBlocks() {
        return forbiddenBlocks.get();
    }

    public boolean hudEnabled() {
        return hudEnabled.get();
    }
}
