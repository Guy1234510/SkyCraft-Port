package dev.skycraft.mixin;

import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.SkyLink;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skyrim proxies have no physics, but remain valid targets for skulls and fireballs. */
@Mixin(AbstractHurtingProjectile.class)
public abstract class HurtingProjectileMixin extends Projectile {
    protected HurtingProjectileMixin(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    @Inject(method = "canHitEntity", at = @At("RETURN"), cancellable = true)
    private void skycraft$hitActor(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && target instanceof SkyrimActorEntity && SkyLink.active()
            && super.canHitEntity(target)) cir.setReturnValue(true);
    }
}
