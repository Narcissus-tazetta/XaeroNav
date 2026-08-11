package net.prason.xaeronav.client;

import java.util.OptionalDouble;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 地形に遮られていても見える描画レイヤー。
 *
 * <p>{@code RenderType.debugQuads()}などの標準レイヤーは深度テストが有効なので、描いたものは
 * 必ず手前のブロックに隠れる。ところが掘削先のハイライトは定義上いつも壁の中にあり、そのままでは
 * 画面に一切出てこない（洞窟で「どこを掘ればいいのか分からない」状態になる）。深度テストだけを
 * 切った同等のレイヤーを用意して、隠れている部分を薄く重ねるために使う。
 *
 * <p>深度は書かない（{@code COLOR_WRITE}）。書いてしまうと、この後に描かれる半透明の地形が
 * 経路の向こう側で欠ける。
 */
final class NavRenderTypes {

    static final RenderType OCCLUDED_QUADS = RenderType.create(
            "xaeronav_occluded_quads", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    static final RenderType OCCLUDED_LINES = RenderType.create(
            "xaeronav_occluded_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private NavRenderTypes() {
    }
}
