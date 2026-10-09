package dev.skycraft.client.mixin;

import dev.skycraft.client.render.SableRenderCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Plot changes can bypass ordinary client section scheduling; invalidate the persistent mesh directly. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.LevelPlot", remap = false)
public abstract class SablePlotDirtyMixin {
	@Inject(method = "onBlockChange", at = @At("HEAD"), remap = false)
	private void skycraft$plotChanged(BlockPos pos, BlockState state, CallbackInfo ci) {
		SableRenderCompat.blockChanged(pos);
	}
}
