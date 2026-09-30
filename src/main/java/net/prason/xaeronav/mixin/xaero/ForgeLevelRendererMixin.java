package net.prason.xaeronav.mixin.xaero;

//? if forge && >=1.21.2 && <1.21.11 {
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
            //? if >=1.21.5 {
            /^FogParameters fog, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profiler,
            Matrix4f modelView, Matrix4f projection, ResourceHandle<?> mainTarget,
            ResourceHandle<?> translucentTarget, Frustum frustum, boolean renderOutline,
            ResourceHandle<?> entityTarget, ResourceHandle<?> outlineTarget, CallbackInfo ci
            ^///?} else {
            FogParameters fog, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profiler,
            Matrix4f modelView, Matrix4f projection, ResourceHandle<?> target0,
            ResourceHandle<?> target1, ResourceHandle<?> target2, ResourceHandle<?> target3,
            Frustum frustum, boolean renderOutline, ResourceHandle<?> target4, CallbackInfo ci
            //?}
    ) {
        PoseStack poseStack = new PoseStack();
        // 1.21.4ではmodelViewをRenderSystem側が描画時に掛けるので、ここで積むと二重に回って線が画面外へ出る
        //? if >=1.21.5 {
        /^poseStack.last().pose().set(RenderSystem.getModelViewMatrix());
        ^///?}
        XaeroNavClient.PATH_RENDERER.render(poseStack, camera);
    }
}
*///?}
