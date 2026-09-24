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
import java.util.OptionalDouble;

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
 * 必ず手前のブロックに隠れる。ところが掘削先のハイライトは定義上いつも壁の中にあり、そのままでは
 * 画面に一切出てこない（洞窟で「どこを掘ればいいのか分からない」状態になる）。深度テストだけを
 * 切った同等のレイヤーを用意して、隠れている部分を薄く重ねるために使う。
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
    private static final RenderPipeline OCCLUDED_LINES_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "pipeline/occluded_lines"))
            .withDepthWrite(false)
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
    static final RenderType OCCLUDED_LINES = RenderType.create("xaeronav_occluded_lines",
            RenderSetup.builder(OCCLUDED_LINES_PIPELINE)
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
    // 5引数のcreate(...)はパッケージ外に公開されていない版がある（Forgeの独自ATで開放できない
    // ケースを確認済み）。7引数版はどの版・ローダーでも常にpublicなので、5引数版が中で渡している
    // 既定値(false, false)をそのまま明示して直接呼ぶ
    static final RenderType OCCLUDED_LINES = RenderType.create(
            "xaeronav_occluded_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536,
            false, false,
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
    //?} else {
    /*static final RenderType OCCLUDED_QUADS = RenderType.lightning();
    static final RenderType OCCLUDED_LINES = RenderType.lines();
    *///?}

    /**
     * 深度テストを切ってから描く。{@code NO_DEPTH_TEST}（関数"always"）は、バニラの実装では
     * 深度テストの状態に<b>触らない</b>という意味で、切ってはくれない。NeoForge/Forgeの
     * {@code AFTER_TRANSLUCENT_BLOCKS}は半透明の地形を描いた後片付けの<b>前</b>に呼ばれるので、
     * 深度テストが有効なまま残っている。切らないと水の中の線や壁の中の枠がそのまま隠れる。
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
