package dev.skycraft.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.skycraft.client.SkyClient;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire inherits the hand renderer's blend state; retain coverage in the transparent export. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectMixin {
    // Skyrim already draws the underwater world and fog. Vanilla's fullscreen
    // water quad corrupts the transparent hand/HUD target's coverage alpha.
    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void skycraft$nativeUnderwater(CallbackInfo ci) {
        if (SkyClient.linked()) ci.cancel();
    }

    @Inject(method = "renderFire", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableBlend()V", shift = At.Shift.AFTER))
    private static void skycraft$fireAlpha(CallbackInfo ci) {
        if (SkyClient.linked()) {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        }
    }
}
