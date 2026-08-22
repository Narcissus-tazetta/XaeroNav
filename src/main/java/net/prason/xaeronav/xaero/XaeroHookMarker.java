package net.prason.xaeronav.xaero;

/**
 * Xaero連携のmixinが当たったことの目印。注入先のクラス1つにつき1つのmixinがこれを実装する。
 *
 * <p>mixinが当たらなかったときは例外も警告も出ない（{@code xaeronav-xaero.mixins.json}は
 * required=false）。ユーザーには「地図に線が出ない」としか見えず、原因がXaeroの版なのか設定なのかを
 * 切り分ける手掛かりが無い。Xaeroが注入先の形を変えた新版ではこれが起きるので、
 * 「連携先のMODは読み込まれているのに注入先のクラスにこの目印が付いていない」を静かな故障の検出に使う。
 */
public interface XaeroHookMarker {
}
