package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's vignette writes alpha across the screen and cannot be composed over Skyrim. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
	private void skycraft$skipLinkedVignette(CallbackInfo ci) {
		if (SkyClient.linked()) {
			ci.cancel();
		}
	}
}
