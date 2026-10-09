package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin extends PathNavigation {
    protected GroundPathNavigationMixin(Mob mob, Level level) { super(mob, level); }

    /** Vanilla scans an air destination down/up to the mirror's limits, losing the real goal. */
    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void skycraft$keepSurfaceDestination(BlockPos pos, int reach, CallbackInfoReturnable<Path> cir) {
        if (!SkyCollision.active() || !this.level.getBlockState(pos).isAir() || !this.level.getBlockState(pos.below()).isAir()) return;
        for (BlockPos node : new BlockPos[] {pos, pos.above()}) {
            SkyPathing.Surface surface = SkyPathing.surface(this.level, node);
            if (Double.isFinite(surface.height())) {
                cir.setReturnValue(surface.blocked() ? null : super.createPath(node, reach));
                return;
            }
        }
        // An unsupported air target stays at its requested coordinates; the evaluator can reject
        // it as a drop. Scanning through the whole void would target the dimension's ceiling.
        cir.setReturnValue(super.createPath(pos, reach));
    }
}
