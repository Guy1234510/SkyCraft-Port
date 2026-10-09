package dev.skycraft.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Keep plot light, tint and ambient occlusion; Skyrim supplies directional face lighting. */
final class MovingBlockView implements BlockAndTintGetter {
	private final ClientLevel level;
	private final LevelLightEngine lights;
    private final java.util.Map<Long, Integer> roofs = new java.util.HashMap<>();
    private int roof(BlockPos pos) {
        long column = ((long)pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
        return roofs.computeIfAbsent(column, key -> {
            var scan = new BlockPos.MutableBlockPos();
            for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); --y) {
                scan.set(pos.getX(), y, pos.getZ());
                if (level.getBlockState(scan).getLightBlock(level, scan) >= 15) return y;
            }
            return level.getMinBuildHeight() - 1;
        });
    }

	MovingBlockView(ClientLevel level, LevelLightEngine lights) { this.level = level; this.lights = lights; }

	@Override public float getShade(Direction direction, boolean shade) { return 1.0F; }
	@Override public LevelLightEngine getLightEngine() { return this.lights; }
	@Override public int getBrightness(LightLayer layer, BlockPos pos) { int light = this.lights.getLayerListener(layer).getLightValue(pos);
        // Reserved plot columns can report exterior skylight under opaque roofs.
        // Cache roof height per mesh capture, never per rendered frame.
        return layer == LightLayer.SKY ? (roof(pos) > pos.getY() ? 0 : 15) : light; }
	@Override public int getRawBrightness(BlockPos pos, int darken) { return Math.max(getBrightness(LightLayer.BLOCK, pos), getBrightness(LightLayer.SKY, pos) - darken); }
	@Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return this.level.getBlockTint(pos, resolver); }
	@Override public BlockEntity getBlockEntity(BlockPos pos) { return this.level.getBlockEntity(pos); }
	@Override public BlockState getBlockState(BlockPos pos) { return this.level.getBlockState(pos); }
	@Override public FluidState getFluidState(BlockPos pos) { return this.level.getFluidState(pos); }
	@Override public int getHeight() { return this.level.getHeight(); }
	@Override public int getMinBuildHeight() { return this.level.getMinBuildHeight(); }
}
