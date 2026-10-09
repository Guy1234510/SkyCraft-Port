package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Round-off in straight rocket flight must not turn the complete player pose into NaN. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerFlightRotationMixin {
    private static int skycraft$clampLogs;
    @WrapOperation(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;acos(D)D"))
    private double skycraft$finiteFlightRotation(double cosine, Operation<Double> original) {
        if (!SkyLink.active()) return original.call(cosine);
        double clamped = Math.max(-1.0, Math.min(1.0, cosine));
        if (cosine != clamped && skycraft$clampLogs++ < 6)
            SkyCraft.LOG.info("SkyCraft: prevented non-finite elytra pose (flight cosine {})", cosine);
        return original.call(clamped);
    }
}
