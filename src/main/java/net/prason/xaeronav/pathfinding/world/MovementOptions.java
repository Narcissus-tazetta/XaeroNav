package net.prason.xaeronav.pathfinding.world;

/**
 * 探索に持ち込む「何をしてよいか」の一式。
 *
 * <p>設定を読むのは{@link ChunkView#capture}を呼ぶ側の1箇所だけにするためのまとめ。項目を1つ足すたびに
 * 4箇所の呼び出しへ引数を書き足していくと、どこか1つを直し忘れても<b>型が合ってしまう</b>
 * （boolean・intが並ぶので順序違いも通る）。
 *
 * @param maxLavaBridgeRunBlocks 溶岩の上に架けてよい橋の長さ。{@code maxBridgeRunBlocks}とは別に持つ。
 *                               空洞に架ける橋は外しても落ちるだけだが、溶岩の上では即死する
 * @param maxVoidBridgeRunBlocks 底の無い空虚の上に架けてよい橋の長さ。溶岩と分けて持つのは、
 *                               ジ・エンドではほぼ全ての橋がこれに当たるため
 * @param avoidRiskyJumps 底の無い空虚の上・外したら死ぬ落差の上での跳躍を避けるか。避ける設定でも、
 *                        経路が一本も引けなかったときだけ緩和の梯子が開ける
 */
public record MovementOptions(boolean diggingEnabled, boolean bridgingEnabled, boolean jumpGapEnabled,
                               boolean lavaBridgingEnabled, int maxBridgeRunBlocks, int maxLavaBridgeRunBlocks,
                               int maxVoidBridgeRunBlocks, int maxSubmergedTicks,
                               boolean fallDamageToleranceEnabled, boolean avoidRiskyJumps) {

    /**
     * 掘る・置く・跳ぶ・危ない落下のどれも許さない。地形を「いま手を加えずに通れるか」だけで
     * 見たいとき（目的地や中継地点の足場探し）に使う。
     */
    public static final MovementOptions NONE =
            new MovementOptions(false, false, false, false, 0, 0, 0, 0, false, true);

    public MovementOptions withoutDigging() {
        return new MovementOptions(false, bridgingEnabled, jumpGapEnabled, lavaBridgingEnabled,
                maxBridgeRunBlocks, maxLavaBridgeRunBlocks, maxVoidBridgeRunBlocks, maxSubmergedTicks,
                fallDamageToleranceEnabled, avoidRiskyJumps);
    }
}
