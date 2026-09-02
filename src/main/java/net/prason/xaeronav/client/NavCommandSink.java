package net.prason.xaeronav.client;

import net.minecraft.network.chat.Component;

/**
 * {@code /xaeronav}の応答をチャットへ返す口。
 *
 * <p>コマンドの中身はローダーに依存しないが、応答の宛先だけは依存する
 * （NeoForgeは{@code CommandSourceStack}、Fabricは{@code FabricClientCommandSource}）。
 * その1点だけをここで受け止める。
 */
public interface NavCommandSink {

    void success(Component message);

    void failure(Component message);
}
