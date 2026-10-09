package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyDigClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dug block breaking reveals what's around it (SkyDigClient). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelDigMixin {
	// Observe the level before alternative renderers cancel their own section scheduling.
	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void skycraft$sectionDirty(int sx, int sy, int sz, CallbackInfo ci) {
		if (!dev.skycraft.client.SkyClient.linked()) return;
		for (int x = sx - 1; x <= sx + 1; x++)
			for (int y = sy - 1; y <= sy + 1; y++)
				for (int z = sz - 1; z <= sz + 1; z++)
					dev.skycraft.client.render.WorldExporter.markDirty(x, y, z);
	}

	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void skycraft$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		SkyDigClient.blockChanged((ClientLevel) (Object) this, pos, oldState, newState);
	}
	@Inject(method = "onChunkLoaded", at = @At("HEAD"))
	private void skycraft$chunkLoaded(net.minecraft.world.level.ChunkPos chunkPos, CallbackInfo ci) {
		var level = (ClientLevel) (Object) this;
		if (level == null) return;
		for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
			dev.skycraft.client.render.WorldExporter.markDirty(chunkPos.x, sectionY, chunkPos.z);
		}
	}
}
