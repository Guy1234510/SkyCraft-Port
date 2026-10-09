package dev.skycraft.mixin;

import dev.skycraft.world.SkyPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.RandomPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RandomPos.class)
public abstract class RandomPosMixin {
    @Inject(method = "generateRandomPosTowardDirection(Lnet/minecraft/world/entity/PathfinderMob;ILnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
        at = @At("RETURN"), cancellable = true)
    private static void skycraft$randomGround(PathfinderMob mob, int range, RandomSource random,
        BlockPos direction, CallbackInfoReturnable<BlockPos> cir) {
        if (!(mob.getNavigation() instanceof GroundPathNavigation)) return;
        // LandRandomPos and DefaultRandomPos retain their normal restrictions, hazards and scoring.
        cir.setReturnValue(SkyPathing.projectGround(mob.level(), cir.getReturnValue(), 8));
    }
}
