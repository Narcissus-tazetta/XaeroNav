package net.prason.xaeronav.client;

//? if >=1.21.11 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.ResourceLocation;
import net.prason.xaeronav.XaeroNav;
*///?} else {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
//?}

/**
 * 地形に遮られていても見える描画レイヤー。
 *
 * <p>{@code RenderType.debugQuads()}などの標準レイヤーは深度テストが有効なので、描いたものは
 * 必ず手前のブロックに隠れる。水の中の経路（水面が深度を書く）や、地形の向こうへ続く空中経路・目的地への
 * 点線は、隠れている部分も薄く重ねたいので、深度テストだけを切った同等のレイヤーを用意する。
 *
 * <p>深度は書かない（{@code COLOR_WRITE}）。書いてしまうと、この後に描かれる半透明の地形が
 * 経路の向こう側で欠ける。
 */
final class NavRenderTypes {

    //? if >=1.21.11 {
    /*static final RenderType DEBUG_QUADS = RenderTypes.debugQuads();

    // 深度テストはRenderPipelineが持つ。標準のパイプラインから深度テストだけを外したものを作る
    // （深度を書かないのは元のdebug_quadsも同じ）
    private static final RenderPipeline OCCLUDED_QUADS_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "pipeline/occluded_quads"))
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build();

    static final RenderType OCCLUDED_QUADS = RenderType.create("xaeronav_occluded_quads",
            RenderSetup.builder(OCCLUDED_QUADS_PIPELINE).sortOnUpload().createRenderSetup());
    // 線はバニラ（RenderTypes.lines()）と違ってitem_entityではなくmainへ描く。Forgeは追加の描画パスを
    // Fabulous!の合成より後に置くので、item_entityへ描いても画面へ合成されない
    static final RenderType LINES = RenderType.create("xaeronav_lines",
            RenderSetup.builder(RenderPipelines.LINES)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    /^* 深度テストはパイプラインが切るので、ここでは描くだけ。 ^/
    static void endOccludedBatch(MultiBufferSource.BufferSource bufferSource, RenderType type) {
        bufferSource.endBatch(type);
    }
    *///?} else {
    static final RenderType DEBUG_QUADS =
            //? if >=1.17 {
            RenderType.debugQuads();
            //?} else {
            /*RenderType.lightning();
            *///?}
    static final RenderType LINES = RenderType.lines();

    //? if >=1.17 {
    static final RenderType OCCLUDED_QUADS = RenderType.create(
            "xaeronav_occluded_quads", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    //?} else {
    /*static final RenderType OCCLUDED_QUADS = RenderType.lightning();
    *///?}

    /**
     * 深度テストを切ってから描く。{@code NO_DEPTH_TEST}（関数"always"）は、バニラの実装では
     * 深度テストの状態に<b>触らない</b>という意味で、切ってはくれない。NeoForge/Forgeの
     * {@code AFTER_TRANSLUCENT_BLOCKS}は半透明の地形を描いた後片付けの<b>前</b>に呼ばれるので、
     * 深度テストが有効なまま残っている。切らないと水の中の線がそのまま隠れる。
     * 後始末は要らない——次に描くレイヤーが自分の深度テストを設定する。
     */
    static void endOccludedBatch(MultiBufferSource.BufferSource bufferSource, RenderType type) {
        RenderSystem.disableDepthTest();
        bufferSource.endBatch(type);
    }
    //?}

    private NavRenderTypes() {
    }
}
