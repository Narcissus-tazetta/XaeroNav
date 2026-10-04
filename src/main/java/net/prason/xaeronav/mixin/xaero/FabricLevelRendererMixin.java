package net.prason.xaeronav.mixin.xaero;

//? if fabric && >=1.21.9 && <1.21.11 {
/*import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.XaeroNavClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/^*
 * Fabric APIの{@code WorldRenderEvents.END_MAIN}と同じ位置で経路を描く。1.21.10のjarは1.21.9でも使うが、
 * 1.21.9向けのFabric APIにはWorldRenderEventsが無い。{@code method_62214}はメインパスのラムダで、
 * 1.21.9と1.21.10で形が同じ。
 ^/
@Mixin(LevelRenderer.class)
public abstract class FabricLevelRendererMixin {
    @Inject(method = "method_62214",
            at = @At(value = "INVOKE:LAST", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"))
    private void xaeronav$endMain(CallbackInfo ci, @Local PoseStack poseStack) {
        XaeroNavClient.PATH_RENDERER.render(poseStack, ClientCompat.mainCamera(Minecraft.getInstance()));
    }
}
*///?}
