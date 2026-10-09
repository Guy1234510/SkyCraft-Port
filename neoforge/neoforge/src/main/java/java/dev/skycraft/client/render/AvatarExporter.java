package dev.skycraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.skycraft.SkyCraft;
import dev.skycraft.client.mixin.RenderSetupAccessor;
import dev.skycraft.client.mixin.BooleanRenderStateAccessor;
import dev.skycraft.client.mixin.ParticleEngineAccessor;
import dev.skycraft.client.mixin.RenderTypeAccessor;
import dev.skycraft.client.mixin.TextureBindingAccessor;
import dev.skycraft.client.mixin.TextureManagerAccessor;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * Minecraft's 1.21.1 entity and block-entity renderers are directed into a CPU vertex collector;
 * Skyrim receives the posed geometry and its textures, then applies its own lighting.
 *
 * <p>Two captures a frame: the player's body in third person (F5), relative to the feet Skyrim's
 * camera follows so it can't drift from the camera; and everything else (lit TNT, falling
 * blocks, minecarts, boats, ..., block entities (chests, beds, signs, banners, pistons while they
 * move, ...) and all particles) relative to a block near the camera. Arrows,
 * Dropped items retain their full entity renderer; thrown items use WorldExporter. Render thread only.
 */
final class AvatarExporter {
	// Vertex flags: cutout, full-detail texture, lit by its own faces / without a normal / blended.
	private static final int SOLID = 1 | 8 | (7 << 4);
	private static final int PARTICLE = 1 | 8;
	private static final int PARTICLE_BLENDED = 2 | 8;
	private static final int EMISSIVE = 4096, ADDITIVE = 8192, OPAQUE_TEXTURE = 16384, CULL_BACK = 32768;
	// How a batch's raw UVs map into the combined atlas (texture 0).
	private static final int UV_RAW = 0, UV_BLOCK_ATLAS = 1, UV_ITEM_ATLAS = 2;
	private static final Direction[] FACES_AND_NONE = { null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
	private static final double SCENE_RANGE = 64.0;
	private static final int SCENE_MAX_ENTITIES = 48;
	private static final int SCENE_MAX_DROPPED_ITEMS = 128;
	private static final double BLOCK_ENTITY_RANGE = 48.0;
	private static final int SCENE_MAX_BLOCK_ENTITIES = 256;
	private static boolean warnedBlockEntity;
	private static int movingLightLogs;

	// Textures Skyrim holds, shared by both captures.
	private static final Map<ResourceLocation, Integer> TEXTURE_IDS = new HashMap<>();
	private static final Set<Integer> BINARY_ALPHA_TEXTURES = new HashSet<>();
	private static final Set<ResourceLocation> DEPTH_TEXTURE_LOGGED = new HashSet<>();
	private static final Set<ResourceLocation> UNUSABLE = new HashSet<>();
	private static final Map<ResourceLocation, Long> TEXTURE_RETRY_AFTER = new HashMap<>();
	private static int nextTextureId = 1;
    private static final Map<ResourceLocation, TextureUpload> TEXTURE_UPLOADS = new java.util.LinkedHashMap<>();
    private static final int TEXTURE_STRIPE_BYTES = 2 * 1024 * 1024;
    private static final class TextureUpload implements AutoCloseable {
        final NativeImage image;
        final int id;
        final ByteBuffer pixels;
        int row;
        boolean allocated, binaryAlpha = true;
        TextureUpload(NativeImage image, int id) {
            this.image = image; this.id = id;
            this.pixels = ByteBuffer.allocateDirect(TEXTURE_STRIPE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        }
        public void close() { image.close(); }
    }

    /** At most one stripe per frame; retries retain both the image and progress. Never wait for IPC. */
    private static void pumpTextures() {
        var iterator = TEXTURE_UPLOADS.entrySet().iterator();
        if (!iterator.hasNext()) return;
        var entry = iterator.next();
        TextureUpload upload = entry.getValue();
        int w = upload.image.getWidth(), h = upload.image.getHeight();
        if (!upload.allocated) {
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(upload.id).putInt(w).putInt(h).putInt(0).flip();
            if (!SkyLink.tryWriteRender(Proto.REN_TEXTURE, header, null)) return;
            upload.allocated = true;
        }
        int rows = Math.min(h - upload.row, TEXTURE_STRIPE_BYTES / (w * 4));
        ByteBuffer pixels = upload.pixels.clear();
        long pointer = ((dev.skycraft.client.mixin.NativeImageAccessor)(Object)upload.image).skycraft$pixels();
        ByteBuffer source = org.lwjgl.system.MemoryUtil.memByteBuffer(pointer, w * h * 4);
        source.position(upload.row * w * 4).limit((upload.row + rows) * w * 4);
        pixels.put(source);
        if (upload.binaryAlpha) for (int offset = 3; offset < pixels.position(); offset += 4) {
            int alpha = pixels.get(offset) & 255;
            if (alpha != 0 && alpha != 255) { upload.binaryAlpha = false; break; }
        }
        pixels.flip();
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(upload.id).putInt(0).putInt(upload.row).putInt(w).putInt(rows).putInt(0).flip();
        if (!SkyLink.tryWriteRender(Proto.REN_TEXTURE_REGION, header, pixels)) return;
        upload.row += rows;
        if (upload.row == h) {
            TEXTURE_IDS.put(entry.getKey(), upload.id);
            if (upload.binaryAlpha) BINARY_ALPHA_TEXTURES.add(upload.id);
            SkyCraft.LOG.info("SkyCraft: streamed texture {} to Skyrim (id {}, {}x{})", entry.getKey(), upload.id, w, h);
            upload.close(); iterator.remove();
        }
    }
	private static boolean warnedEntity;
	private static boolean warnedParticle;

	private static final AvatarExporter AVATAR = new AvatarExporter();
	private static final AvatarExporter SCENE = new AvatarExporter();
	private static final AvatarExporter RAGDOLL = new AvatarExporter();
	private static long nextRagdollNanos;

	private final Map<Long, Batch> batches = new HashMap<>();
	private final Map<RenderType, Batch> materials = new java.util.IdentityHashMap<>();
	private static final int[] QUAD_TRIANGLES = { 0, 1, 2, 0, 2, 3 };
	private ByteBuffer reusableVertices = ByteBuffer.allocateDirect(32 * 1024).order(ByteOrder.LITTLE_ENDIAN);
	private ByteBuffer reusableHeader = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);

	private ByteBuffer vertexBuffer(int bytes) {
		if (this.reusableVertices.capacity() < bytes) {
			this.reusableVertices = ByteBuffer.allocateDirect(Math.max(bytes, this.reusableVertices.capacity() * 2))
				.order(ByteOrder.LITTLE_ENDIAN);
		}
		return this.reusableVertices.clear();
	}

	private ByteBuffer headerBuffer(int bytes) {
		if (this.reusableHeader.capacity() < bytes) {
			this.reusableHeader = ByteBuffer.allocate(Math.max(bytes, this.reusableHeader.capacity() * 2))
				.order(ByteOrder.LITTLE_ENDIAN);
		}
		return this.reusableHeader.clear();
	}
	private final Capture ignoredCapture = new Capture(null);
	private final MultiBufferSource buffers = renderType -> {
		Batch batch = this.batchFor(renderType);
		// Renderers retain consumers and can feed several of them simultaneously (e.g. glint).
		// Their destinations must stay stable throughout the render call.
		return batch == null ? this.ignoredCapture : batch.capture;
	};
	private boolean shown;
	private int movingLight = -1;
    private MovingSample movingSample;
    private MovingBlockView movingView;
    private net.minecraft.client.multiplayer.ClientLevel movingLevel;
    private int movingX, movingY, movingZ;
    private final java.util.List<MovingSample> movingSamples = new java.util.ArrayList<>();

    private static final class MovingSample {
        final net.minecraft.core.BlockPos pos;
        final int emission;
        int light;
        MovingSample(net.minecraft.core.BlockPos pos, int emission, int light) {
            this.pos = pos.immutable(); this.emission = emission; this.light = light;
        }
    }

    private static int movingSurfaceLight(MovingBlockView view, net.minecraft.client.multiplayer.ClientLevel level,
            net.minecraft.core.BlockPos pos, net.minecraft.core.BlockPos worldPos, int emission) {
        int sky = view.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
        int block = Math.max(emission, view.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos));
        for (var face : Direction.values()) {
            var sample = pos.relative(face);
            if (!view.getBlockState(sample).isSolidRender(view, sample)) {
                sky = Math.max(sky, view.getBrightness(net.minecraft.world.level.LightLayer.SKY, sample));
                block = Math.max(block, view.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample));
            }
        }
        sky = Math.min(sky, surfaceLight(level, net.minecraft.world.level.LightLayer.SKY, worldPos));
        block = Math.max(block, surfaceLight(level, net.minecraft.world.level.LightLayer.BLOCK, worldPos));
        return block | (sky << 8);
    }

    /** Resample light without invoking block renderers or rebuilding geometry. */
    boolean refreshMovingLight(org.joml.Matrix4d sectionToWorld) {
        if (this.movingView == null) return false;
        boolean changed = false;
        var world = new org.joml.Vector3d();
        for (var sample : this.movingSamples) {
            world.set(sample.pos.getX() - movingX + 0.5, sample.pos.getY() - movingY + 0.5, sample.pos.getZ() - movingZ + 0.5);
            sectionToWorld.transformPosition(world);
            int light = movingSurfaceLight(this.movingView, this.movingLevel, sample.pos,
                net.minecraft.core.BlockPos.containing(world.x, world.y, world.z), sample.emission);
            changed |= light != sample.light;
            sample.light = light;
        }
        if (!changed) return false;
        for (Batch batch : this.batches.values()) {
            if (batch.movingSources == null) continue;
            for (int v = 0; v < batch.count; v++) {
                var sample = batch.movingSources[v];
                if (sample != null) batch.data[v * 8 + 6] = sample.light;
            }
        }
        return true;
    }

    private static int surfaceLight(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.level.LightLayer layer, net.minecraft.core.BlockPos pos) {
        int light = level.getBrightness(layer, pos);
        for (var direction : Direction.values()) {
            var sample = pos.relative(direction);
            if (!level.getBlockState(sample).isSolidRender(level, sample)) light = Math.max(light, level.getBrightness(layer, sample));
        }
        return light;
    }

	private SkyAtlas atlas;
	// Added to every position (particles and their groups come relative to the camera).
	private float offX, offY, offZ;

	private AvatarExporter() {
	}

	/** Skyrim dropped everything (new link, new world, new atlas): textures go again. */
	static void reset() {
		SableRenderCompat.reset();
		for (AvatarExporter exporter : new AvatarExporter[] { AVATAR, SCENE, RAGDOLL }) {
			exporter.materials.clear();
			exporter.batches.clear();
		}
		TEXTURE_UPLOADS.values().forEach(TextureUpload::close);
		TEXTURE_UPLOADS.clear();
		TEXTURE_IDS.clear();
		BINARY_ALPHA_TEXTURES.clear();
		DEPTH_TEXTURE_LOGGED.clear();
		UNUSABLE.clear();
		TEXTURE_RETRY_AFTER.clear();
		nextTextureId = 1;
		AVATAR.shown = false;
		SCENE.shown = false;
		RAGDOLL.shown = false;
		nextRagdollNanos = 0;
	}

	static void frame(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		ModRenderCompat.begin();
		try {
		pumpTextures();
		AVATAR.exportAvatar(minecraft, atlas, partialTick);
		SCENE.exportScene(minecraft, atlas, partialTick);
		long now = System.nanoTime();
		if (now >= nextRagdollNanos) {
			nextRagdollNanos = now + 1_000_000_000L;
			RAGDOLL.exportRagdoll(minecraft, atlas, partialTick);
		}
		} finally { ModRenderCompat.end(); }
	}

	// ---- the two captures -------------------------------------------------------------------------

	private void exportAvatar(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		this.atlas = atlas;
		var player = minecraft.player;
		Camera camera = minecraft.gameRenderer.getMainCamera();
		if (player == null || minecraft.options.getCameraType().isFirstPerson()) {
			this.sendEmpty(Proto.REN_AVATAR, false);
			return;
		}
		this.begin();
		try (var capturePose = new RenderPoseGuard(player)) {
			var dispatcher = minecraft.getEntityRenderDispatcher();
			dispatcher.prepare(minecraft.level, camera, minecraft.crosshairPickEntity);
			// At the origin: the old entity renderer emits geometry relative to the player's feet.
			dispatcher.render(player, 0.0, 0.0, 0.0, player.getYRot(), partialTick, new PoseStack(), this.buffers, 0xF000F0);
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't capture the player model", e);
			return;
		}
		this.send(Proto.REN_AVATAR, null);
	}

	/**
	 * The player's body standing still, facing +Z, feet at the origin, split into Minecraft's six
	 * parts, for Skyrim to hang on its ragdoll when the player dies. Kept up to date while alive
	 * (skin and armour change), so the last one before death is the one that falls.
	 */
	private void exportRagdoll(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		this.atlas = atlas;
		var player = minecraft.player;
		if (player == null || player.isDeadOrDying() || player.isSpectator()) {
			return;
		}
		this.begin();
		float yaw = player.getYRot(), oldYaw = player.yRotO;
		float bodyYaw = player.yBodyRot, oldBodyYaw = player.yBodyRotO;
		float headYaw = player.yHeadRot, oldHeadYaw = player.yHeadRotO;
		float pitch = player.getXRot(), oldPitch = player.xRotO;
		try {
			var dispatcher = minecraft.getEntityRenderDispatcher();
			Camera camera = minecraft.gameRenderer.getMainCamera();
			dispatcher.prepare(minecraft.level, camera, minecraft.crosshairPickEntity);
			player.setYRot(0.0F);
			player.yRotO = player.yBodyRot = player.yBodyRotO = player.yHeadRot = player.yHeadRotO = 0.0F;
			player.setXRot(0.0F);
			player.xRotO = 0.0F;
			dispatcher.render(player, 0.0, 0.0, 0.0, 0.0F, 0.0F, new PoseStack(), this.buffers, 0xF000F0);
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't capture the player's body for the ragdoll", e);
		} finally {
			player.setYRot(yaw);
			player.yRotO = oldYaw;
			player.yBodyRot = bodyYaw;
			player.yBodyRotO = oldBodyYaw;
			player.yHeadRot = headYaw;
			player.yHeadRotO = oldHeadYaw;
			player.setXRot(pitch);
			player.xRotO = oldPitch;
		}
		this.sendParts(Proto.REN_RAGDOLL);
	}

	/** Which of Minecraft's six parts a point of the standing, +Z-facing body belongs to. */
	private static int partAt(float x, float y) {
		if (y >= 1.5F) {
			return Proto.PART_HEAD;
		}
		if (y >= 0.75F) {
			// The arms hang outside the body's 8-pixel width; its right side is -X facing +Z.
			return x < -0.255F ? Proto.PART_RIGHT_ARM : x > 0.255F ? Proto.PART_LEFT_ARM : Proto.PART_BODY;
		}
		return x < 0.0F ? Proto.PART_RIGHT_LEG : Proto.PART_LEFT_LEG;
	}

	/** Like send, with each batch split by the part its quads belong to (RenBatch flags bits 8-11). */
	private void sendParts(int message) {
		this.flushCaptures();
		record Group(Batch batch, int part, int[] quads, int count) {
		}
		List<Group> groups = new ArrayList<>();
		int vertices = 0;
		for (Batch b : this.batches.values()) {
			int quadCount = b.count / 4;
			if (quadCount == 0) {
				continue;
			}
			int[][] byPart = new int[7][quadCount];
			int[] counts = new int[7];
			for (int q = 0; q < quadCount; q++) {
				float cx = 0.0F, cy = 0.0F;
				for (int k = 0; k < 4; k++) {
					int o = (q * 4 + k) * 8;
					cx += Float.intBitsToFloat(b.data[o]);
					cy += Float.intBitsToFloat(b.data[o + 1]);
				}
				int part = partAt(cx * 0.25F, cy * 0.25F);
				byPart[part][counts[part]++] = q;
			}
			for (int part = 1; part < 7; part++) {
				if (counts[part] > 0) {
					groups.add(new Group(b, part, byPart[part], counts[part]));
					vertices += counts[part] * 6;
				}
			}
		}
		if (groups.isEmpty()) {
			return;
		}
		ByteBuffer header = this.headerBuffer(8 + groups.size() * 16);
		header.putInt(groups.size()).putInt(vertices);
		ByteBuffer body = this.vertexBuffer(vertices * Proto.REN_VERTEX_BYTES);
		int first = 0;
		for (Group g : groups) {
			int count = g.count() * 6;
			header.putInt(g.batch().texture).putInt(first).putInt(count).putInt(g.batch().drawFlags() | g.part() << 8);
			for (int i = 0; i < g.count(); i++) {
				g.batch().writeQuad(body, g.quads()[i]);
			}
			first += count;
		}
		header.flip();
		body.flip();
		if (SkyLink.tryWriteRender(message, header, body)) {
			this.shown = true;
		}
	}

	private void exportScene(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		partialTick = Mth.clamp(partialTick, 0.0F, 1.0F);
		this.atlas = atlas;
		var level = minecraft.level;
		var player = minecraft.player;
		if (level == null || player == null) {
			return;
		}
		Camera camera = minecraft.gameRenderer.getMainCamera();
		Vec3 cam = camera.getPosition();
		double[] origin = { Math.floor(cam.x), Math.floor(cam.y), Math.floor(cam.z) };
		this.begin();
		var dispatcher = minecraft.getEntityRenderDispatcher();
		dispatcher.prepare(level, camera, minecraft.crosshairPickEntity);
		PoseStack pose = new PoseStack();
		int entities = 0;
		int droppedItems = 0;
		for (Entity e : MultipartRenderCompat.entities(level.entitiesForRendering())) {
			if (e == player || e instanceof AbstractArrow || e instanceof ItemSupplier || e instanceof SkyrimActorEntity
				|| e.distanceToSqr(cam) > SCENE_RANGE * SCENE_RANGE
				|| (e instanceof ItemEntity ? droppedItems >= SCENE_MAX_DROPPED_ITEMS : entities >= SCENE_MAX_ENTITIES)) {
				continue;
			}
			// Keep item piles from consuming the separate mob/model budget.
			if (e instanceof ItemEntity) droppedItems++; else entities++;
			try (var capturePose = new RenderPoseGuard(e)) {
				// Match vanilla's frame interpolation instead of exporting 20 Hz tick positions.
				// Newly spawned entities may not have their previous position initialized yet.
				double x = e.tickCount == 0 ? e.getX() : Mth.lerp((double) partialTick, e.xOld, e.getX());
				double y = e.tickCount == 0 ? e.getY() : Mth.lerp((double) partialTick, e.yOld, e.getY());
				double z = e.tickCount == 0 ? e.getZ() : Mth.lerp((double) partialTick, e.zOld, e.getZ());
				float yaw = e.tickCount == 0 ? e.getYRot() : Mth.rotLerp(partialTick, e.yRotO, e.getYRot());
				if (e instanceof net.minecraft.world.entity.LivingEntity living && headDiagnostics < 12
					&& (Math.abs(Mth.wrapDegrees(living.yHeadRot - living.yHeadRotO)) > 45.0F
						|| Math.abs(living.getXRot() - living.xRotO) > 45.0F)) {
					long now = System.nanoTime();
					if (now - lastHeadDiagnostic > 500_000_000L) {
						lastHeadDiagnostic = now;
						headDiagnostics++;
						SkyCraft.LOG.info("SkyCraft head diagnostic: entity={} tick={} partial={} head={}->{} body={}->{} pitch={}->{}",
							e.getId(), e.tickCount, partialTick, living.yHeadRotO, living.yHeadRot,
							living.yBodyRotO, living.yBodyRot, living.xRotO, living.getXRot());
					}
				}
				dispatcher.render(e, x - origin[0], y - origin[1], z - origin[2], yaw, partialTick, pose,
					this.buffers, dispatcher.getPackedLightCoords(e, partialTick));
			} catch (RuntimeException ex) {
				if (!warnedEntity) {
					warnedEntity = true;
					SkyCraft.LOG.warn("SkyCraft: couldn't capture {} for Skyrim", e, ex);
				}
			}
		}
		submitBlockEntities(minecraft, level, cam, origin, partialTick, pose);
		SableRenderCompat.frame(minecraft, this, atlas, cam, origin, partialTick);
		PoseStack outlinePose = new PoseStack();
		outlinePose.translate(cam.x - origin[0], cam.y - origin[1], cam.z - origin[2]);
		ModRenderCompat.outlines(outlinePose, this.buffers, cam, partialTick);
		this.submitBlockCracks(minecraft, origin, partialTick);
		this.submitTerrainCracks(minecraft, origin);
		this.submitParticles(minecraft, camera, origin, partialTick);
		this.flushCaptures();
		this.send(Proto.REN_SCENE, origin);
	}

	private static int headDiagnostics;
	private static long lastHeadDiagnostic;

	/** Both older versions expose sprite particle rendering through VertexConsumer. */
	private void submitParticles(Minecraft minecraft, Camera camera, double[] origin, float partialTick) {
		this.flushCaptures();
		var groups = ((ParticleEngineAccessor) minecraft.particleEngine).skycraft$particles();
		Vec3 cam = camera.getPosition();
		this.offX = (float) (cam.x - origin[0]);
		this.offY = (float) (cam.y - origin[1]);
		this.offZ = (float) (cam.z - origin[2]);
		int count = 0;
		try {
			for (var group : groups.entrySet()) {
				if (group.getValue().isEmpty()) continue;
				var type = group.getKey();
				if (type == ParticleRenderType.NO_RENDER || type == ParticleRenderType.CUSTOM) continue;
				boolean terrain = type == ParticleRenderType.TERRAIN_SHEET;
				int texture = terrain ? 0 : textureId(TextureAtlas.LOCATION_PARTICLES);
				if (texture < 0) continue;
				Batch batch = this.batch(texture, terrain ? UV_BLOCK_ATLAS : UV_RAW,
					type == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT ? PARTICLE_BLENDED : PARTICLE);
				// Finish each sprite before moving to the next particle or resetting offsets.
				batch.capture.flush();
				for (var particle : group.getValue()) {
					if (!(particle instanceof SingleQuadParticle) || !particle.isAlive()
						|| particle.getBoundingBox().getCenter().distanceToSqr(cam) > SCENE_RANGE * SCENE_RANGE) continue;
					if (++count > 1024) return;
					particle.render(batch.capture, camera, partialTick);
					batch.capture.flush();
				}
			}
		} catch (RuntimeException error) {
			if (!warnedParticle) {
				warnedParticle = true;
				SkyCraft.LOG.warn("SkyCraft: couldn't capture sprite particles for Skyrim", error);
			}
		} finally {
			this.offX = this.offY = this.offZ = 0.0F;
		}
	}

    /** Keep plot coordinates in doubles until transformed, then emit camera-relative cracks. */
    private void submitBlockCracks(Minecraft minecraft, double[] origin, float partialTick) {
        var level = minecraft.level;
        var progressMap = ((dev.skycraft.client.mixin.LevelRendererAccessor)minecraft.levelRenderer).skycraft$destroyingBlocks();
        for (var progress : progressMap.values()) {
            int stage = progress.getProgress();
            if (stage < 0 || stage > 9) continue;
            var pos = progress.getPos();
            Vec3 centre = SableRenderCompat.worldPosition(Vec3.atCenterOf(pos), partialTick);
            if (centre.distanceToSqr(new Vec3(origin[0], origin[1], origin[2])) > SCENE_RANGE * SCENE_RANGE) continue;
            var shape = level.getBlockState(pos).getShape(level, pos);
            float[] uv = this.atlas.crackUv(stage);
            Batch batch = this.batch(0, UV_RAW, PARTICLE_BLENDED);
            for (var bounds : shape.toAabbs()) {
                var box = bounds.move(pos).inflate(0.004);
                Vec3[] corners = new Vec3[8];
                for (int k = 0; k < 8; k++) {
                    Vec3 point = new Vec3((k & 1) == 0 ? box.minX : box.maxX,
                        (k & 2) == 0 ? box.minY : box.maxY, (k & 4) == 0 ? box.minZ : box.maxZ);
                    corners[k] = SableRenderCompat.worldPosition(point, partialTick);
                }
                for (int[] face : CRACK_FACES) for (int k = 0; k < 4; k++) {
                    Vec3 point = corners[face[k]];
                    batch.add((float)(point.x-origin[0]), (float)(point.y-origin[1]), (float)(point.z-origin[2]),
                        k == 0 || k == 3 ? uv[0] : uv[2], k < 2 ? uv[1] : uv[3], -1, 0xF000F0, OverlayTexture.NO_OVERLAY);
                }
            }
        }
    }
    private static final int[][] CRACK_FACES = {
        {0,1,3,2}, {5,4,6,7}, {4,0,2,6}, {1,5,7,3}, {2,3,7,6}, {4,5,1,0}
    };

	/** Crack stages on the actual selected Skyrim face, clipped to the mined cell. */
	private void submitTerrainCracks(Minecraft minecraft, double[] origin) {
		var mining = dev.skycraft.client.SkyDigClient.mining(minecraft);
		if (mining == null) return;
		var hit = mining.hit();
		var tri = hit.tri();
		var pos = mining.pos();
		java.util.List<double[]> polygon = new java.util.ArrayList<>();
		polygon.add(new double[] { tri.ax, tri.ay, tri.az });
		polygon.add(new double[] { tri.bx, tri.by, tri.bz });
		polygon.add(new double[] { tri.cx, tri.cy, tri.cz });
		double[] corner = { pos.getX(), pos.getY(), pos.getZ() };
		for (int axis = 0; axis < 3; axis++) {
			polygon = clipCrack(polygon, axis, corner[axis], true);
			polygon = clipCrack(polygon, axis, corner[axis] + 1, false);
		}
		if (polygon.size() < 3) return;
		float[] rect = this.atlas.crackUv(mining.stage());
		Batch batch = this.batch(0, UV_RAW, PARTICLE_BLENDED);
		// Finish any pending renderer vertex before adding complete triangles to its batch.
		batch.capture.flush();
		int normalAxis = dev.skycraft.world.SkyRay.dominantFace(hit.nx(), hit.ny(), hit.nz()) / 2;
		// dominantFace pairs are Y, Z, X. Project onto the other two coordinates.
		int uAxis = normalAxis == 2 ? 2 : 0;
		int vAxis = normalAxis == 0 ? 2 : 1;
		for (int i = 1; i + 1 < polygon.size(); i++) {
			for (double[] point : new double[][] { polygon.get(0), polygon.get(i), polygon.get(i + 1), polygon.get(i + 1) }) {
				float u = rect[0] + (float) (point[uAxis] - corner[uAxis]) * (rect[2] - rect[0]);
				float v = rect[1] + (float) (point[vAxis] - corner[vAxis]) * (rect[3] - rect[1]);
				batch.add((float) (point[0] - origin[0] + hit.nx() * 0.006),
					(float) (point[1] - origin[1] + hit.ny() * 0.006),
					(float) (point[2] - origin[2] + hit.nz() * 0.006), u, v, -1, 0xF000F0, OverlayTexture.NO_OVERLAY);
			}
		}
	}

	private static java.util.List<double[]> clipCrack(java.util.List<double[]> polygon, int axis, double plane, boolean above) {
		java.util.List<double[]> out = new java.util.ArrayList<>();
		for (int i = 0; i < polygon.size(); i++) {
			double[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
			boolean insideA = above ? a[axis] >= plane : a[axis] <= plane;
			boolean insideB = above ? b[axis] >= plane : b[axis] <= plane;
			if (insideA) out.add(a);
			if (insideA != insideB) {
				double t = (plane - a[axis]) / (b[axis] - a[axis]);
				out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t });
			}
		}
		return out;
	}

	/**
	 * Blocks Minecraft draws with their own renderer rather than as block models, so they aren't in
	 * the section meshes: chests, beds, signs, banners, shulker boxes, heads, bells, lecterns, pots,
	 * campfire items, spawners, and blocks being pushed by a piston (a moving block entity until
	 * the push ends). Minecraft only draws the ones in its visible sections; its world isn't drawn
	 * here, so they're taken straight from the loaded chunks around the camera.
	 */
	private void submitBlockEntities(Minecraft minecraft, net.minecraft.client.multiplayer.ClientLevel level, Vec3 cam, double[] origin, float partialTick,
		PoseStack pose) {
		var dispatcher = minecraft.getBlockEntityRenderDispatcher();
		dispatcher.prepare(level, minecraft.gameRenderer.getMainCamera(), minecraft.hitResult);
		double range2 = BLOCK_ENTITY_RANGE * BLOCK_ENTITY_RANGE;
		int count = 0;
		int cx0 = (int) Math.floor((cam.x - BLOCK_ENTITY_RANGE) / 16.0), cx1 = (int) Math.floor((cam.x + BLOCK_ENTITY_RANGE) / 16.0);
		int cz0 = (int) Math.floor((cam.z - BLOCK_ENTITY_RANGE) / 16.0), cz1 = (int) Math.floor((cam.z + BLOCK_ENTITY_RANGE) / 16.0);
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				var chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) {
					continue;
				}
				for (var blockEntity : chunk.getBlockEntities().values()) {
					var pos = blockEntity.getBlockPos();
					if (blockEntity.isRemoved() || pos.distToCenterSqr(cam) > range2 || count >= SCENE_MAX_BLOCK_ENTITIES) {
						continue;
					}
					try {
						count++;
						pose.pushPose();
						pose.translate(pos.getX() - origin[0], pos.getY() - origin[1], pos.getZ() - origin[2]);
						try { dispatcher.render(blockEntity, partialTick, pose, this.buffers); }
						finally { pose.popPose(); }
					} catch (RuntimeException ex) {
						if (!warnedBlockEntity) {
							warnedBlockEntity = true;
							SkyCraft.LOG.warn("SkyCraft: couldn't capture {} at {} for Skyrim", blockEntity.getType(), pos, ex);
						}
					}
				}
			}
		}
	}


	/** Model/fluid geometry stays section-local; native meshes receive only pose updates while moving. */
	static AvatarExporter captureMovingSection(Minecraft minecraft, SkyAtlas atlas, net.minecraft.client.multiplayer.ClientLevel level,
		net.minecraft.world.level.chunk.LevelChunk chunk, int sy, net.minecraft.world.level.lighting.LevelLightEngine lights) {
		AvatarExporter mesh = new AvatarExporter();
		mesh.atlas = atlas;
		PoseStack pose = new PoseStack();
		var renderer = minecraft.getBlockRenderer();
		var view = new MovingBlockView(level, lights);
		var random = net.minecraft.util.RandomSource.create();
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int ox = chunk.getPos().getMinBlockX(), oy = sy * 16, oz = chunk.getPos().getMinBlockZ();
        mesh.movingView = view;
        mesh.movingLevel = level;
        mesh.movingX = ox; mesh.movingY = oy; mesh.movingZ = oz;
		for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			pos.set(ox + x, oy + y, oz + z);
			var state = chunk.getBlockState(pos);
			if (state.isAir()) continue;
            var worldSample = net.minecraft.core.BlockPos.containing(SableRenderCompat.worldPosition(Vec3.atCenterOf(pos), 1.0F));
            int packed = movingSurfaceLight(view, level, pos, worldSample, state.getLightEmission());
            mesh.movingSample = new MovingSample(pos, state.getLightEmission(), packed);
            mesh.movingSamples.add(mesh.movingSample);
            mesh.movingLight = ((packed & 15) << 4) | (((packed >>> 8) & 15) << 20);

			var fluid = state.getFluidState();
			if (!fluid.isEmpty()) {
				renderer.renderLiquid(pos, view, mesh.buffers.getBuffer(net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(fluid)), state, fluid);
				mesh.flushCaptures();
			}
			if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
			var model = renderer.getBlockModel(state);
			var data = level.getModelDataManager().getAt(pos);
			if (data == null) data = net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
			data = model.getModelData(level, pos, state, data);
			random.setSeed(state.getSeed(pos));
			pose.pushPose();
			try {
				pose.translate(x, y, z);
				for (var layer : model.getRenderTypes(state, random, data)) {
					random.setSeed(state.getSeed(pos));
					renderer.renderBatched(state, pos, view, pose, mesh.buffers.getBuffer(layer), true, random, data, layer);
					mesh.flushCaptures();
				}
			} finally { pose.popPose(); }
		}
		mesh.logMovingLight();
		return mesh;
	}

	private void logMovingLight() {
		if (movingLightLogs++ >= 8) return;
		int minSky = 15, maxSky = 0, maxBlock = 0, vertices = 0;
		for (Batch batch : this.batches.values()) for (int v = 0; v < batch.count; v++) {
			int light = batch.data[v * 8 + 6];
			minSky = Math.min(minSky, (light >>> 8) & 15);
			maxSky = Math.max(maxSky, (light >>> 8) & 15);
			maxBlock = Math.max(maxBlock, light & 15);
			vertices++;
		}
		SkyCraft.LOG.info("SkyCraft: moving mesh light sky {}..{}, block max {}, vertices {} (directional shade delegated to Skyrim)",
			minSky, maxSky, maxBlock, vertices);
	}

	/** Animated chests, deployers and other renderers in plots retain their ordinary render methods. */
	void captureMovingBlockEntities(Minecraft minecraft, net.minecraft.world.level.chunk.LevelChunk chunk, org.joml.Matrix4f transform,
		Vec3 plotCamera, float partialTick) {
		var dispatcher = minecraft.getBlockEntityRenderDispatcher();
		for (var blockEntity : chunk.getBlockEntities().values()) {
			var renderer = dispatcher.getRenderer(blockEntity);
			if (blockEntity.isRemoved() || renderer == null || !renderer.shouldRender(blockEntity, plotCamera)) continue;
			var pos = blockEntity.getBlockPos();
			PoseStack pose = new PoseStack();
			pose.mulPose(transform);
			pose.translate(pos.getX() - chunk.getPos().getMinBlockX(), pos.getY(), pos.getZ() - chunk.getPos().getMinBlockZ());
            var worldPos = net.minecraft.core.BlockPos.containing(SableRenderCompat.worldPosition(Vec3.atCenterOf(pos), partialTick));
            var worldLevel = minecraft.level;
            int skyLight = surfaceLight(worldLevel, net.minecraft.world.level.LightLayer.SKY, worldPos);
            int blockLight = surfaceLight(worldLevel, net.minecraft.world.level.LightLayer.BLOCK, worldPos);
            this.movingLight = (blockLight << 4) | (skyLight << 20);

			try {
				renderer.render(blockEntity, partialTick, pose, this.buffers,
					net.minecraft.client.renderer.LevelRenderer.getLightColor(blockEntity.getLevel(), pos), OverlayTexture.NO_OVERLAY);
			} catch (RuntimeException error) {
				if (!warnedBlockEntity) { warnedBlockEntity = true; SkyCraft.LOG.warn("SkyCraft: couldn't capture a moving block entity at {}", pos, error); }
			} finally { this.flushCaptures(); this.movingLight = -1; }
		}
	}

	boolean sendMovingMesh(long id) { return this.send(Proto.REN_MOVING_MESH, null, id); }

	private void begin() {
		this.ignoredCapture.pending = false;
		for (Batch b : this.batches.values()) {
			b.clear();
		}
	}

	private void flushCaptures() {
		for (Batch batch : this.batches.values()) {
			batch.capture.flush();
		}
		this.ignoredCapture.flush();
	}

	/** Header: [origin (3 doubles), scene only] batchCount, vertexCount, then batches; body: triangles. */
	private void send(int message, double @Nullable [] origin) { this.send(message, origin, 0); }

	private boolean send(int message, double @Nullable [] origin, long movingId) {
		this.flushCaptures();
		List<Batch> used = new ArrayList<>();
		int vertices = 0;
		for (Batch b : this.batches.values()) {
			if (b.count >= 4) {
				used.add(b);
				vertices += b.count / 4 * 6;
			}
		}
        // Mod renderers can also yield an invalid pose. Retain the last valid
        // capture rather than sending non-finite vertices into every native pass.
        if (message == Proto.REN_AVATAR) {
            for (Batch batch : used) for (int v = 0; v < batch.count; v++) {
                int o = v * 8;
                if (!Float.isFinite(Float.intBitsToFloat(batch.data[o]))
                    || !Float.isFinite(Float.intBitsToFloat(batch.data[o + 1]))
                    || !Float.isFinite(Float.intBitsToFloat(batch.data[o + 2]))) return false;
            }
        }
		// A cancelled/temporarily empty third-person capture must not erase
        // the last valid skin. First-person and missing-player clears are explicit.
        if (used.isEmpty() && message == Proto.REN_AVATAR) {
            var player = Minecraft.getInstance().player;
            if (player != null && !player.isInvisible()) return false;
        }
        if (used.isEmpty() && message != Proto.REN_MOVING_MESH) {
			this.sendEmpty(message, origin != null);
			return true;
		}
		ByteBuffer header = this.headerBuffer((movingId != 0 ? 8 : 0) + (origin != null ? 24 : 0) + 8 + used.size() * 16);
		if (movingId != 0) header.putLong(movingId);
		if (origin != null) {
			header.putDouble(origin[0]).putDouble(origin[1]).putDouble(origin[2]);
		}
		header.putInt(used.size()).putInt(vertices);
		ByteBuffer body = this.vertexBuffer(vertices * Proto.REN_VERTEX_BYTES);
		int first = 0;
		for (Batch b : used) {
			int count = b.count / 4 * 6;
			header.putInt(b.texture).putInt(first).putInt(count).putInt(b.drawFlags());
			b.writeTriangles(body);
			first += count;
		}
		header.flip();
		body.flip();
		if (SkyLink.tryWriteRender(message, header, body)) {
			this.shown = true;
			return true;
		}
		return false;
	}

	private void sendEmpty(int message, boolean withOrigin) {
		if (!this.shown) {
			return;
		}
		ByteBuffer header = ByteBuffer.allocate((withOrigin ? 24 : 0) + 8).order(ByteOrder.LITTLE_ENDIAN);
		if (withOrigin) {
			header.putDouble(0).putDouble(0).putDouble(0);
		}
		header.putInt(0).putInt(0).flip();
		this.shown = !SkyLink.writeRender(message, header, null);
	}

	// ---- batches ---------------------------------------------------------------------------------

	private Batch batch(int texture, int uvMode, int flags) {
		return this.batch(texture, uvMode, flags, false);
	}

	private Batch batch(int texture, int uvMode, int flags, boolean depthCandidate) {
		long key = ((long) texture << 16) | ((long) uvMode << 8) | flags;
		if (depthCandidate) key |= Long.MIN_VALUE;
		return this.batches.computeIfAbsent(key, k -> new Batch(texture, uvMode, flags, depthCandidate));
	}

	/** Triangles for one texture and one kind of surface. Vertices arrive as quads. */
	private final class Batch {
		final int texture;
		final int uvMode;
		final int flags;
		final boolean translucent;
		final boolean depthCandidate;
		boolean partialVertexAlpha;
		final Capture capture = new Capture(this);
		int[] data = new int[8 * 256];
		MovingSample[] movingSources;
		int count;

		Batch(int texture, int uvMode, int flags, boolean depthCandidate) {
			this.texture = texture;
			this.uvMode = uvMode;
			this.flags = flags;
			this.translucent = (flags & 2) != 0;
			this.depthCandidate = depthCandidate;
		}

		void clear() {
			this.count = 0;
			this.partialVertexAlpha = false;
			this.capture.pending = false;
		}

		// Preserve vertex fades even when the uploaded texture has only binary alpha.
		int drawFlags() { return (usesBlend() ? 1 : 0) | ((flags & ADDITIVE) != 0 ? 2 : 0) | ((flags & CULL_BACK) != 0 ? 4 : 0); }

		boolean usesBlend() {
			return this.translucent && (!this.depthCandidate || this.partialVertexAlpha);
		}

		void add(float x, float y, float z, float u, float v, int argb, int light, int overlay) {
			if ((this.count + 1) * 8 > this.data.length) {
				this.data = java.util.Arrays.copyOf(this.data, this.data.length * 2);
			}
			if (this.uvMode == UV_BLOCK_ATLAS) {
				u = AvatarExporter.this.atlas.blockU(u);
				v = AvatarExporter.this.atlas.blockV(v);
			} else if (this.uvMode == UV_ITEM_ATLAS) {
				u = AvatarExporter.this.atlas.itemU(u);
				v = AvatarExporter.this.atlas.itemV(v);
			}
			// Minecraft's red "hurt" flash is an overlay texture: tint instead. Its white flash
			// (lit TNT about to go) glows instead.
			if (((overlay >>> 16) & 0xFFFF) < 8) {
				int r = (argb >> 16) & 0xFF, g = (int) (((argb >> 8) & 0xFF) * 0.55F), b = (int) ((argb & 0xFF) * 0.55F);
				argb = (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
			}
			if ((overlay & 0xFFFF) >= 8) {
				light = 0xF000F0;
			}
			if (AvatarExporter.this.movingLight >= 0) light = AvatarExporter.this.movingLight;
            if (AvatarExporter.this.movingSample != null) {
                if (this.movingSources == null) this.movingSources = new MovingSample[this.data.length / 8];
                else if (this.movingSources.length < this.data.length / 8)
                    this.movingSources = java.util.Arrays.copyOf(this.movingSources, this.data.length / 8);
                this.movingSources[this.count] = AvatarExporter.this.movingSample;
            }
			int o = this.count * 8;
			if ((argb >>> 24) != 255) this.partialVertexAlpha = true;
			this.data[o] = Float.floatToRawIntBits(x + AvatarExporter.this.offX);
			this.data[o + 1] = Float.floatToRawIntBits(y + AvatarExporter.this.offY);
			this.data[o + 2] = Float.floatToRawIntBits(z + AvatarExporter.this.offZ);
			this.data[o + 3] = Float.floatToRawIntBits(u);
			this.data[o + 4] = Float.floatToRawIntBits(v);
			this.data[o + 5] = argb;
			this.data[o + 6] = ((light >> 4) & 0xF) | (((light >> 20) & 0xF) << 8);
			this.data[o + 7] = this.flags | (AvatarExporter.this.movingLight >= 0 ? 1024 : 0);
			this.count++;
		}

		void writeTriangles(ByteBuffer out) {
			for (int q = 0; q + 4 <= this.count; q += 4) {
				this.writeQuad(out, q / 4);
			}
		}

		void writeQuad(ByteBuffer out, int quad) {
			for (int k : QUAD_TRIANGLES) {
				int o = (quad * 4 + k) * 8;
				out.putInt(this.data[o]).putInt(this.data[o + 1]).putInt(this.data[o + 2]).putInt(this.data[o + 3]).putInt(this.data[o + 4]);
				int argb = this.data[o + 5];
				out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
				out.putInt(this.data[o + 6]).putInt(this.depthCandidate && !this.usesBlend() ? (this.data[o + 7] & ~2) | SOLID : this.data[o + 7]);
			}
		}
	}

	/** A VertexConsumer with one fixed destination; models call addVertex followed by setters. */
	private final class Capture implements VertexConsumer {
		private final Batch batch;
		private boolean pending;
		private float x, y, z, u, v;
		private int color, light, overlay;

		Capture(Batch batch) {
			this.batch = batch;
		}

		void flush() {
			if (this.pending && this.batch != null) {
				this.batch.add(this.x, this.y, this.z, this.u, this.v, this.color, this.light, this.overlay);
			}
			this.pending = false;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.flush();
			this.x = x;
			this.y = y;
			this.z = z;
			this.color = -1;
			this.light = 0xF000F0;
			this.overlay = OverlayTexture.NO_OVERLAY;
			this.pending = true;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			this.color = (a << 24) | (r << 16) | (g << 8) | b;
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.color = color;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			this.overlay = (u & 0xFFFF) | (v << 16);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			this.light = (u & 0xFFFF) | (v << 16);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}
	}

	// ---- textures --------------------------------------------------------------------------------

	private static int textureId(ResourceLocation texture) {
		Integer known = TEXTURE_IDS.get(texture);
		if (known != null) {
			return known;
		}
		if (TEXTURE_UPLOADS.containsKey(texture)) return -1;
		if (UNUSABLE.contains(texture)) {
			return -1;
		}
		if (System.nanoTime() < TEXTURE_RETRY_AFTER.getOrDefault(texture, 0L)) {
			return -1;
		}
		NativeImage image = readTexture(texture);
		if (image == null) {
			// Skin downloads/uploads may complete after the first rendered frame.
			var registered = ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).skycraft$byPath().get(texture);
			if (registered instanceof HttpTexture || texture.getPath().startsWith("skins/")) {
				if (!TEXTURE_RETRY_AFTER.containsKey(texture)) {
					SkyCraft.LOG.info("SkyCraft: waiting for downloaded texture {} before exporting it", texture);
				}
				TEXTURE_RETRY_AFTER.put(texture, System.nanoTime() + 1_000_000_000L);
				return -1;
			}
			UNUSABLE.add(texture);
			SkyCraft.LOG.info("SkyCraft: texture {} isn't available to Skyrim; what uses it is left out", texture);
			return -1;
		}
		if (image.getWidth() > 4096 || image.getHeight() > 4096) {
            image.close(); UNUSABLE.add(texture);
            SkyCraft.LOG.warn("SkyCraft: texture {} exceeds native dimension limits", texture);
            return -1;
        }
        if ((long) image.getWidth() * image.getHeight() * 4 > TEXTURE_STRIPE_BYTES) {
            TEXTURE_UPLOADS.put(texture, new TextureUpload(image, nextTextureId++));
            return -1;
        }
        int id = nextTextureId++;
		boolean binaryAlpha = true;
		try (image) {
			int w = image.getWidth(), h = image.getHeight();
			ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					// NativeImage in 1.21.1 stores packed ABGR; little-endian bytes are RGBA.
					int pixel = image.getPixelRGBA(x, y);
					int alpha = pixel >>> 24;
					if (alpha != 0 && alpha != 255) binaryAlpha = false;
					pixels.putInt(pixel);
				}
			}
			pixels.flip();
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(w).putInt(h).putInt(0).flip();
			if (!SkyLink.tryWriteRender(Proto.REN_TEXTURE, header, pixels)) {
				nextTextureId--;
				return -1;
			}
		}
		TEXTURE_IDS.put(texture, id);
		if (binaryAlpha) BINARY_ALPHA_TEXTURES.add(id);
		TEXTURE_RETRY_AFTER.remove(texture);
		SkyCraft.LOG.info("SkyCraft: sent texture {} to Skyrim (id {})", texture, id);
		return id;
	}

	/** A texture's pixels: resource packs, a runtime texture (downloaded skins), or an atlas. Caller closes it. */
	private static @Nullable NativeImage readTexture(ResourceLocation texture) {
		if (texture.getNamespace().equals("skycraft") && texture.getPath().equals("internal/white")) {
			NativeImage image = new NativeImage(1, 1, false);
			image.setPixelRGBA(0, 0, -1);
			return image;
		}
		Minecraft minecraft = Minecraft.getInstance();
		var resource = minecraft.getResourceManager().getResource(texture);
		if (resource.isPresent()) {
			try (var in = resource.get().open()) {
				return NativeImage.read(in);
			} catch (java.io.IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}", texture, e);
				return null;
			}
		}
		var registered = ((TextureManagerAccessor) minecraft.getTextureManager()).skycraft$byPath().get(texture);
		if (registered instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
			NativeImage copy = new NativeImage(dynamic.getPixels().getWidth(), dynamic.getPixels().getHeight(), false);
			copy.copyFrom(dynamic.getPixels());
			return copy;
		}
		if (registered instanceof TextureAtlas atlas) {
			return SkyAtlas.image(atlas);
		}
		if (registered instanceof HttpTexture downloaded) {
			// 1.21.1 closes the processed skin's NativeImage after uploading it. Capture the
			// uploaded RGBA texture once, rather than expecting DynamicTexture's CPU image.
			int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
			int packRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
			int packSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
			int packSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
			try {
				GlStateManager._bindTexture(downloaded.getId());
				int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
				int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
				if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
					return null;
				}
				GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
				NativeImage image = new NativeImage(width, height, false);
				try {
					image.downloadTexture(0, false);
					return image;
				} catch (RuntimeException exception) {
					image.close();
					throw exception;
				}
			} finally {
				GlStateManager._bindTexture(previousTexture);
				GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
				GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, packRowLength);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, packSkipRows);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, packSkipPixels);
			}
		}
		return null;
	}

	/** Resolves 1.21.1 render-state texture bindings into the same shared atlas/texture batches. */
	private @Nullable Batch batchFor(RenderType renderType) {
		Batch cached = this.materials.get(renderType);
		if (cached != null) return cached;
		Batch batch = this.resolveBatch(renderType);
		// RenderTypes are immutable. Do not cache a texture that is not ready; retry normally.
		if (batch != null && this.materials.size() < 1024) this.materials.put(renderType, batch);
		return batch;
	}

	private @Nullable Batch resolveBatch(RenderType renderType) {
		if (renderType.mode() != com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) return null;
		RenderTypeAccessor accessor = (RenderTypeAccessor) renderType;
		String name = renderType.name;
		if (renderType == RenderType.waterMask() || name.contains("glint") || name.contains("shadow") || name.contains("crumbling")) {
			return null;
		}
		Object composite = accessor.skycraft$state();
		RenderStateShard.EmptyTextureStateShard textureState = ((RenderSetupAccessor) composite).skycraft$textureState();
		ResourceLocation texture = null;
		if (textureState instanceof RenderStateShard.TextureStateShard textured) {
			texture = ((TextureBindingAccessor) (Object) textured).skycraft$texture().orElse(null);
		}
		if (texture == null && (renderType == RenderType.solid() || renderType == RenderType.cutout()
			|| renderType == RenderType.cutoutMipped() || renderType == RenderType.translucent())) {
			texture = TextureAtlas.LOCATION_BLOCKS;
		}
		if (texture == null) texture = ResourceLocation.fromNamespaceAndPath("skycraft", "internal/white");
		int uvMode = UV_RAW;
		int textureId;
		if (texture.equals(TextureAtlas.LOCATION_BLOCKS)) {
			textureId = 0;
			uvMode = UV_BLOCK_ATLAS;
		} else {
			textureId = textureId(texture);
			if (textureId < 0) return null;
		}
		boolean translucent = texture.getPath().equals("internal/white") || name.contains("translucent") || name.contains("eyes") || name.contains("energy_swirl");
		// PlayerModel uses entityTranslucent even for opaque skin pixels. Skyrim's blended pass
		// does not write depth, so drawing the whole layered body there shows overlapping faces.
		// Put skin in its depth-writing alpha-cutout pass, retaining transparent background pixels.
		boolean skin = texture.getPath().startsWith("skins/") || texture.getPath().startsWith("textures/entity/player/");
		// Ordinary generated items also use a translucent sheet in 1.21.1. Their opaque front,
		// back and edge faces need depth writes, just like the layered skin.
		if (skin || renderType == net.minecraft.client.renderer.Sheets.translucentCullBlockSheet()) translucent = false;
		// GeckoLib and other renderers use entity_translucent for opaque/cutout models.
		// Without depth writes, the native blended pass exposes their back faces.
		// Classify uploaded pixels once; leave glow and genuine transparency blended.
		boolean depthCandidate = translucent && name.startsWith("entity_translucent")
			&& !name.contains("emissive") && BINARY_ALPHA_TEXTURES.contains(textureId);
		if (depthCandidate && DEPTH_TEXTURE_LOGGED.add(texture)) {
			SkyCraft.LOG.info("SkyCraft: depth writes enabled for binary-alpha entity texture {} ({}); vertex fades retain blending", texture, name);
		}
		int flags = translucent ? PARTICLE_BLENDED : SOLID;
		// The Staff ring has coincident inner/outer faces: draw the side selected by Minecraft.
		if (((BooleanRenderStateAccessor) (Object) ((RenderSetupAccessor) composite).skycraft$cullState()).skycraft$enabled())
			flags |= CULL_BACK;
        // RenderType.eyes is fullbright and additive; ordinary alpha/lighting makes it gray.
        if (name.contains("eyes")) flags |= EMISSIVE | ADDITIVE;
        else if (name.contains("emissive")) flags |= EMISSIVE;
        // Create's glowing item shader has no alpha discard on its solid layer.
        // The staff's half-alpha core must retain its source colour and depth coverage.
        if (name.contains("item_glowing")) {
            flags |= EMISSIVE;
            if (!translucent) flags |= OPAQUE_TEXTURE;
        }
        return this.batch(textureId, uvMode, flags, depthCandidate);
	}
}

