package net.prason.xaeronav.client;

//? if >=26.2 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if >=26.3 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;

import java.util.Optional;
import java.util.OptionalDouble;

import net.minecraft.client.Minecraft;
//?}
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 26.2で{@code MultiBufferSource}が無くなったので、{@code getBuffer}→{@code endBatch}の流れを
 * {@link StagedVertexBuffer}の上に作り直したもの。{@code endBatch}で頂点を転送してその場で描く。
 *
 * <p>1回の{@code render}の最後に{@link #endFrame}を呼ぶこと。GPUバッファの回収はそこで進む。
 */
final class NavBuffers {

    private static final StagedVertexBuffer STAGED = new StagedVertexBuffer(() -> "XaeroNav path", 1 << 16);

    private StagedVertexBuffer.Draw currentDraw;

    static NavBuffers begin() {
        return new NavBuffers();
    }

    VertexConsumer getBuffer(RenderType type) {
        VertexSorting sorting = type.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
        currentDraw = STAGED.appendDraw(type.format(), type.primitiveTopology(), sorting);
        return STAGED.getVertexBuilder(currentDraw);
    }

    void endBatch(RenderType type) {
        STAGED.upload();
        StagedVertexBuffer.ExecuteInfo info = STAGED.getExecuteInfo(currentDraw);
        if (info != null) {
            //? if >=26.3 {
            // 描画先のレンダーパスを自分で開く必要がある（26.2までのPreparedRenderTypeは自分で開いていた）
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "XaeroNav path", target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                type.prepare().drawFromBuffer(info, pass);
            }
            //?} else {
            /*type.prepare().drawFromBuffer(info);
            *///?}
        }
        STAGED.endDraw();
        currentDraw = null;
    }

    void endFrame() {
        STAGED.endFrame();
    }
}
//?}
