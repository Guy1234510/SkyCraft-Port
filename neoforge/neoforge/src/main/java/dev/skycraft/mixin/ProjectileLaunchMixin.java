package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.link.SkyLink;
import dev.skycraft.SkyCraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProjectileLaunchMixin {
    @Unique private static int skycraft$launchLogs;

    @Inject(method = "shootFromRotation", at = @At("TAIL"))
    private void skycraft$recordLaunch(Entity shooter, float pitch, float yaw, float roll, float speed,
        float spread, CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (self instanceof AbstractArrow && shooter instanceof ServerPlayer && SkyLink.active()
            && skycraft$launchLogs++ < 16) {
            SkyCraft.LOG.info("SkyCraft: arrow launch position={} velocity={} shooterVelocity={} pitch={} yaw={}",
                self.position(), self.getDeltaMovement(), shooter.getDeltaMovement(), pitch, yaw);
        }
    }

    @WrapOperation(method = "shootFromRotation", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getKnownMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 skycraft$shooterMotion(Entity shooter, Operation<Vec3> original) {
        Vec3 motion = original.call(shooter);
        if (!SkyLink.active() || !(shooter instanceof ServerPlayer)) return motion;
        // 1.21.1 already derives movement from validated client packets; reject teleport jumps.
        return Double.isFinite(motion.lengthSqr()) && motion.lengthSqr() <= 64.0 ? motion : Vec3.ZERO;
    }
}
