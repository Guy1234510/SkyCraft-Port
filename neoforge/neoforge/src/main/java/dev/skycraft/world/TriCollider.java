package dev.skycraft.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooth collision for the local player against Skyrim's exact triangles.
 *
 * Minecraft still computes every velocity (walking, sprinting, jumping, gravity, friction); this
 * only replaces how that movement is stopped by Skyrim geometry, which vanilla can only represent
 * as axis-aligned boxes (i.e. stair-stepped slopes):
 *  - walkable ground (<= ~45 deg) is followed exactly, stepping up to Minecraft's step height;
 *  - while grounded and not jumping, the player sticks to ground going downhill;
 *  - steeper surfaces are walls: the player's cylinder slides along them;
 *  - ceilings stop upward movement.
 * The resulting movement is handed back to Minecraft, which derives onGround, fall damage,
 * sprint-stopping etc. from it exactly as it would from block collisions.
 */
public final class TriCollider {
	private static final double FLOOR_RADIUS = 0.15;   // ground is sampled under a small footprint, like a capsule's base
	private static final double SUBSTEP = 0.1;         // three-dimensional sub-steps, including fast descent
	private static final double AIR_STEP = 0.3;        // walkable surfaces this far above the feet catch you mid-air
	private static final double EPS = 1e-4;

	private static final double[][] FLOOR_SAMPLES = buildSamples();

	private TriCollider() {
	}

