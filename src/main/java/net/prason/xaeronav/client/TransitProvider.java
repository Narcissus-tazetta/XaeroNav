package net.prason.xaeronav.client;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.transit.TransitOutcome;
import org.jspecify.annotations.Nullable;

/** {@link TransitGuide}へ電車の乗り換え案内を渡すMOD（MTRなど）1つぶん。 */
interface TransitProvider {

    /** ログに出す名前。 */
    String name();

    /** このクライアントで使えるか（MODが読み込まれていて、必要なクラスが引けた）。 */
    boolean present();

    /** このMODの乗り物に乗っているか。 */
    boolean riding(Player player);

    /**
     * {@code from}から{@code to}への案内を問い合わせる。投げられたら{@code null}を返し、結果は後で{@code result}へ
     * （どのスレッドからでもよい）。今は問い合わせられないなら、その理由（英語）を返して{@code result}は呼ばない。
     */
    @Nullable String ask(Minecraft mc, BlockPos from, BlockPos to, Consumer<TransitOutcome> result);

    /** 乗る場所の周りが読み込まれていて、{@link #boardingSpot}で床を探せるか。 */
    boolean spotLoaded(Level level, BlockPos board);

    /** 案内が教える乗る位置（{@link net.prason.xaeronav.transit.Itinerary.Ride#board}）から、実際に立って待つ所。 */
    BlockPos boardingSpot(Level level, BlockPos board);

    /** 返事が読めなかったときにチャットへ出す文の翻訳キー。 */
    String unavailableMessageKey();
}
