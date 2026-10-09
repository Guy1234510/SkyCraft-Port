package dev.skycraft.client.render;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Optional Sable plot capture: persistent section geometry with frame-interpolated transforms. */
public final class SableRenderCompat {
	private static boolean resolved, available, warned;
	private static Method container, subLevels, getPlot, renderPose, removed, bounds;
	private static Method loadedChunks, holderChunk, bakeMatrix, toAabb, plotLights;
	private static ClientLevel currentLevel;
	private static SkyAtlas currentAtlas;
	private static long nextId = 1, frame;
	private static int meshLogs;
	private static final Map<Long, Entry> SECTIONS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final LinkedHashSet<Long> PENDING = new LinkedHashSet<>();
	private static final Map<Object, Matrix4d> FRAME_POSES = new java.util.IdentityHashMap<>();
	private static final ByteBuffer POSE = ByteBuffer.allocateDirect(136).order(ByteOrder.LITTLE_ENDIAN);
	private static final ByteBuffer REMOVE = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);

	private static final class Entry {
		final long id = nextId++;
		Object ship;
		LevelChunk chunk;
		net.minecraft.world.level.lighting.LevelLightEngine lights;
		int sy;
		long seen;
		final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
		long capturedRevision = -1;
		long nextLightCheck;
		boolean uploaded, poseSent;
		AvatarExporter mesh;
		final Matrix4d matrix = new Matrix4d();
		final Matrix4d world = new Matrix4d();
		final double[] values = new double[16];
	}

    private static boolean pointResolved;
    private static Object pointHelper;
    private static Method pointContaining;

    static Vec3 worldPosition(Vec3 point, float partialTick) {
        resolve();
        if (!available) return point;
        try {
            if (!pointResolved) {
                pointResolved = true;
                var field = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER");
                pointHelper = field.get(null);
                pointContaining = field.getType().getMethod("getContainingClient", net.minecraft.core.Position.class);
            }
            if (pointContaining == null) return point;
            Object ship = pointContaining.invoke(pointHelper, point);
            if (ship == null) return point;
            Matrix4d matrix = new Matrix4d();
            bakeMatrix.invoke(renderPose.invoke(ship, partialTick), matrix);
            var result = matrix.transformPosition(new org.joml.Vector3d(point.x, point.y, point.z));
            return new Vec3(result.x, result.y, result.z);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't transform block crack", error); }
            return point;
        }
    }

	private SableRenderCompat() {}

    private static boolean arrowResolved, arrowWarned;
    private static Object arrowHelper;
    private static Method plotPosition, containingClient;
    private static Class<?> stickExtension;
    private static int arrowLogs;
    public record ArrowPose(Vec3 position, float yaw, float pitch, boolean attached) {}
    public static ArrowPose arrowPose(net.minecraft.world.entity.projectile.AbstractArrow arrow, Vec3 position, float yaw, float pitch, float partialTick) {
        resolve();
        ArrowPose fallback = new ArrowPose(position, yaw, pitch, false);
        if (!available) return fallback;
        if (!arrowResolved) {
            arrowResolved = true;
            try {
                stickExtension = Class.forName("dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension");
                plotPosition = stickExtension.getMethod("sable$getPlotPosition");
                var field = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER");
                arrowHelper = field.get(null);
                containingClient = field.getType().getMethod("getContainingClient", net.minecraft.core.Position.class);
            } catch (ReflectiveOperationException | LinkageError error) {
                SkyCraft.LOG.warn("SkyCraft: Sable arrow attachment API unavailable", error);
            }
        }
        if (containingClient == null || !stickExtension.isInstance(arrow)) return fallback;
        try {
            Vec3 local = (Vec3) plotPosition.invoke(arrow);
            // Sable marks retained projectiles as actually inside the plot. Their
            // position itself is local and EntityStickExtension's position is null.
            // Tracking entities instead have a world position and a separate local one.
            if (local == null) local = arrow.position();
            Object ship = containingClient.invoke(arrowHelper, local);
            if (ship == null) return fallback;
            Matrix4d matrix = new Matrix4d();
            bakeMatrix.invoke(renderPose.invoke(ship, partialTick), matrix);
            var p = matrix.transformPosition(new org.joml.Vector3d(local.x, local.y, local.z));
            double y = Math.toRadians(yaw), t = Math.toRadians(pitch);
            var direction = matrix.transformDirection(new org.joml.Vector3d(Math.sin(y) * Math.cos(t), Math.sin(t), Math.cos(y) * Math.cos(t))).normalize();
            if (arrowLogs++ < 6) SkyCraft.LOG.info("SkyCraft: attached arrow {} plot {} -> world {} {} {}", arrow.getId(), local, p.x, p.y, p.z);
            return new ArrowPose(new Vec3(p.x, p.y, p.z), (float) Math.toDegrees(Math.atan2(direction.x, direction.z)),
                (float) Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))), true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!arrowWarned) { arrowWarned = true; SkyCraft.LOG.warn("SkyCraft: couldn't render attached arrow", error); }
            return fallback;
        }
    }



    private static boolean supportResolved, supportWarned;
    private static Object supportHelper;
    private static Method trackingSupport;
    private static Class<?> clientSupport;

    /** Detect support only. Render transforms must never become authoritative player positions. */
    public static boolean isOnMovingSupport(net.minecraft.world.entity.Entity player) {
        resolve();
        if (!available) return false;
        if (!supportResolved) {
            supportResolved = true;
            try {
                var field = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER");
                supportHelper = field.get(null);
                trackingSupport = field.getType().getMethod("getTrackingOrVehicleSubLevel", net.minecraft.world.entity.Entity.class);
                clientSupport = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            } catch (ReflectiveOperationException | LinkageError error) {
                trackingSupport = null;
                SkyCraft.LOG.warn("SkyCraft: Sable support detection API unavailable", error);
            }
        }
        if (trackingSupport == null) return false;
        try {
            Object ship = trackingSupport.invoke(supportHelper, player);
            return clientSupport.isInstance(ship) && !Boolean.TRUE.equals(removed.invoke(ship));
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!supportWarned) { supportWarned = true; SkyCraft.LOG.warn("SkyCraft: couldn't detect player support", error); }
            return false;
        }
    }

	private static void resolve() {
		if (resolved) return;
		resolved = true;
		try {
			Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
			Class<?> sub = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
			Class<?> plot = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
			Class<?> holder = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");
			Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
			Class<?> box = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
			container = containers.getMethod("getContainer", ClientLevel.class);
			subLevels = containers.getMethod("getAllSubLevels");
			getPlot = sub.getMethod("getPlot");
			renderPose = sub.getMethod("renderPose", float.class);
			removed = sub.getMethod("isRemoved");
			bounds = sub.getMethod("boundingBox");
			loadedChunks = plot.getMethod("getLoadedChunks");
			plotLights = plot.getMethod("getLightEngine");
			holderChunk = holder.getMethod("getChunk");
			bakeMatrix = pose.getMethod("bakeIntoMatrix", Matrix4d.class);
			toAabb = box.getMethod("toMojang");
			available = true;
			SkyCraft.LOG.info("SkyCraft: optional Sable moving-structure capture ready");
		} catch (ClassNotFoundException ignored) {
			// No dependency in ordinary modpacks.
		} catch (ReflectiveOperationException | LinkageError error) {
			SkyCraft.LOG.warn("SkyCraft: Sable rendering API is unavailable", error);
		}
	}

	static void reset() {
		SECTIONS.clear();
		PENDING.clear();
		FRAME_POSES.clear();
		currentLevel = null;
		currentAtlas = null;
	}

	/** Called from normal client dirty notifications, including remote plot coordinates. */
	public static void markDirty(int sx, int sy, int sz) {
		Entry entry = SECTIONS.get(SectionPos.asLong(sx, sy, sz));
		if (entry != null) entry.revision.incrementAndGet();
	}

	public static void blockChanged(net.minecraft.core.BlockPos pos) {
		for (int x = (pos.getX() - 1) >> 4; x <= (pos.getX() + 1) >> 4; x++)
			for (int y = (pos.getY() - 1) >> 4; y <= (pos.getY() + 1) >> 4; y++)
				for (int z = (pos.getZ() - 1) >> 4; z <= (pos.getZ() + 1) >> 4; z++) markDirty(x, y, z);
	}

	/** Send existing GPU meshes' motion before ordinary chunk/texture uploads fill the ring. */
	static void publishMotion(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		if (!available || minecraft.level != currentLevel || atlas != currentAtlas) return;
		try {
			for (Entry entry : SECTIONS.values()) {
				entry.poseSent = false;
				Matrix4d world = FRAME_POSES.get(entry.ship);
				if (world == null) {
					if (FRAME_POSES.containsKey(entry.ship)) continue;
					if (Boolean.TRUE.equals(removed.invoke(entry.ship))) { FRAME_POSES.put(entry.ship, null); continue; }
					world = entry.world;
					bakeMatrix.invoke(renderPose.invoke(entry.ship, partialTick), world);
					FRAME_POSES.put(entry.ship, world);
				}
				entry.matrix.set(world).translate(entry.chunk.getPos().getMinBlockX(), entry.sy * 16.0, entry.chunk.getPos().getMinBlockZ());

				entry.poseSent = sendPose(entry);
			}
		} catch (ReflectiveOperationException | RuntimeException error) {
			if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't publish Sable motion", error); }
		} finally {
			// Interpolation must be sampled again next frame, including stationary ships.
			FRAME_POSES.clear();
		}
	}

	private static boolean sendPose(Entry entry) {
		entry.matrix.get(entry.values);
		POSE.clear().putLong(entry.id);
		for (double value : entry.values) POSE.putDouble(value);
		POSE.flip();
		return SkyLink.tryWriteRender(Proto.REN_MOVING_POSE, POSE, null);
	}

	static void frame(Minecraft minecraft, AvatarExporter scene, SkyAtlas atlas, Vec3 camera, double[] origin, float partialTick) {
		resolve();
		if (!available) return;
		ClientLevel level = minecraft.level;
		if (level == null) return;
		if (level != currentLevel || atlas != currentAtlas) {
			reset();
			currentLevel = level;
			currentAtlas = atlas;
		}
		frame++;
		long lightCheckTime = System.nanoTime();
		try {
			Object owner = container.invoke(null, level);
			if (owner == null) return;
			double range = Math.max(64, minecraft.options.getEffectiveRenderDistance() * 16.0);
			AABB visible = new AABB(camera.x - range, camera.y - range, camera.z - range, camera.x + range, camera.y + range, camera.z + range);
			for (Object ship : (Iterable<?>) subLevels.invoke(owner)) {
				if (Boolean.TRUE.equals(removed.invoke(ship))) continue;
				AABB worldBounds = (AABB) toAabb.invoke(bounds.invoke(ship));
				if (!visible.intersects(worldBounds)) continue;
				Matrix4d world = new Matrix4d();
				bakeMatrix.invoke(renderPose.invoke(ship, partialTick), world);
				org.joml.Vector3d localCamera = new Matrix4d(world).invert().transformPosition(new org.joml.Vector3d(camera.x, camera.y, camera.z));
				Vec3 plotCamera = new Vec3(localCamera.x, localCamera.y, localCamera.z);
				Object plot = getPlot.invoke(ship);
				var lights = (net.minecraft.world.level.lighting.LevelLightEngine) plotLights.invoke(plot);
				for (Object holder : (Iterable<?>) loadedChunks.invoke(plot)) {
					LevelChunk chunk = (LevelChunk) holderChunk.invoke(holder);
					if (chunk == null) continue;
					var sections = chunk.getSections();
					for (int index = 0; index < sections.length; index++) {
						if (sections[index].hasOnlyAir()) continue;
						int sy = level.getSectionYFromSectionIndex(index);
						long key = SectionPos.asLong(chunk.getPos().x, sy, chunk.getPos().z);
						Entry entry = SECTIONS.computeIfAbsent(key, ignored -> new Entry());
						if (entry.ship != ship || entry.chunk != chunk) { entry.revision.incrementAndGet(); entry.mesh = null; }
						entry.ship = ship;
						entry.chunk = chunk;
						entry.lights = lights;
						entry.sy = sy;
						entry.seen = frame;
						entry.matrix.set(world).translate(chunk.getPos().getMinBlockX(), sy * 16.0, chunk.getPos().getMinBlockZ());
						// Poll light while stationary too; world light updates use world coordinates,
						// whereas this cache is indexed by the plot's remote coordinates.
						if (entry.revision.get() != entry.capturedRevision || !entry.uploaded
								|| lightCheckTime >= entry.nextLightCheck) PENDING.add(key);
					}
					Matrix4f relative = new Matrix4f(new Matrix4d().translation(-origin[0], -origin[1], -origin[2]).mul(world)
						.translate(chunk.getPos().getMinBlockX(), 0, chunk.getPos().getMinBlockZ()));
					scene.captureMovingBlockEntities(minecraft, chunk, relative, plotCamera, partialTick);
				}
			}
			// Remove stale, empty, unloaded or disassembled plots. Retain removal retries when the ring is full.
			var entries = SECTIONS.entrySet().iterator();
			while (entries.hasNext()) {
				var pair = entries.next();
				if (pair.getValue().seen == frame) continue;
				REMOVE.clear().putLong(pair.getValue().id).flip();
				if (SkyLink.tryWriteRender(Proto.REN_MOVING_REMOVE, REMOVE, null)) { PENDING.remove(pair.getKey()); entries.remove(); }
			}
			// Publish motion before geometry can consume the ring or the frame budget.
			// Native accepts a pose before its first mesh and retains it across mesh replacements.
			// A pending light/model rebuild must never freeze a mesh already on the GPU.
			for (Entry entry : SECTIONS.values()) {
				if (entry.seen != frame || entry.poseSent) continue;
				sendPose(entry); // new meshes and retries; no dependency on a geometry upload
			}
			// Fair work queue: update geometry only when blocks/models change, never because the ship moves.
			long deadline = System.nanoTime() + 3_000_000L;
			int attempts = PENDING.size();
			while (attempts-- > 0 && !PENDING.isEmpty() && System.nanoTime() < deadline) {
				long key = PENDING.iterator().next();
				PENDING.remove(key);
				Entry entry = SECTIONS.get(key);
				if (entry == null || entry.seen != frame) continue;
				if (entry.revision.get() != entry.capturedRevision || entry.mesh == null) {
					long revision = entry.revision.get();
					entry.mesh = AvatarExporter.captureMovingSection(minecraft, atlas, level, entry.chunk, entry.sy, entry.lights);
					entry.uploaded = false;
					entry.capturedRevision = revision;
				}
                if (System.nanoTime() >= entry.nextLightCheck) {
                    if (entry.mesh.refreshMovingLight(entry.matrix)) entry.uploaded = false;
                    entry.nextLightCheck = System.nanoTime() + 250_000_000L;
                }
                if (entry.uploaded) continue;
				if (entry.mesh.sendMovingMesh(entry.id)) {
					entry.uploaded = true;
					if (meshLogs++ < 12) SkyCraft.LOG.info("SkyCraft: moving plot section {} uploaded (mesh {})", SectionPos.of(key), entry.id);
				} else PENDING.add(key);
			}
		} catch (ReflectiveOperationException | RuntimeException error) {
			if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't capture Sable moving structures", error); }
		}
	}
}
