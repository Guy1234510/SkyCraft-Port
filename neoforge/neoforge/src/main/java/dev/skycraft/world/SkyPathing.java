package dev.skycraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;

/** Navigation sees the same received surfaces and acknowledged holes as movement. */
public final class SkyPathing {
	private SkyPathing() {}

	public record Surface(double height, boolean blocked) {}

	/** Random ground destinations need a floor before vanilla checks stability and hazards. */
	public static BlockPos projectGround(BlockGetter level, BlockPos candidate, int verticalReach) {
		if (!SkyCollision.active() || !level.getBlockState(candidate).isAir()
			|| !level.getBlockState(candidate.below()).isAir()) return candidate;
		double x = candidate.getX() + 0.5, z = candidate.getZ() + 0.5;
		AABB query = new AABB(x - 0.35, candidate.getY() - verticalReach, z - 0.35,
			x + 0.35, candidate.getY() + verticalReach + 1.8, z + 0.35);
		List<SkyTri> triangles = new ArrayList<>();
		SkyCollision.trianglesNear(query, triangles);
		SkyDig.DugLookup dug = level instanceof CollisionGetter getter ? SkyDig.lookup(getter) : null;
		if (dug != null) triangles = SkyTriCut.withoutDug(triangles, dug, query);
		BlockPos best = candidate;
		double bestDistance = Double.POSITIVE_INFINITY;
		java.util.Set<Integer> checked = new java.util.HashSet<>();
		for (SkyTri tri : triangles) {
			if (!tri.walkable) continue;
			double height = tri.heightAt(x, z);
			double distance = Math.abs(height - candidate.getY());
			if (!Double.isFinite(height) || distance > verticalReach || distance >= bestDistance) continue;
			int nodeY = (int) Math.floor(height + 0.5);
			if (!checked.add(nodeY)) continue;
			BlockPos node = new BlockPos(candidate.getX(), nodeY, candidate.getZ());
			Surface ground = surface(level, node);
			if (Double.isFinite(ground.height()) && !ground.blocked()) {
				best = node;
				bestDistance = Math.abs(ground.height() - candidate.getY());
			}
		}
		// Callers expect a non-null candidate even when the received geometry has no floor.
		return best;
	}

	public static Surface surface(BlockGetter level, BlockPos pos) {
		if (!SkyCollision.active() || !level.getBlockState(pos).isAir()) return new Surface(Double.NaN, false);
		double x = pos.getX() + 0.5, z = pos.getZ() + 0.5;
		AABB query = new AABB(x - 0.35, pos.getY() - 1.0, z - 0.35, x + 0.35, pos.getY() + 2.5, z + 0.35);
		List<SkyTri> triangles = new ArrayList<>();
		SkyCollision.trianglesNear(query, triangles);
		SkyDig.DugLookup dug = level instanceof CollisionGetter getter ? SkyDig.lookup(getter) : null;
		if (dug != null) triangles = SkyTriCut.withoutDug(triangles, dug, query);
		double ground = Double.NaN;
		for (SkyTri tri : triangles) {
			if (!tri.walkable) continue;
			double h = tri.heightAt(x, z);
			// WalkNodeEvaluator rounds a grounded mob's feet with floor(y + 0.5).
			if (!Double.isNaN(h) && h >= pos.getY() - 0.5 - 1e-4 && h < pos.getY() + 0.5
				&& (Double.isNaN(ground) || h > ground)) ground = h;
		}
		if (Double.isNaN(ground)) return new Surface(ground, false);
		double[] closest = new double[3];
		for (SkyTri tri : triangles) {
			if (tri.stairHelper || tri.maxY <= ground + 0.05 || tri.minY >= ground + 1.8) continue;
			// Roofs and overhangs must leave body clearance, even though their block state is air.
			double h = tri.heightAt(x, z);
			if (!Double.isNaN(h) && h > ground + 0.5 && h < ground + 1.8) return new Surface(ground, true);
			if (tri.walkable) continue;
			for (double above : new double[] { 0.3, 0.9, 1.5 }) {
				SkyDig.closestPoint(tri, x, ground + above, z, closest);
				double dx = x - closest[0], dy = ground + above - closest[1], dz = z - closest[2];
				if (dx * dx + dy * dy + dz * dz < 0.09) return new Surface(ground, true);
			}
		}
		return new Surface(ground, false);
	}
}
