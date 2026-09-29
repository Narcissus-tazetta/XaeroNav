package net.prason.xaeronav.mixin.xaero;

//? if forge && >=1.21.5 && <1.21.11 {
/*import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import net.prason.xaeronav.client.XaeroNavClient;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ForgeLevelRendererMixin {
    @Inject(method = "lambda$addMainPass$1", at = @At("TAIL"), remap = false)
    private void xaeronav$afterMainPass(
            FogParameters fog, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profiler,
            Matrix4f modelView, Matrix4f projection, ResourceHandle<?> mainTarget,
            ResourceHandle<?> translucentTarget, Frustum frustum, boolean renderOutline,
            ResourceHandle<?> entityTarget, ResourceHandle<?> outlineTarget, CallbackInfo ci) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(RenderSystem.getModelViewMatrix());
        XaeroNavClient.PATH_RENDERER.render(poseStack, camera);
    }
}
*///?}
