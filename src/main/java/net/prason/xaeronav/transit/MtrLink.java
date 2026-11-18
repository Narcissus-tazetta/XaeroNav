package net.prason.xaeronav.transit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
import org.jspecify.annotations.Nullable;

/**
 * MTR（Minecraft Transit Railway）がクライアント側に持つ状態。MTRには依存せず、名前で引く。
 *
 * <p>4.1系（1.21.1〜）と4.0系（〜1.20.4）でパッケージが違うので両方を試す。どちらでも引けなければ、その機能は
 * 使えないものとして扱い、理由を一度だけログに残す。
 */
public final class MtrLink {

    public static final String MOD_ID = "mtr";

    private static final String[] PORT_CLASSES = {"org.mtr.MTRClient", "org.mtr.mod.InitClient"};
    private static final String[] RIDING_CLASSES = {"org.mtr.client.VehicleRidingMovement",
            "org.mtr.mod.client.VehicleRidingMovement"};

    private MtrLink() {
    }

    public static boolean present() {
        return Holder.PRESENT;
    }

    /**
     * MTRがこのクライアントに立てたHTTPサーバーのポート。シングルではシミュレーターの地図サーバーそのもの、
     * マルチではサーバーへ中継する口。0以下は使えない（ワールドの外、地図サーバーが無効、MTRの形が違う）。
     */
    public static int port() {
        Method method = Holder.PORT;
        if (method == null) {
            return 0;
        }
        try {
            return (Integer) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MTR getServerPort() failed", e);
        }
    }

    /** MTRの乗り物（電車・エレベーターなど）に乗っているか。乗り物はエンティティではなく、MTRがプレイヤーを直接動かす。 */
    public static boolean riding() {
        Field field = Holder.RIDING;
        if (field == null) {
            return false;
        }
        try {
            return field.getLong(null) != 0L;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("MTR ridingVehicleId is not readable", e);
        }
    }

    /** 乗車中かを見分けられるか。見分けられないと、乗っている間も歩きの経路を引き直し続ける。 */
    public static boolean canSeeRiding() {
        return Holder.RIDING != null;
    }

    /** MTRのクラスへ触るのは、MTRが読み込まれていると分かってから（{@link ModPresence}の注意を参照）。 */
    private static final class Holder {
        static final boolean PRESENT = ModPresence.isLoaded(MOD_ID);
        static final @Nullable Method PORT = PRESENT ? portMethod() : null;
        static final @Nullable Field RIDING = PRESENT ? ridingField() : null;

        private static @Nullable Method portMethod() {
            Exception last = null;
            for (String name : PORT_CLASSES) {
                try {
                    return Class.forName(name).getMethod("getServerPort");
                } catch (ReflectiveOperationException tryNext) {
                    last = tryNext;
                }
            }
            XaeroNav.LOGGER.warn("XaeroNav: this MTR version has no getServerPort(); train directions are off ({})",
                    String.valueOf(last));
            return null;
        }

        private static @Nullable Field ridingField() {
            Exception last = null;
            for (String name : RIDING_CLASSES) {
                try {
                    Field field = Class.forName(name).getDeclaredField("ridingVehicleId");
                    field.setAccessible(true);
                    return field;
                } catch (ReflectiveOperationException | SecurityException tryNext) {
                    last = tryNext;
                } catch (RuntimeException closed) {
                    // Java 9以降、モジュールの外から開けないときはInaccessibleObjectException（Java 8には無い型）
                    if (!closed.getClass().getName().endsWith("InaccessibleObjectException")) {
                        throw closed;
                    }
                    last = closed;
                }
            }
            XaeroNav.LOGGER.warn("XaeroNav: cannot read MTR's riding state; routes will keep replanning on trains ({})",
                    String.valueOf(last));
            return null;
        }
    }
}
