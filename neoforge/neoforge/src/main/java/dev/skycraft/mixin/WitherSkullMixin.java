package dev.skycraft.mixin;

import dev.skycraft.world.SkyClip;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WitherSkull.class)
public abstract class WitherSkullMixin {
    @Inject(method = "onHit", at = @At("HEAD"))
    private void skycraft$impactCenter(HitResult result, CallbackInfo ci) {
        WitherSkull self = (WitherSkull) (Object) this;
        if (!self.level().isClientSide && result instanceof SkyClip.SkyrimHitResult hit) {
            // Vanilla explodes at the pre-movement position, which can be outside a small blast's reach.
            self.setPos(hit.getLocation().add(hit.nx * 0.02, hit.ny * 0.02, hit.nz * 0.02));
        }
    }
}
