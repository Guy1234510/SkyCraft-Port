package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.LevelLoadStatusManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The "Loading terrain" screen waits for the player's chunk section to be compiled for
 * rendering. We never render Minecraft's level while linked, so don't wait for it.
 */
@Mixin(LevelLoadStatusManager.class)
public abstract class WaitingForPlayerChunkMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
	private boolean skycraft$ready(LevelRenderer renderer, BlockPos pos, Operation<Boolean> original) {
		// Keep the initial server-packet wait; bypass only the render compilation wait.
		return SkyClient.linked() || original.call(renderer, pos);
	}
}
