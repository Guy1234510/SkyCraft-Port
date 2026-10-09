package dev.skycraft.mixin;

import dev.skycraft.world.SkyWater;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sprint-swimming starts in Skyrim's water too (see {@link SkyWater}). */
@Mixin(Entity.class)
public abstract class EntitySwimMixin {
	@Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
	private void skycraft$swimInSkyrimWater(CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		var pos = self.blockPosition();
		// NeoForge replaces vanilla's getFluidState invocation with canStartSwimming.
		// Override only virtual water in an otherwise empty Minecraft fluid cell.
		if (SkyWater.active() && self.level().getFluidState(pos).isEmpty()
			&& SkyWater.fluidAt(self.level(), pos) != null) {
			boolean submerged = self.getEyeY() < SkyWater.surfaceAt(pos.getX(), pos.getZ());
			self.setSwimming(self.isSprinting() && !self.isPassenger() && (self.isSwimming() || submerged));
			ci.cancel();
		}
	}
}
