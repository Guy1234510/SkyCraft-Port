package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.skycraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Local catch-up packets must not make the visual clock run ahead for seconds. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.client.ClientSableInterpolationState", remap = false)
public abstract class SableLocalClockMixin {
	@ModifyExpressionValue(method = "tick", remap = false,
		at = @At(value = "FIELD", target = "Ldev/ryanhcode/sable/network/client/ClientSableInterpolationState;serverMsFromLastUpdate:F", opcode = 180, remap = false))
	private float skycraft$localCadence(float millis) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!SkyClient.linked() || minecraft.level == null || minecraft.getSingleplayerServer() == null) return millis;
		// Several server catch-up ticks can send snapshots within 0-1 ms. Treat these
		// as ticks, not a sustained 50x send rate that the 5% estimator takes seconds to forget.
		return Math.max(millis, 50.0F);
	}
}
