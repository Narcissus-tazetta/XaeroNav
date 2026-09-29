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
//? if >=1.21.5 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.prason.xaeronav.XaeroNav;
*///?} else {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
//?}

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
    *///?} else if >=1.21.5 {
    /*static final RenderType DEBUG_QUADS = RenderType.debugQuads();
    static final RenderType LINES = RenderType.lines();

    private static final RenderPipeline OCCLUDED_QUADS_PIPELINE = withoutDepthTest(RenderPipelines.DEBUG_QUADS);
    static final RenderType OCCLUDED_QUADS = createOccludedQuads();

    private static RenderType createOccludedQuads() {
        RenderType.CompositeState state = null;
        for (java.lang.reflect.Field field : DEBUG_QUADS.getClass().getDeclaredFields()) {
            if (field.getType() == RenderType.CompositeState.class) {
                try {
                    field.setAccessible(true);
                    state = (RenderType.CompositeState) field.get(DEBUG_QUADS);
                    break;
                } catch (ReflectiveOperationException exception) {
                    throw new ExceptionInInitializerError(exception);
                }
            }
        }
        if (state == null) {
            throw new ExceptionInInitializerError("RenderType composite state not found");
        }
        for (java.lang.reflect.Method method : RenderType.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 4 && parameters[0] == String.class && parameters[1] == int.class
                    && parameters[2] == RenderPipeline.class && parameters[3] == RenderType.CompositeState.class) {
                try {
                    method.setAccessible(true);
                    return (RenderType) method.invoke(null, "xaeronav_occluded_quads", 1536,
                            OCCLUDED_QUADS_PIPELINE, state);
                } catch (ReflectiveOperationException exception) {
                    throw new ExceptionInInitializerError(exception);
                }
            }
        }
        throw new ExceptionInInitializerError("RenderType factory not found");
    }

    private static RenderPipeline withoutDepthTest(RenderPipeline source) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "pipeline/occluded_quads"))
                .withVertexShader(source.getVertexShader())
                .withFragmentShader(source.getFragmentShader())
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withPolygonMode(source.getPolygonMode())
                .withCull(source.isCull())
                .withColorWrite(source.isWriteColor(), source.isWriteAlpha())
                .withDepthWrite(false)
                .withColorLogic(source.getColorLogic())
                .withVertexFormat(source.getVertexFormat(), source.getVertexFormatMode())
                .withDepthBias(source.getDepthBiasScaleFactor(), source.getDepthBiasConstant());
        source.getBlendFunction().ifPresent(builder::withBlend);
        source.getSamplers().forEach(builder::withSampler);
        source.getUniforms().forEach(uniform -> builder.withUniform(uniform.name(), uniform.type()));
        source.getShaderDefines().flags().forEach(builder::withShaderDefine);
        source.getShaderDefines().values().forEach((name, value) -> {
            try {
                builder.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                builder.withShaderDefine(name, Float.parseFloat(value));
            }
        });
        return builder.build();
    }

    static void endOccludedBatch(MultiBufferSource.BufferSource bufferSource, RenderType type) {
        bufferSource.endBatch(type);
    }
    *///?} else {
    static final RenderType DEBUG_QUADS =
            //? if >=1.20 {
            RenderType.debugQuads();
            //?} else {
            /*RenderType.lightning();
            *///?}
    static final RenderType LINES = RenderType.lines();

    //? if >=1.20 {
    static final RenderType OCCLUDED_QUADS = RenderType.create(
            "xaeronav_occluded_quads", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    //?} else if >=1.17 {
    /*// 1.20より前のFabric APIはRenderStateShardの定数を開放しておらず、protectedのままではここから読めない。
    // サブクラスの中からなら継承したprotected定数を読めるので、それだけの内部クラスを挟む
    private static final class Shards extends RenderStateShard {
        static final ShaderStateShard POSITION_COLOR = POSITION_COLOR_SHADER;
        static final TransparencyStateShard TRANSLUCENT = TRANSLUCENT_TRANSPARENCY;
        static final CullStateShard NO_CULLING = NO_CULL;
        static final DepthTestStateShard NO_DEPTH = NO_DEPTH_TEST;
        static final WriteMaskStateShard COLOR_ONLY = COLOR_WRITE;

        private Shards() {
            super("xaeronav_shards", () -> { }, () -> { });
        }
    }

    static final RenderType OCCLUDED_QUADS = RenderType.create(
            "xaeronav_occluded_quads", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(Shards.POSITION_COLOR)
                    .setTransparencyState(Shards.TRANSLUCENT)
                    .setCullState(Shards.NO_CULLING)
                    .setDepthTestState(Shards.NO_DEPTH)
                    .setWriteMaskState(Shards.COLOR_ONLY)
                    .createCompositeState(false));
    *///?} else {
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
