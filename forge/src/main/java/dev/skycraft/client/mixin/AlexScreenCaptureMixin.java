package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.skycraft.client.SkyClient;
import dev.skycraft.client.render.FullScreenEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain alpha for fullscreen effects captured over a transparent Minecraft frame. */
@Pseudo
@Mixin(targets = "com.github.alexmodguy.alexscaves.client.ClientProxy", remap = false)
public abstract class AlexScreenCaptureMixin {
    @Inject(method = "preScreenRender", remap = false, at = @At("HEAD"))
    private void skycraft$reset(float partialTick, CallbackInfo ci) {
        FullScreenEffect.active = false;
    }

    @WrapOperation(method = "preScreenRender", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
    private void skycraft$alpha(Operation<Void> original) {
        original.call();
        if (!SkyClient.linked()) return;
        FullScreenEffect.active = true;
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }
}
