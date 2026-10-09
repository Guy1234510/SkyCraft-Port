package dev.skycraft.client.mixin;

import dev.skycraft.world.SkyWater;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** LocalPlayer caches its eye-water state separately from Entity's fluid physics. */
@Mixin(LocalPlayer.class)
public abstract class VirtualWaterViewMixin {
	@Inject(method = "isUnderWater", at = @At("RETURN"), cancellable = true)
	private void skycraft$virtualSubmersion(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || !SkyWater.active()) return;
		var player = (LocalPlayer) (Object) this;
		BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		if (player.level().getFluidState(eye).isEmpty() && SkyWater.fluidAt(player.level(), eye) != null
			&& player.getEyeY() < SkyWater.surfaceAt(eye.getX(), eye.getZ())) cir.setReturnValue(true);
	}
}
