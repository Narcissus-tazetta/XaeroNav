package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** 読み取り範囲の上限チェックと、所要時間計測のフィールドが素通しになっていないかの確認。 */
class CoarseMapWindowTest {

    @Test
    void anOversizedSpanSkipsTheReadEntirelyAndReportsZeroMillis() {
        // MAX_SPAN_CHUNKS(1024)を大きく超えるX距離。地図もXaeroも一切呼ばれないので
        // Minecraft/Xaeroのランタイムが無くても検証できる。
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(1024 * 16, 64, 0);
        CoarseMapWindow.Window window = CoarseMapWindow.read(from, to, 1);
        assertNull(window.map());
        assertEquals(0L, window.readMillis());
    }
}
