package dev.skycraft.mixin;

import dev.skycraft.world.SkyWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep walking AI from choosing dry-air paths through the virtual river bed. */
@Mixin(WalkNodeEvaluator.class)
public abstract class VirtualWaterPathMixin {
    @Inject(method = "getBlockPathTypeRaw", at = @At("HEAD"), cancellable = true)
    private static void skycraft$waterPath(BlockGetter level, BlockPos pos, CallbackInfoReturnable<BlockPathTypes> cir) {
        if (SkyWater.active() && SkyWater.fluidAt(level, pos) != null)
            cir.setReturnValue(BlockPathTypes.WATER);
    }
}
