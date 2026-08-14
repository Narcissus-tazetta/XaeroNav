package net.prason.xaeronav.config;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;

/**
 * design doc §6 Phase3項目15。TOML設定ファイル（{@code config/xaeronav-client.toml}）としてクライアント側に生成される。
 * 設定GUI画面（{@link net.prason.xaeronav.client.gui.XaeroNavConfigScreen}）からも編集される。
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
    private final ModConfigSpec.BooleanValue bridgingEnabled;
    private final ModConfigSpec.BooleanValue jumpGapEnabled;
    private final ModConfigSpec.BooleanValue fallDamageToleranceEnabled;
    private final ModConfigSpec.IntValue searchHorizontalMargin;
    private final ModConfigSpec.IntValue searchVerticalMargin;
    private final ModConfigSpec.DoubleValue deviationThresholdBlocks;
    private final ModConfigSpec.DoubleValue arrivalRadiusBlocks;
    private final ModConfigSpec.IntValue groundLevelY;
    private final ModConfigSpec.IntValue recalcIntervalTicks;
    private final ModConfigSpec.IntValue maxExpandedNodes;
    private final ModConfigSpec.DoubleValue heuristicWeight;
    private final ModConfigSpec.ConfigValue<List<? extends String>> forbiddenBlocks;
    private final ModConfigSpec.BooleanValue hudEnabled;
    private final ModConfigSpec.BooleanValue straightLineEnabled;

    private XaeroNavConfig(ModConfigSpec.Builder builder) {
        builder.comment("XaeroNav 経路探索設定").push("pathfinding");

        diggingEnabled = builder
                .comment("掘削を経路に含めることを許可するか（falseなら徒歩のみで到達可能な経路だけ探索する）")
                .define("diggingEnabled", true);

        bridgingEnabled = builder
                .comment("空洞を渡る・断崖を登るためのブロック設置を経路に含めることを許可するか",
                        "trueでも、ホットバーに置けるブロックが無い場合と、水・溶岩に接する場所には設置を提示しない")
                .define("bridgingEnabled", true);

        jumpGapEnabled = builder
                .comment("隙間を飛び越える移動を経路に含めることを許可するか（最大3マスの隙間まで）",
                        "falseにすると、跳べば渡れる隙間でも迂回かブロック設置(bridgingEnabled)で越える経路になる",
                        "着地を外すと落ちるので、跳躍に自信が無い場合や落ちると危険な地形ではオフにする")
                .define("jumpGapEnabled", true);

        fallDamageToleranceEnabled = builder
                .comment("落下ダメージを受ける降下を経路に含めることを許可するか",
                        "許容するダメージは経路を計算した時点の体力の1/3まで（満タンなら3ハート＝9マスの落下まで）",
                        "水バケツを持っている場合は、着地寸前に水を置いてダメージを消す降下（MLG）も候補に入る",
                        "falseなら安全に降りられる高さ(3マス)までしか降下しない")
                .define("fallDamageToleranceEnabled", false);

        searchHorizontalMargin = builder
                .comment("探索範囲の水平方向マージン（ブロック数、design doc §4-3）")
                .defineInRange("searchHorizontalMargin", 64, 8, 256);

        searchVerticalMargin = builder
                .comment("探索範囲の垂直方向マージン（ブロック数、design doc §4-3）")
                .defineInRange("searchVerticalMargin", 32, 4, 128);

        deviationThresholdBlocks = builder
                .comment("プレイヤーが経路からこの距離(ブロック)以上離れたら再計算する（design doc §4-6）",
                        "この距離の中を歩いている限り経路は引き直さないので、大きいほど線が落ち着く",
                        "既定値は線の横2〜3マスのずれを許す値")
                .defineInRange("deviationThresholdBlocks", 4.0, 1.0, 16.0);

        arrivalRadiusBlocks = builder
                .comment("目的地からこの距離(ブロック)以内に来たら到着とみなす（水平・垂直とも）",
                        "掘っても辿り着けない目的地では、実際に辿り着けた地点を基準にする")
                .defineInRange("arrivalRadiusBlocks", 3.0, 1.0, 16.0);

        groundLevelY = builder
                .comment("この高さ(Y座標)以上で、かつ頭上が開けている場所を地上とみなす",
                        "屋根の下(空が見えない場所)から地上の目的地へ向かうとき、目的地の真下を一直線に掘るのではなく、",
                        "まず最寄りの地上（この高さ以上で空の下）へ出る経路を探してから、改めて目的地へ向かう",
                        "そのとき掘らずに行ける道を先に探すので、洞窟や坑道があればそちらを通る",
                        "空が見えている場所ではこの高さより下にいても地上として扱う（川底・谷底・海岸）",
                        "空の無い次元・天井のある次元（ネザー、ジ・エンド）では働かない",
                        "既定値60は海面の少し下")
                .defineInRange("groundLevelY", 60, -64, 320);

        recalcIntervalTicks = builder
                .comment("経路の再確認間隔（tick）。プレイヤーが動いていない間はこの間隔で経路上のブロック変化だけを調べる")
                .defineInRange("recalcIntervalTicks", 40, 20, 1200);

        maxExpandedNodes = builder
                .comment("1回の探索で展開するノード数の上限。届かなかったときに探索を打ち切る天井で、",
                        "経路が見つかった時点で探索は終わるため、上げても届く経路の計算時間は変わらない",
                        "探索はワーカースレッドで走るのでフレームレートには直接影響しない",
                        "下げると、届くはずの経路が手前で切れるようになる")
                .defineInRange("maxExpandedNodes", AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES, 1_000, 500_000);

        heuristicWeight = builder
                .comment("経路探索の「ゴールへの近さ」を重視する度合い",
                        "1.0は最短経路を保証するが、掘削や遊泳のように実際のコストが見積もりを大きく上回る場所では",
                        "探索が四方に広がり、上限が数十マス先で尽きて経路が届かなくなる",
                        "上げるほど遠くまで届くかわりに、遠回りな経路が混じりうる（海を渡る・長距離では上げると効く）")
                .defineInRange("heuristicWeight", AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, 1.0, 3.0);

        forbiddenBlocks = builder
                .comment("掘削禁止ブロックの追加リスト（例: \"minecraft:chest\"）。デフォルト禁止リストへの追加分")
                .defineListAllowEmpty("additionalForbiddenBlocks", Collections.emptyList(),
                        () -> "minecraft:stone", o -> o instanceof String);

        builder.pop();
        builder.comment("XaeroNav 表示設定").push("display");

        hudEnabled = builder
                .comment("画面上部に案内（次の曲がり角・残りの道のり・所要時間）を表示するか")
                .define("hudEnabled", true);

        straightLineEnabled = builder
                .comment("経路が分からない区間（未読み込みチャンクの先など）を目的地までの点線で示すか")
                .define("straightLineEnabled", true);

        builder.pop();
    }

    public boolean diggingEnabled() {
        return diggingEnabled.get();
    }

    public void setDiggingEnabled(boolean value) {
        diggingEnabled.set(value);
    }

    public boolean bridgingEnabled() {
        return bridgingEnabled.get();
    }

    public void setBridgingEnabled(boolean value) {
        bridgingEnabled.set(value);
    }

    public boolean jumpGapEnabled() {
        return jumpGapEnabled.get();
    }

    public void setJumpGapEnabled(boolean value) {
        jumpGapEnabled.set(value);
    }

    public boolean fallDamageToleranceEnabled() {
        return fallDamageToleranceEnabled.get();
    }

    public void setFallDamageToleranceEnabled(boolean value) {
        fallDamageToleranceEnabled.set(value);
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

    public double arrivalRadiusBlocks() {
        return arrivalRadiusBlocks.get();
    }

    public int groundLevelY() {
        return groundLevelY.get();
    }

    public int recalcIntervalTicks() {
        return recalcIntervalTicks.get();
    }

    public int maxExpandedNodes() {
        return maxExpandedNodes.get();
    }

    public double heuristicWeight() {
        return heuristicWeight.get();
    }

    public List<? extends String> additionalForbiddenBlocks() {
        return forbiddenBlocks.get();
    }

    public boolean hudEnabled() {
        return hudEnabled.get();
    }

    /**
     * ここではsaveしない（GUI・キーバインド双方から呼ばれ、GUIは複数項目を一括保存したいため）。
     * 呼び出し側が責任を持って{@link #SPEC}をsaveする。
     */
    public void setHudEnabled(boolean value) {
        hudEnabled.set(value);
    }

    public boolean straightLineEnabled() {
        return straightLineEnabled.get();
    }

    public void setStraightLineEnabled(boolean value) {
        straightLineEnabled.set(value);
    }
}
