package dev.skycraft.world;

import dev.skycraft.link.SkyLink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's lakes, rivers and sea as Minecraft water: Skyrim sends the water surface over the block
 * columns around the player (see WaterGrid in the protocol), and wherever Minecraft has air below
 * that surface, entities treat it as water, so the player swims, floats, sinks slowly and drowns
 * there as in Minecraft water. Only entity physics sees it; no blocks change.
 */
public final class SkyWater {
	private record Grid(int originX, int originZ, int size, float[] surface) {
	}

	private static volatile @Nullable Grid grid;
	private record WaterCache(int world, java.util.concurrent.ConcurrentHashMap<Long, Float> surface) {}
	private static volatile WaterCache known = new WaterCache(0, new java.util.concurrent.ConcurrentHashMap<>());
	private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

	private SkyWater() {
	}

	/** Once a frame on the client: pick up Skyrim's latest grid. */
	public static void refresh() {
		SkyLink.WaterGrid read = SkyLink.readWaterGrid();
		if (read != null) {
			WaterCache cache = known;
			if (cache.world() != read.worldId) {
				grid = null;
				known = cache = new WaterCache(read.worldId, new java.util.concurrent.ConcurrentHashMap<>());
			}
			Grid previous = grid;
			if (previous != null && previous.originX() == read.originX && previous.originZ() == read.originZ
				&& java.util.Arrays.equals(previous.surface(), read.surface)) return;
			for (int z = 0; z < read.size; z++) for (int x = 0; x < read.size; x++) {
				float height = read.surface[z * read.size + x];
				long column = key(read.originX + x, read.originZ + z);
				if (Float.isFinite(height) && height > -1.0e20F) cache.surface().put(column, height);
				else cache.surface().remove(column);
			}
			grid = new Grid(read.originX, read.originZ, read.size, read.surface);
		}
	}

	public static void clear() {
		grid = null;
		known = new WaterCache(0, new java.util.concurrent.ConcurrentHashMap<>());
	}

	public static boolean active() {
		return grid != null;
	}

	/** Minecraft y of Skyrim's water surface over this column, or NaN where there is none. */
	public static double surfaceAt(int x, int z) {
		Grid g = grid;
		if (g == null) {
			return Double.NaN;
		}
		int dx = x - g.originX(), dz = z - g.originZ();
		if (dx < 0 || dz < 0 || dx >= g.size() || dz >= g.size()) {
			Float cached = known.surface().get(key(x, z));
			return cached != null ? cached : Double.NaN;
		}
		float s = g.surface()[dz * g.size() + dx];
		return s < -1.0e20F ? Double.NaN : s;
	}

	/** How much of this block (0..1) is under Skyrim's water; 0 above the surface. */
	public static float depthIn(BlockPos pos) {
		double s = surfaceAt(pos.getX(), pos.getZ());
		if (Double.isNaN(s)) {
			return 0.0F;
		}
		double h = s - pos.getY();
		return h < 0.02 ? 0.0F : (float) Math.min(1.0, h);
	}

	/** True if Skyrim water reaches up into the box of block cells (inclusive). */
	public static boolean anyIn(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (grid == null) {
			return false;
		}
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double s = surfaceAt(x, z);
				if (!Double.isNaN(s) && s > y0) {
					return true;
				}
			}
		}
		return false;
	}

	/** Skyrim water in an otherwise empty (air) Minecraft cell, as a Minecraft fluid; null if none. */
	public static @Nullable FluidState fluidAt(BlockGetter level, BlockPos pos) {
		if (depthIn(pos) <= 0.0F || !level.getBlockState(pos).isAir()) {
			return null;
		}
		var terrain = SkyCollision.shapeAt(pos);
		if (terrain != null && !terrain.isEmpty() && terrain.max(net.minecraft.core.Direction.Axis.Y) >= depthIn(pos) - 0.02F) return null;
		return Fluids.WATER.getSource(false);
	}

	/** Ray intersection with virtual water for boats and fluid-targeting items. */
	public static net.minecraft.world.phys.BlockHitResult clip(BlockGetter level,
			net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to,
			net.minecraft.world.phys.BlockHitResult original) {
		double dy = to.y - from.y;
		if (!active() || dy >= -1.0E-9) return original;
		double best = original.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 1.0
			: from.distanceTo(original.getLocation()) / Math.max(1.0E-9, from.distanceTo(to));
		int x0 = (int) Math.floor(Math.min(from.x, to.x)), x1 = (int) Math.floor(Math.max(from.x, to.x));
		int z0 = (int) Math.floor(Math.min(from.z, to.z)), z1 = (int) Math.floor(Math.max(from.z, to.z));
		for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
			double surface = surfaceAt(x, z), t = (surface - from.y) / dy;
			if (!Double.isFinite(t) || t < 0 || t >= best) continue;
			double px = from.x + (to.x - from.x) * t, pz = from.z + (to.z - from.z) * t;
			if (px < x || px >= x + 1 || pz < z || pz >= z + 1) continue;
			BlockPos pos = BlockPos.containing(px, surface - 0.001, pz);
			if (fluidAt(level, pos) == null || !level.getFluidState(pos).isEmpty()) continue;
			best = t;
			original = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(px, surface, pz),
				net.minecraft.core.Direction.UP, pos, false);
		}
		return original;
	}

	/** The exact water height in a cell only Skyrim fills (so floating matches its surface); -1 otherwise. */
	public static float substitutedHeight(BlockGetter level, BlockPos pos) {
		float depth = depthIn(pos);
		if (fluidAt(level, pos) == null) return -1.0F;
		if (depth <= 0.0F || !level.getFluidState(pos).isEmpty() || !level.getBlockState(pos).isAir()) {
			return -1.0F;
		}
		return depth;
	}
}
