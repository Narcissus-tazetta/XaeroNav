package net.prason.xaeronav.sable;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.platform.ModPresence;

/**
 * Sableの乗り物に乗っているか。Create Aeronauticsの車・飛行機はSableの構造物（sub-level）で、乗り手は
 * 座席に座るか上に立って運ぶ。
 *
 * <p>このクラス自体はSableのクラスを参照しない。参照すると、Sable未導入の環境ではここを読むだけで
 * {@link NoClassDefFoundError}になる。Sableに触れるのは{@code SableBridge}だけで、読み込まれているときだけ呼ぶ。
 */
public final class SableVehicles {

    public static final String MOD_ID = "sable";
    /** 車として案内する幅の上限。これより広い車は歩きの経路にしてHUDで知らせる（足場の判定が探索の時間に収まる幅）。 */
    public static final int MAX_VEHICLE_WIDTH = 7;
    /**
     * 車の高さに足す分。構造物の外接箱は接地中でも地面から約1マス浮いていて（タイヤは箱の外）、座った乗り手の目は
     * 箱の上端より上に出る（実機: 箱の下端が地面+0.97、座った乗り手の足が地面+1.78）。
     */
    private static final int HEIGHT_MARGIN = 2;

    private static @Nullable Boolean present;

    private SableVehicles() {
    }

    public static boolean present() {
        //? if sable {
        Boolean known = present;
        if (known == null) {
            known = ModPresence.isLoaded(MOD_ID);
            present = known;
        }
        return known;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * 乗り物として探索に渡す状態。車輪が無い・幅が上限を超えるなら{@link MountState#NONE}（歩きの経路）。
     *
     * @param widthOverride 設定の車幅（0なら車輪から測った幅を使う）
     */
    public static MountState mount(SableRide ride, int widthOverride) {
        if (!ride.wheeled()) {
            return MountState.NONE;
        }
        int width = widthOverride > 0 ? widthOverride : ride.wheelSpan();
        return width > MAX_VEHICLE_WIDTH ? MountState.NONE : MountState.vehicle(width, ride.height() + HEIGHT_MARGIN);
    }

    /** 車輪から測った幅が上限を超えて、歩きの経路にしているか。設定で幅を決めていれば超えない。 */
    public static boolean tooWide(SableRide ride, int widthOverride) {
        return ride.wheeled() && widthOverride <= 0 && ride.wheelSpan() > MAX_VEHICLE_WIDTH;
    }

    /** いま乗っているSableの乗り物。乗っていない・Sableが無いなら{@code null}。メインスレッド専用。 */
    public static @Nullable SableRide ride(Player player) {
        //? if sable {
        return present() ? SableBridge.ride(player) : null;
        //?} else {
        /*return null;
        *///?}
    }
}
