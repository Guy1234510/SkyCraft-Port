package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MouseHandler runs mod drag hooks, while the linked camera's rotation still comes from Skyrim. */
@Mixin(Entity.class)
public abstract class MouseLookMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void skycraft$keepNativeLook(double x, double y, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer && SkyClient.linked()) ci.cancel();
	}
}
