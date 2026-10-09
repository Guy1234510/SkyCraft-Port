package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow @Final protected Level level;

    /** Random stroll goals reject air below their destination in the empty mirror. */
    @Inject(method = "isStableDestination", at = @At("RETURN"), cancellable = true)
    private void skycraft$stableGround(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !SkyCollision.active()) return;
        SkyPathing.Surface surface = SkyPathing.surface(this.level, pos);
        if (Double.isFinite(surface.height()) && !surface.blocked()) cir.setReturnValue(true);
    }

    /** MoveControl needs the exact surface height, rather than the integer path node. */
    @Inject(method = "getGroundY", at = @At("HEAD"), cancellable = true)
    private void skycraft$groundY(Vec3 target, CallbackInfoReturnable<Double> cir) {
        BlockPos pos = BlockPos.containing(target);
        if (!this.level.getBlockState(pos.below()).isAir()) return;
        SkyPathing.Surface surface = SkyPathing.surface(this.level, pos);
        if (Double.isFinite(surface.height()) && !surface.blocked()) cir.setReturnValue(surface.height());
    }
}
