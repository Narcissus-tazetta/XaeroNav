package net.prason.xaeronav.sable;

/**
 * Sableの乗り物（物理で動く構造物）に座っている・上に立っているときの、案内に要る値。
 *
 * @param seated    座席に座っているか。立って乗っているならfalse
 * @param clearance 構造物の外接箱の下端から真下の地面までの高さ（ブロック）。地面が見つからなければ
 *                  {@link Double#POSITIVE_INFINITY}。Offroadのタイヤは外接箱の外に出るので、車輪で
 *                  接地していても1前後になる
 * @param wheelSpan 車輪で決まる幅（ブロック）。車輪の無い構造物は0
 * @param height    構造物の縦の大きさ（ブロック、切り上げ）
 */
public record SableRide(boolean seated, double clearance, int wheelSpan, int height) {

    public boolean wheeled() {
        return wheelSpan > 0;
    }
}
