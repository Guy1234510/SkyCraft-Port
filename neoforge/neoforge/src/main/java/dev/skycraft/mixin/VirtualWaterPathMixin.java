package dev.skycraft.mixin;

import dev.skycraft.world.SkyWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep walking AI from choosing dry-air paths through the virtual river bed. */
@Mixin(WalkNodeEvaluator.class)
public abstract class VirtualWaterPathMixin {
    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private static void skycraft$waterPath(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        if (SkyWater.active() && SkyWater.fluidAt(level, pos) != null)
            cir.setReturnValue(PathType.WATER);
    }
}
