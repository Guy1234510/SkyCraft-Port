package dev.skycraft.client.mixin;

import net.minecraft.client.Minecraft;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The MC window is hidden while linked; Skyrim has the real focus, so pretend we do too. */
@Mixin(Minecraft.class)
public abstract class WindowMixin {
	@Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
	private void skycraft$focused(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.tookOver()) {
			// Focused while Skyrim is connected; if Skyrim goes away, act unfocused so MC
			// never tries to grab the (hidden) mouse.
			cir.setReturnValue(SkyClient.linked());
		}
	}

}
