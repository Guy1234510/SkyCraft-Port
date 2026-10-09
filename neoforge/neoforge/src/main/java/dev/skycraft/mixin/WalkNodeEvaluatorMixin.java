package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A void mirror's air blocks must not make real Skyrim ground look like an endless drop. */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
	@Inject(method = "getPathTypeStatic(Lnet/minecraft/world/level/pathfinder/PathfindingContext;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;", at = @At("RETURN"), cancellable = true)
	private static void skycraft$walkOnSkyrim(PathfindingContext context, BlockPos.MutableBlockPos pos, CallbackInfoReturnable<PathType> cir) {
		if (dev.skycraft.world.SkyWater.active() && dev.skycraft.world.SkyWater.fluidAt(context.level(), pos) != null) {
			cir.setReturnValue(PathType.WATER);
			return;
		}
		if (!SkyCollision.active() || !context.getBlockState(pos).isAir() || cir.getReturnValue() != PathType.OPEN) return;
		SkyPathing.Surface surface = SkyPathing.surface(context.level(), pos);
		if (!Double.isNaN(surface.height())) {
			cir.setReturnValue(surface.blocked() ? PathType.BLOCKED
				: WalkNodeEvaluator.checkNeighbourBlocks(context, pos.getX(), pos.getY(), pos.getZ(), PathType.WALKABLE));
		}
	}

	@Inject(method = "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D", at = @At("RETURN"), cancellable = true)
	private static void skycraft$exactNavigationFloor(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
		if (!level.getBlockState(pos.below()).isAir()) return;
		SkyPathing.Surface surface = SkyPathing.surface(level, pos);
		if (!Double.isNaN(surface.height()) && !surface.blocked()) cir.setReturnValue(surface.height());
	}
}