	private static double[][] buildSamples() {
		List<double[]> samples = new ArrayList<>();
		samples.add(new double[] { 0, 0 });
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			samples.add(new double[] { Math.cos(a) * FLOOR_RADIUS, Math.sin(a) * FLOOR_RADIUS });
		}
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 4 + i * Math.PI / 2;
			samples.add(new double[] { Math.cos(a) * FLOOR_RADIUS * 0.5, Math.sin(a) * FLOOR_RADIUS * 0.5 });
		}
		return samples.toArray(new double[0][]);
	}

	/**
	 * Resolves one tick of movement {@code (mx, my, mz)} for a player whose feet are centred at
	 * {@code (x0, y0, z0)}. Returns the allowed movement.
	 */
	public static double[] resolve(
		List<SkyTri> tris, double x0, double y0, double z0, double radius, double height, double step, boolean wasOnGround, double mx, double my, double mz
	) {
		return resolve(tris, x0, y0, z0, radius, height, step, wasOnGround, mx, my, mz, null);
	}

	public static double[] resolve(
		List<SkyTri> tris, double x0, double y0, double z0, double radius, double height, double step, boolean wasOnGround, double mx, double my, double mz,
		SkyDig.DugLookup dug
	) {
        return resolve(tris, x0, y0, z0, radius, height, step, wasOnGround, mx, my, mz, dug, FLOOR_RADIUS);
    }

    /** Boats sample support across the hull; player callers retain their capsule footprint. */
    public static double[] resolve(
        List<SkyTri> tris, double x0, double y0, double z0, double radius, double height, double step, boolean wasOnGround, double mx, double my, double mz,
        SkyDig.DugLookup dug, double supportRadius
    ) {
		if (tris.isEmpty()) {
			return new double[] { mx, my, mz };
		}

		// Follow the flight path, rather than moving horizontally at the original height and
		// dropping only at the destination. The latter skips roofs crossed during an elytra dive.
		int count = Math.max(1, (int) Math.ceil(Math.max(Math.hypot(mx, mz), Math.abs(my)) / SUBSTEP));
		if (my <= 0 && Math.abs(floor(tris, x0, y0, z0, true, EPS, dug, supportRadius) - y0) < 2 * EPS) wasOnGround = true;
		if (count == 1) return java.util.Arrays.copyOf(resolveStep(tris, x0, y0, z0, radius, height, step, wasOnGround, mx, my, mz, dug, supportRadius), 3);
		double sx = mx / count, sy = my / count, sz = mz / count;
		double x = x0, y = y0, z = z0;
		boolean changedX = false, changedY = false, changedZ = false;
		boolean grounded = wasOnGround;
		for (int i = 0; i < count; i++) {
			double[] allowed = resolveStep(tris, x, y, z, radius, height, step, grounded, sx, sy, sz, dug, supportRadius);
			changedX |= allowed[0] != sx;
			changedY |= allowed[1] != sy;
			changedZ |= allowed[2] != sz;
			x += allowed[0];
			y += allowed[1];
			z += allowed[2];
			// Downhill support can move farther DOWN than gravity requested.
			// Carry actual floor contact, rather than inferring it from displacement sign.
			grounded = sy <= 0 && allowed[3] > 0.5;
		}
		// Exact equality is Minecraft's collision signal. Summing unobstructed sub-steps can
		// introduce round-off, so preserve every unchanged input axis bit-for-bit.
		return new double[] { changedX ? x - x0 : mx, changedY ? y - y0 : my, changedZ ? z - z0 : mz };
	}

	private static double[] resolveStep(
		List<SkyTri> tris, double x0, double y0, double z0, double radius, double height, double step, boolean wasOnGround, double mx, double my, double mz,
		SkyDig.DugLookup dug, double supportRadius
	) {
		double x = x0, y = y0, z = z0;

		// 1) Horizontal, in sub-steps, sliding out of walls after each.
		double horizontal = Math.hypot(mx, mz);
		int steps = Math.max(1, (int) Math.ceil(horizontal / SUBSTEP));
		double wallFrom = wasOnGround ? step : 0.02;
		boolean hitX = false, hitZ = false;
		for (int i = 0; i < steps; i++) {
			double px = x, pz = z;
			x += mx / steps;
			z += mz / steps;
			double[] out = pushOutOfWalls(tris, x, y, z, radius, height, wallFrom, step, px, pz);
			// A blocked uphill step must stop rather than turn depenetration into a shove
			// downhill. Revert this small trial move if the wall solver exceeds its sweep.
			double rx = out[0] - px, rz = out[1] - pz;
			if (rx * mx + rz * mz < -EPS * EPS) {
				out[0] = px;
				out[1] = pz;
			}
			hitX |= out[0] != x;
			hitZ |= out[1] != z;
			x = out[0];
			z = out[1];
		}

		// 2) Vertical.
		double dy = my;
		if (dy > 0) {
			double ceiling = ceilingAbove(tris, x, y + height, z, radius * 0.8, dug);
			if (!Double.isNaN(ceiling)) {
				dy = Math.max(0.0, Math.min(dy, ceiling - (y + height)));
			}
		}
		double walkUp = wasOnGround ? step : AIR_STEP;
		double floorWalk = floor(tris, x, y, z, true, walkUp, dug, supportRadius);
		double floorAny = floor(tris, x, y, z, false, wasOnGround ? step : EPS, dug, supportRadius);
		double floor = Math.max(floorWalk, floorAny);
		double targetY = y + dy;
		double outY;
		if (targetY <= floor) {
			outY = floor - y0; // land / stand / walk up a slope or small ledge
		} else if (wasOnGround && dy <= 0 && floor > Double.NEGATIVE_INFINITY && y - floor <= Math.max(step, horizontal * 1.5)) {
			outY = floor - y0; // stick to the ground going downhill instead of hopping
		} else {
			outY = dy; // free movement (possibly shortened by a ceiling)
		}
		// Minecraft decides "did I collide?" with exact equality against what it asked for, so any
		// axis we didn't actually change must come back bit-for-bit identical (not (y0 + d) - y0).
		return new double[] { hitX ? x - x0 : mx, outY, hitZ ? z - z0 : mz,
			Double.isFinite(floor) && Math.abs(y0 + outY - floor) < 2 * EPS ? 1.0 : 0.0 };
	}

	/** Highest ground under the footprint at most {@code maxAbove} above the feet (or -inf). */
	private static double floor(List<SkyTri> tris, double x, double y, double z, boolean walkableOnly, double maxAbove, SkyDig.DugLookup dug, double supportRadius) {
		double best = Double.NEGATIVE_INFINITY;
		double limit = y + maxAbove;
		for (SkyTri t : tris) {
			if (walkableOnly && !t.walkable) {
				continue;
			}
			if (t.minY > limit || t.maxX < x - supportRadius || t.minX > x + supportRadius
				|| t.maxZ < z - supportRadius || t.minZ > z + supportRadius) {
				continue;
			}
			for (double[] s : FLOOR_SAMPLES) {
				double sampleX = x + s[0] * supportRadius / FLOOR_RADIUS;
                double sampleZ = z + s[1] * supportRadius / FLOOR_RADIUS;
                double h = t.heightAt(sampleX, sampleZ);
				if (!Double.isNaN(h) && t.diggable && dug != null
					&& dug.isDug((int) Math.floor(sampleX), (int) Math.floor(h - 0.01), (int) Math.floor(sampleZ))) continue;
				if (!Double.isNaN(h) && h <= limit && h > best) {
					best = h;
				}
			}
		}
		return best;
	}

	/** Lowest surface above the head within the footprint, or NaN. */
	private static double ceilingAbove(List<SkyTri> tris, double x, double head, double z, double r, SkyDig.DugLookup dug) {
		double best = Double.NaN;
		for (SkyTri t : tris) {
			if (t.stairHelper || t.maxY < head - 0.05 || t.maxX < x - r || t.minX > x + r
				|| t.maxZ < z - r || t.minZ > z + r) {
				continue;
			}
			for (double[] s : FLOOR_SAMPLES) {
				double h = t.heightAt(x + s[0] * r / FLOOR_RADIUS, z + s[1] * r / FLOOR_RADIUS);
				if (!Double.isNaN(h) && t.diggable && dug != null
					&& dug.isDug((int) Math.floor(x + s[0] * r / FLOOR_RADIUS), (int) Math.floor(h + 0.01), (int) Math.floor(z + s[1] * r / FLOOR_RADIUS))) continue;
				if (!Double.isNaN(h) && h >= head - 0.05 && (Double.isNaN(best) || h < best)) {
					best = h;
				}
			}
		}
		return best;
	}

	/**
	 * Pushes the player's vertical cylinder out of every triangle that intersects its body.
	 * Steep triangles count from {@code wallFrom} above the feet; walkable ones only from the
	 * step height (below that they are ground, handled by {@link #floor}).
	 */
	private static double[] pushOutOfWalls(
		List<SkyTri> tris, double x, double y, double z, double radius, double height, double wallFrom, double step, double prevX, double prevZ
	) {
		double[] poly = new double[3 * 6];
		for (int iter = 0; iter < 4; iter++) {
			double bestPen = 0, bestDx = 0, bestDz = 0;
			for (SkyTri t : tris) {
				if (t.stairHelper) {
					continue;
				}
				double lo = y + (t.walkable ? step : wallFrom);
				double hi = y + height - 0.02;
				if (t.maxY < lo || t.minY > hi || t.maxX < x - radius || t.minX > x + radius || t.maxZ < z - radius || t.minZ > z + radius) {
					continue;
				}
				int n = clipToSlab(t, lo, hi, poly);
				if (n == 0) {
					continue;
				}
				double[] closest = closestXZ(poly, n, x, z);
				double cx = closest[0], cz = closest[1];
				double ddx = x - cx, ddz = z - cz;
				double d = Math.sqrt(ddx * ddx + ddz * ddz);
				double pen;
				double dirX, dirZ;
				if (d > 1e-6) {
					pen = radius - d;
					dirX = ddx / d;
					dirZ = ddz / d;
				} else {
					// Axis is inside the wall's footprint: push back along its horizontal normal.
					double hl = Math.hypot(t.nx, t.nz);
					if (hl < 1e-6) {
						continue;
					}
					dirX = t.nx / hl;
					dirZ = t.nz / hl;
					if ((prevX - t.ax) * dirX + (prevZ - t.az) * dirZ < 0) {
						dirX = -dirX;
						dirZ = -dirZ;
					}
					pen = radius;
				}
				if (pen > bestPen) {
					bestPen = pen;
					bestDx = dirX;
					bestDz = dirZ;
				}
			}
			if (bestPen <= EPS) {
				break;
			}
			x += bestDx * (bestPen + EPS);
			z += bestDz * (bestPen + EPS);
		}
		return new double[] { x, z };
	}

	/** Sutherland-Hodgman clip of the triangle to lo <= y <= hi. Writes xyz triples, returns vertex count. */
	private static int clipToSlab(SkyTri t, double lo, double hi, double[] out) {
		double[] a = { t.ax, t.ay, t.az, t.bx, t.by, t.bz, t.cx, t.cy, t.cz };
		double[] tmp = new double[3 * 6];
		int n = clipPlane(a, 3, tmp, lo, true);
		if (n == 0) {
			return 0;
		}
		return clipPlane(tmp, n, out, hi, false);
	}

	private static int clipPlane(double[] in, int n, double[] out, double level, boolean keepAbove) {
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double ay = in[i * 3 + 1], by = in[j * 3 + 1];
			boolean aIn = keepAbove ? ay >= level : ay <= level;
			boolean bIn = keepAbove ? by >= level : by <= level;
			if (aIn) {
				out[m * 3] = in[i * 3];
				out[m * 3 + 1] = ay;
				out[m * 3 + 2] = in[i * 3 + 2];
				m++;
			}
			if (aIn != bIn) {
				double s = (level - ay) / (by - ay);
				out[m * 3] = in[i * 3] + (in[j * 3] - in[i * 3]) * s;
				out[m * 3 + 1] = level;
				out[m * 3 + 2] = in[i * 3 + 2] + (in[j * 3 + 2] - in[i * 3 + 2]) * s;
				m++;
			}
		}
		return m;
	}

	/** Closest point on the XZ projection of a convex polygon to (x, z). */
	private static double[] closestXZ(double[] poly, int n, double x, double z) {
		// Inside test (only meaningful if the projection has area).
		double area = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			area += poly[i * 3] * poly[j * 3 + 2] - poly[j * 3] * poly[i * 3 + 2];
		}
		if (Math.abs(area) > 1e-9) {
			boolean inside = true;
			for (int i = 0; i < n && inside; i++) {
				int j = (i + 1) % n;
				double cross = (poly[j * 3] - poly[i * 3]) * (z - poly[i * 3 + 2]) - (poly[j * 3 + 2] - poly[i * 3 + 2]) * (x - poly[i * 3]);
				inside = area > 0 ? cross >= -1e-12 : cross <= 1e-12;
			}
			if (inside) {
				return new double[] { x, z };
			}
		}
		double bestD = Double.MAX_VALUE, bx = poly[0], bz = poly[2];
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double x0 = poly[i * 3], z0 = poly[i * 3 + 2], x1 = poly[j * 3], z1 = poly[j * 3 + 2];
			double ex = x1 - x0, ez = z1 - z0;
			double l2 = ex * ex + ez * ez;
			double s = l2 > 1e-12 ? Math.max(0, Math.min(1, ((x - x0) * ex + (z - z0) * ez) / l2)) : 0;
			double px = x0 + ex * s, pz = z0 + ez * s;
			double d = (px - x) * (px - x) + (pz - z) * (pz - z);
			if (d < bestD) {
				bestD = d;
				bx = px;
				bz = pz;
			}
		}
		return new double[] { bx, bz };
	}

	/** Highest surface at or below {@code maxAbove} over the feet at (x, y, z), or NaN. */
	public static double groundAt(List<SkyTri> tris, double x, double y, double z, double maxAbove) {
		return groundAt(tris, x, y, z, maxAbove, null);
	}

	public static double groundAt(List<SkyTri> tris, double x, double y, double z, double maxAbove, SkyDig.DugLookup dug) {
		return groundAt(tris, x, y, z, maxAbove, dug, FLOOR_RADIUS);
    }

    public static double groundAt(List<SkyTri> tris, double x, double y, double z, double maxAbove, SkyDig.DugLookup dug, double supportRadius) {
        double f = floor(tris, x, y, z, false, maxAbove, dug, supportRadius);
		return f == Double.NEGATIVE_INFINITY ? Double.NaN : f;
	}

    /** Reject terrain through the body without treating a supporting floor as a solid voxel. */
    public static boolean canOccupy(List<SkyTri> tris, double x, double y, double z, double radius, double height, SkyDig.DugLookup dug) {
        double[] clear = pushOutOfWalls(tris, x, y, z, radius, height, 0.02, 0.02, x, z);
        if (Math.hypot(clear[0] - x, clear[1] - z) > EPS) return false;
        double floor = floor(tris, x, y, z, false, height - EPS, dug, radius);
        if (floor > y + 0.002) return false;
        double ceiling = ceilingAbove(tris, x, y + height, z, radius, dug);
        return Double.isNaN(ceiling) || ceiling >= y + height - EPS;
    }

}
