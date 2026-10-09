package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Exact Skyrim collision shared by client prediction and integrated-server entity movement. */
public final class SkyEntityCollider {
	private static long nextFlightLog;
    private static long nextBoatLog;

	private SkyEntityCollider() {}

	public static Vec3 collide(Entity entity, Vec3 requested) {
		AABB box = entity.getBoundingBox();
		// Missing data is not a physical surface. Startup loading is handled by SkyClient;
		// normal movement must preserve gravity instead of inventing a floor in unknown air.
		Vec3 move = requested;
		double step = entity.getStepHeight();
        // Let a moving, occupied boat beach itself on a low riverbank. Ordinary
        // solid walls and taller banks remain barriers, checked by both solvers.
        boolean beaching = entity instanceof net.minecraft.world.entity.vehicle.Boat
            && entity.isVehicle() && requested.horizontalDistanceSqr() > 1.0e-6
            && (entity.isInWater() || entity.onGround());
        if (beaching) step = Math.max(step, Math.min(0.35, box.getYsize() * 0.65));
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0), tris);
		if (tris.isEmpty()) {
			traceFlight(entity, requested, move, 0, box.expandTowards(move));
			return move;
		}
		SkyDig.DugLookup dug = SkyDig.lookup(entity.level());
		// Native cutting is asynchronous. Remove acknowledged dug volumes from every surface
		// before floor, ceiling and lateral collision, not just from the floor sample points.
		tris = SkyTriCut.withoutDug(tris, dug, box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0));
		double[] result = TriCollider.resolve(tris, (box.minX + box.maxX) * 0.5, box.minY,
			(box.minZ + box.maxZ) * 0.5, Math.max(box.getXsize(), box.getZsize()) * 0.5,
			box.getYsize(), step,
            entity.onGround() || beaching, move.x, move.y, move.z, dug,
            entity instanceof net.minecraft.world.entity.vehicle.Boat ? Math.max(box.getXsize(), box.getZsize()) * 0.5 : 0.15);
		Vec3 allowed = new Vec3(result[0], result[1], result[2]);
        if (entity instanceof net.minecraft.world.entity.vehicle.Boat && requested.horizontalDistanceSqr() < 1.0e-8) {
            // A resting hull must not be shoved sideways or repeatedly snapped
            // by adjacent terrain triangles. Retain the actual floor correction.
            allowed = new Vec3(requested.x, allowed.y, requested.z);
        }
        if (entity.level().isClientSide() && entity instanceof net.minecraft.world.entity.vehicle.Boat && entity.isVehicle()
            && System.nanoTime() >= nextBoatLog) {
            nextBoatLog = System.nanoTime() + 5_000_000_000L;
            SkyCraft.LOG.info("SkyCraft: boat collision pos={} requested={} allowed={} triangles={}",
                entity.position(), requested, allowed, tris.size());
        }
		traceFlight(entity, requested, allowed, tris.size(), box.expandTowards(move));
		return allowed;
	}

	/** Low-frequency flight evidence: distinguish missing snapshots from a solver failure. */
	private static void traceFlight(Entity entity, Vec3 requested, Vec3 allowed, int triangles, AABB swept) {
		if (!entity.level().isClientSide() || !(entity instanceof net.minecraft.world.entity.LivingEntity living) || !living.isFallFlying()) return;
		long now = System.nanoTime();
		if (now < nextFlightLog) return;
		nextFlightLog = now + 5_000_000_000L;
		long pending = Math.max(0, SkyLink.collisionHead() - SkyLink.collisionTail());
		SkyCraft.LOG.info("SkyCraft: flight collision pos={} requested={} allowed={} triangles={} covered={} pendingBytes={}",
			entity.position(), requested, allowed, triangles, SkyCollision.hasTriangleCoverage(swept), pending);
	}
}
