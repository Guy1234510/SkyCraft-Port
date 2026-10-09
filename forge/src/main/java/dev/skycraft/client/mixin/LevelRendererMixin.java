package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import dev.skycraft.client.render.WorldExporter;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skyrim draws the world. While linked, Minecraft renders nothing of its own level (no sky,
 * clouds, fog or terrain) so the overlay is just hand + HUD on a transparent background.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(
		method = "renderLevel",
		at = @At("HEAD"),
		cancellable = true
	)
	private void skycraft$skipLevel(CallbackInfo ci) {
		if (SkyClient.linked()) {
			// The skipped level normally sets up and clears this target. The Skyrim overlay
			// needs zero alpha outside the hand/HUD, rather than an opaque black world.
			Minecraft minecraft = Minecraft.getInstance();
			// Keep client lighting maintenance when world drawing is cancelled (Neo25 parity).
			if (minecraft.level != null) {
				minecraft.level.pollLightUpdates();
				minecraft.level.getChunkSource().getLightEngine().runLightUpdates();
			}
			minecraft.getMainRenderTarget().bindWrite(true);
			RenderSystem.colorMask(true, true, true, true);
			RenderSystem.depthMask(true);
			RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
			RenderSystem.clear(16640, Minecraft.ON_OSX); // color and depth
			ci.cancel();
		}
	}

	@Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
	private void skycraft$blockDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		if (oldState != newState) {
			WorldExporter.markDirtyNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
				SectionPos.blockToSectionCoord(pos.getZ()));
		}
	}

}
