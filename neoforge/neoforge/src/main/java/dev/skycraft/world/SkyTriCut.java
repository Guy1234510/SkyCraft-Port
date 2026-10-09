package dev.skycraft.world;

import dev.skycraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;

/** Subtract nearby acknowledged dug cells from streamed triangles while native recutting catches up. */
public final class SkyTriCut {
	private static final double EPS = 1e-7;
	private static final double CUT_SLOP = 0.011;

	private SkyTriCut() {}

	/** A real surface fragment in this cell, including layers missed by interior point samples. */
	public static boolean intersectsCell(SkyTri tri, int x, int y, int z) {
		// A surface exactly on the bottom belongs to the solid cell below, not the air above.
		if (tri.maxY <= y + EPS || tri.minY > y + 1 + EPS || tri.maxX < x || tri.minX > x + 1
			|| tri.maxZ < z || tri.minZ > z + 1) return false;
		List<double[]> polygon = List.of(new double[] { tri.ax, tri.ay, tri.az },
			new double[] { tri.bx, tri.by, tri.bz }, new double[] { tri.cx, tri.cy, tri.cz });
		int[] cell = { x, y, z };
		for (int axis = 0; axis < 3 && polygon.size() >= 3; axis++) {
			polygon = clip(polygon, axis, cell[axis] - CUT_SLOP, true, true);
			polygon = clip(polygon, axis, cell[axis] + 1 + CUT_SLOP, false, true);
		}
		for (int i = 1; i + 1 < polygon.size(); i++) {
			double[] a = polygon.get(0), b = polygon.get(i), c = polygon.get(i + 1);
			double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
			double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			if (nx * nx + ny * ny + nz * nz > 1e-16) return true;
		}
		return false;
	}

	public static List<SkyTri> withoutDug(List<SkyTri> triangles, SkyDig.DugLookup dug, AABB query) {
		List<int[]> cells = new ArrayList<>();
		for (int x = (int) Math.floor(query.minX - CUT_SLOP); x <= (int) Math.floor(query.maxX + CUT_SLOP); x++) {
			for (int y = (int) Math.floor(query.minY - CUT_SLOP); y <= (int) Math.floor(query.maxY + CUT_SLOP); y++) {
				for (int z = (int) Math.floor(query.minZ - CUT_SLOP); z <= (int) Math.floor(query.maxZ + CUT_SLOP); z++) {
					if (dug.isDug(x, y, z)) cells.add(new int[] { x, y, z });
				}
			}
		}
		if (cells.isEmpty()) return triangles;
		List<SkyTri> result = new ArrayList<>();
		for (SkyTri tri : triangles) {
			if (!tri.diggable || tri.stairHelper) {
				result.add(tri);
				continue;
			}
			List<List<double[]>> pieces = null;
			for (int[] cell : cells) {
				if (tri.maxX < cell[0] - CUT_SLOP || tri.minX > cell[0] + 1 + CUT_SLOP
					|| tri.maxY < cell[1] - CUT_SLOP || tri.minY > cell[1] + 1 + CUT_SLOP
					|| tri.maxZ < cell[2] - CUT_SLOP || tri.minZ > cell[2] + 1 + CUT_SLOP) continue;
				if (pieces == null) pieces = new ArrayList<>(List.of(List.of(
					new double[] { tri.ax, tri.ay, tri.az }, new double[] { tri.bx, tri.by, tri.bz }, new double[] { tri.cx, tri.cy, tri.cz })));
				List<List<double[]>> kept = new ArrayList<>();
				for (List<double[]> polygon : pieces) subtract(polygon, cell, kept);
				pieces = kept;
				if (pieces.isEmpty()) break;
			}
			if (pieces == null) {
				result.add(tri);
				continue;
			}
			int flags = Proto.TRI_DIGGABLE | (tri.material << Proto.TRI_MATERIAL_SHIFT) | (tri.terrain ? Proto.TRI_TERRAIN : 0);
			for (List<double[]> polygon : pieces) {
				for (int i = 1; i + 1 < polygon.size(); i++) {
					double[] a = polygon.get(0), b = polygon.get(i), c = polygon.get(i + 1);
					double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
					double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
					double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
					if (nx * nx + ny * ny + nz * nz < 1e-16) continue;
					float[] vertices = { (float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1], (float) b[2], (float) c[0], (float) c[1], (float) c[2] };
					result.add(new SkyTri(vertices, 0, flags));
				}
			}
		}
		return result;
	}

	/** Keep disjoint outside pieces, then discard the polygon inside all six cell planes. */
	private static void subtract(List<double[]> polygon, int[] cell, List<List<double[]>> kept) {
		List<double[]> inside = polygon;
		for (int axis = 0; axis < 3 && inside.size() >= 3; axis++) {
			List<double[]> below = clip(inside, axis, cell[axis] - CUT_SLOP, false, false);
			if (below.size() >= 3) kept.add(below);
			inside = clip(inside, axis, cell[axis] - CUT_SLOP, true, true);
			List<double[]> above = clip(inside, axis, cell[axis] + 1 + CUT_SLOP, true, false);
			if (above.size() >= 3) kept.add(above);
			inside = clip(inside, axis, cell[axis] + 1 + CUT_SLOP, false, true);
		}
	}

	private static List<double[]> clip(List<double[]> polygon, int axis, double plane, boolean above, boolean boundary) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < polygon.size(); i++) {
			double[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
			double da = above ? a[axis] - plane : plane - a[axis];
			double db = above ? b[axis] - plane : plane - b[axis];
			boolean aIn = boundary ? da >= -EPS : da > EPS;
			boolean bIn = boundary ? db >= -EPS : db > EPS;
			if (aIn) out.add(a);
			if (aIn != bIn) {
				double t = Math.max(0, Math.min(1, (plane - a[axis]) / (b[axis] - a[axis])));
				out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t });
			}
		}
		return out;
	}
}
