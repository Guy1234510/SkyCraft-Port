package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyClip;
import dev.skycraft.world.SkyRay;
import dev.skycraft.world.SkyDig;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ships what Minecraft would draw in the world to Skyrim, which draws it in its own frame (so it is
 * locked to the world and hidden behind Skyrim geometry): block and fluid meshes built by Minecraft's
 * own block renderer (models, tint, smooth lighting), the texture atlas, arrows and dropped items,
 * and the targeted-block outline. Render thread only.
 */
public final class WorldExporter {
	private static final int SECTIONS_PER_FRAME = 12;
	private static final double ENTITY_RANGE = 96.0;

	private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
	private static final LongLinkedOpenHashSet URGENT = new LongLinkedOpenHashSet();
	private static final int[] QUAD_TRIANGLES = { 0, 1, 2, 0, 2, 3 };
	private static final LongOpenHashSet SENT = new LongOpenHashSet(); // sections Skyrim holds a mesh for
	private static final LongOpenHashSet LIT = new LongOpenHashSet(); // sections Skyrim holds lights for
	private static final LongOpenHashSet SOLID = new LongOpenHashSet(); // sections Skyrim holds NPC collision for
	private static final long[] SOLID_BITS = new long[64]; // 4096 blocks: bit x + 16z + 256y
	private static final LongOpenHashSet DUG = new LongOpenHashSet(); // sections Skyrim holds dug cells for
	private static final ByteBuffer LIGHTS = ByteBuffer.allocate(16 * 16 * 16 * 8).order(ByteOrder.LITTLE_ENDIAN);
	private static int sentGeneration = Integer.MIN_VALUE;
	private static int meshesSent;
	private static boolean renderRingBlocked;
	private static ClientLevel sentLevel;
	private static SkyAtlas atlas;
	private static boolean atlasReady;
	private static boolean clearSent;
	private static boolean atlasStarted;
	private static int atlasRow;
	private static long focusSection = Long.MIN_VALUE;
	private static final MeshBuilder MESH = new MeshBuilder();
	private static final List<SkyLink.WorldEntity> ENTITIES = new ArrayList<>();
	private static final java.util.Map<Item, float[]> ICONS = new java.util.HashMap<>();
	private static final java.util.Map<BlockState, float[]> CUBE_FACES = new java.util.HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private WorldExporter() {
	}

	public static void markDirty(int sx, int sy, int sz) {
		SableRenderCompat.markDirty(sx, sy, sz);
		synchronized (DIRTY) {
			long key = SectionPos.asLong(sx, sy, sz);
			if (!URGENT.contains(key)) {
				// A chunk/dug column can arrive after the focus was visited, without camera movement.
				if (focusSection != Long.MIN_VALUE && Math.abs(sx - SectionPos.x(focusSection)) <= 2
					&& Math.abs(sy - SectionPos.y(focusSection)) <= 1 && Math.abs(sz - SectionPos.z(focusSection)) <= 2) {
					DIRTY.remove(key);
					URGENT.add(key);
				} else DIRTY.add(key);
			}
		}
	}

	/** A block the player just placed or broke: re-mesh ahead of everything else. */
	public static void markDirtyNow(int sx, int sy, int sz) {
		SableRenderCompat.markDirty(sx, sy, sz);
		synchronized (DIRTY) {
			long key = SectionPos.asLong(sx, sy, sz);
			DIRTY.remove(key);
			URGENT.add(key);
		}
	}

	/** Include neighboring sections whose face visibility/ambient occlusion changed. */
	public static void blockChanged(BlockPos pos) {
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		markDirtyNow(sx, sy, sz);
		for (int x = (pos.getX() - 1) >> 4; x <= (pos.getX() + 1) >> 4; x++)
			for (int y = (pos.getY() - 1) >> 4; y <= (pos.getY() + 1) >> 4; y++)
				for (int z = (pos.getZ() - 1) >> 4; z <= (pos.getZ() + 1) >> 4; z++)
					if (x != sx || y != sy || z != sz) markDirtyNow(x, y, z);
		// The edited cell takes precedence over older light/chunk rebuilds and its neighbors.
		synchronized (DIRTY) { URGENT.addAndMoveToFirst(SectionPos.asLong(sx, sy, sz)); }
	}

	public static void frame(Minecraft minecraft, float partialTick) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || !SkyLink.active()) {
			return;
		}
		if (sentGeneration != SkyLink.generation() || sentLevel != level || atlas == null || atlas.stale(minecraft)) {
			resendEverything(minecraft, level);
		}
		if (!atlasReady && !uploadAtlas()) return;
		SableRenderCompat.publishMotion(minecraft, atlas, partialTick);
		prioritizeNearby(minecraft, level);
		// Publish local block prediction before scene/texture traffic can fill the render ring.
		// Reconcile stale geometry along a mining miss without changing world blocks.
        if (minecraft.options.keyAttack.isDown() && minecraft.hitResult != null
                && minecraft.hitResult.getType() == HitResult.Type.MISS && level.getGameTime()%3 == 0) {
            var eye = minecraft.player.getEyePosition(partialTick);
            var look = minecraft.player.getLookAngle();
            for (int d = 0; d <= 5; ++d) {
                var point = eye.add(look.scale(d));
                int sx=(int)Math.floor(point.x)>>4, sy=(int)Math.floor(point.y)>>4, sz=(int)Math.floor(point.z)>>4;
                if (SENT.contains(SectionPos.asLong(sx,sy,sz))) markDirtyNow(sx,sy,sz);
            }
        }
        meshDirtySections(level, true);
		// Dynamic entities (including knockback) share the motion priority of moving plots.
		// Large ordinary chunk rebuilds must not take the ring space for this frame's scene.
		AvatarExporter.frame(minecraft, atlas, partialTick);
        // Selection/cracks reach the reader before ordinary section rebuild work.
        exportEntities(minecraft, level, partialTick);
		meshDirtySections(level, false);
		// Animated textures (water, lava, fire, ...): the frame for this game tick.
		atlas.animate(level.getGameTime(), region -> {
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(region.x()).putInt(region.y()).putInt(region.w()).putInt(region.h()).flip();
			return SkyLink.tryWriteRender(Proto.REN_ATLAS_REGION, header, region.pixels());
		});
	}

	private static void resendEverything(Minecraft minecraft, ClientLevel level) {
		sentGeneration = SkyLink.generation();
		sentLevel = level;
		// World/reconnect changes do not invalidate the resource atlas.
		if (atlas == null || atlas.stale(minecraft)) atlas = SkyAtlas.build(minecraft);
		SkyCraft.LOG.info("SkyCraft: {} animated textures (water, lava, fire, ...) will play in Skyrim", atlas.animatedSprites());
		AvatarExporter.reset();
		ICONS.clear();
		CUBE_FACES.clear();
		clearSent = false;
		atlasReady = false;
		atlasStarted = false;
		atlasRow = 0;
		SENT.clear();
		LIT.clear();
		SOLID.clear();
		DUG.clear();
		synchronized (DIRTY) {
			DIRTY.clear();
			URGENT.clear();
			focusSection = Long.MIN_VALUE;
		}
		dev.skycraft.client.SkyDigClient.resendAll();
		// Everything already loaded needs meshing again; later chunk loads mark themselves dirty.
		int radius = minecraft.options.getEffectiveRenderDistance() + 1;
		int pcx = SectionPos.blockToSectionCoord(minecraft.player.getBlockX()), pcz = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					if (!sections[i].hasOnlyAir()) {
						markDirty(cx, chunk.getSectionYFromSectionIndex(i), cz);
					}
				}
			}
		}
	}

	private static void meshDirtySections(ClientLevel level, boolean urgentOnly) {
		// Chunk loads and light updates dirty thousands of all-air sections; those cost a lookup.
		// Real meshing is limited per frame.
		long deadline = System.nanoTime() + 3_000_000L;
		int meshed = 0;
		while (meshed < SECTIONS_PER_FRAME && System.nanoTime() < deadline) {
			long key;
			boolean urgent;
			synchronized (DIRTY) {
				if (URGENT.isEmpty() && (urgentOnly || DIRTY.isEmpty())) {
					return;
				}
				urgent = !URGENT.isEmpty();
				key = urgent ? URGENT.removeFirstLong() : DIRTY.removeFirstLong();
			}
			renderRingBlocked = false;
			boolean sent = meshSection(level, key);
			if (sent) {
				meshed++;
			}
			synchronized (DIRTY) {
				if (urgent && DIRTY.remove(key)) URGENT.add(key);
			}
			// Empty/unloaded sections are cheap skips, not a full render ring.
            if (renderRingBlocked) {
                synchronized (DIRTY) {
                    DIRTY.remove(key);
                    if (urgent) URGENT.addAndMoveToFirst(key);
                    else DIRTY.addAndMoveToFirst(key);
                }
                return;
            }
		}
	}

	/** Load the player's immediate surroundings before distant background sections. */
	private static void prioritizeNearby(Minecraft minecraft, ClientLevel level) {
		// Player coordinates are already authoritative on the first frame after a respawn/teleport.
		int sx = SectionPos.blockToSectionCoord(minecraft.player.getBlockX());
		int sy = SectionPos.blockToSectionCoord(minecraft.player.getBlockY());
		int sz = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
		long focus = SectionPos.asLong(sx, sy, sz);
		synchronized (DIRTY) {
			if (focus == focusSection) return;
			focusSection = focus;
		}
		for (int radius = 0; radius <= 2; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(sx + dx, sz + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (int dy = -Math.min(1, radius); dy <= Math.min(1, radius); dy++) {
					if (Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy)) != radius) continue;
					long key = SectionPos.asLong(sx + dx, sy + dy, sz + dz);
					var section = sectionAt(level, chunk, sy + dy);
					synchronized (DIRTY) {
						if (DIRTY.contains(key) || (!SENT.contains(key) && section != null && !section.hasOnlyAir())
							|| (!DUG.contains(key) && hasDugCells(chunk, sy + dy))) {
							DIRTY.remove(key);
							URGENT.add(key);
						}
					}
				}
			}
		}
	}

	private static boolean hasDugCells(LevelChunk chunk, int sy) {
		long[] bits = dev.skycraft.client.SkyDigClient.dugBits(chunk, sy);
		if (bits != null) for (long word : bits) if (word != 0) return true;
		return false;
	}

	/** Retry initialization on later frames; never export untextured collision geometry. */
	private static boolean uploadAtlas() {
		if (!clearSent) {
			if (!SkyLink.tryWriteRender(Proto.REN_CLEAR_ALL, ByteBuffer.allocate(0), null)) return false;
			clearSent = true;
		}
		if (!atlasStarted) {
			ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
				.putInt(atlas.width).putInt(atlas.height).flip();
			if (!SkyLink.tryWriteRender(Proto.REN_ATLAS, header, null)) return false;
			atlasStarted = true;
		}
		// Two <=4 MiB stripes per frame keep startup work bounded, with every source pixel.
		int rowsPerStripe = Math.max(1, (4 << 20) / (atlas.width * 4));
		for (int stripe = 0; stripe < 2 && atlasRow < atlas.height; stripe++) {
			int rows = Math.min(rowsPerStripe, atlas.height - atlasRow);
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
				.putInt(0).putInt(atlasRow).putInt(atlas.width).putInt(rows).flip();
			ByteBuffer pixels = atlas.pixels.duplicate();
			pixels.position(atlasRow * atlas.width * 4).limit((atlasRow + rows) * atlas.width * 4);
			if (!SkyLink.tryWriteRender(Proto.REN_ATLAS_REGION, header, pixels)) return false;
			atlasRow += rows;
		}
		if (atlasRow < atlas.height) return false;
		if (!SkyLink.tryWriteRender(Proto.REN_ATLAS_READY, ByteBuffer.allocate(0), null)) return false;
		atlasReady = true;
		SkyCraft.LOG.info("SkyCraft: sent {}x{} texture atlas to Skyrim (ok; streamed at full resolution, {} bytes)",
			atlas.width, atlas.height, atlas.pixels.capacity());
		return true;
	}

	private static LevelChunkSection sectionAt(ClientLevel level, LevelChunk chunk, int sy) {
		int index = level.getSectionIndexFromSectionY(sy);
		return index >= 0 && index < chunk.getSections().length ? chunk.getSections()[index] : null;
	}

	/** Returns true if real meshing work was done. */
	private static boolean meshSection(ClientLevel level, long key) {
		int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) {
			return false; // unloaded: Skyrim keeps what it has
		}
		LevelChunkSection section = sectionAt(level, chunk, sy);
		boolean empty = section == null || section.hasOnlyAir();
		long[] dug = dev.skycraft.client.SkyDigClient.dugBits(chunk, sy);
		int dugCount = 0;
		if (dug != null) {
			for (long word : dug) {
				dugCount += Long.bitCount(word);
			}
		}
		if (empty && !SENT.contains(key) && !LIT.contains(key) && !SOLID.contains(key) && dugCount == 0 && !DUG.contains(key)) {
			return false; // nothing there and nothing to remove
		}
		MESH.reset();
		LIGHTS.clear();
		int lightCount = 0;
		java.util.Arrays.fill(SOLID_BITS, 0L);
		int solidCount = 0;
		BlockPos origin = SectionPos.of(sx, sy, sz).origin();
		PoseStack poseStack = new PoseStack();
		if (!empty) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
						BlockState state = chunk.getBlockState(pos);
						if (state.isAir()) {
							continue;
						}
						// Blocks Skyrim's NPCs can't walk through (anything with a collision shape).
						if (!state.getCollisionShape(level, pos).isEmpty()) {
							int bit = x + 16 * z + 256 * y;
							SOLID_BITS[bit >> 6] |= 1L << (bit & 63);
							solidCount++;
						}
						// Light-emitting blocks (torches, lava, glowstone, ...) light Skyrim's world too.
						int emission = state.getLightEmission();
						if (emission > 0) {
							LIGHTS.put((byte) x).put((byte) y).put((byte) z).put((byte) emission).putInt(BlockLightColors.of(state));
							lightCount++;
						}
						FluidState fluid = state.getFluidState();
						if (!fluid.isEmpty()) {
							// Skyrim ground in the cell: the fluid is drawn in the space above it.
							MESH.fluidGround = dev.skycraft.world.SkyCollision.groundTop(pos);
							MESH.fluidBaseY = y;
							MESH.noMipmaps = false;
							MESH.surfaceOverlay = false;
							MESH.poweredDust = false;
							MESH.fluidTranslucent = ItemBlockRenderTypes.getRenderLayer(fluid) == RenderType.translucent();
							// The 1.20.1 liquid renderer already emits section-relative coordinates.
							Minecraft.getInstance().getBlockRenderer().renderLiquid(pos, level, MESH, state, fluid);
							MESH.flush();
							MESH.fluidGround = 0.0F;
						}
						if (state.getRenderShape() == RenderShape.MODEL) {
							MESH.poweredDust = state.getBlock() instanceof net.minecraft.world.level.block.RedStoneWireBlock
								&& state.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER) > 0;
							var cuts = dev.skycraft.world.SkyDig.clientDug;
							MESH.surfaceOverlay = state.getBlock() instanceof net.minecraft.world.level.block.RedStoneWireBlock
								|| (cuts != null && cuts.isDug(pos.getX(), pos.getY(), pos.getZ()));
                            var renderer = Minecraft.getInstance().getBlockRenderer();
                            var model = renderer.getBlockModel(state);
                            var data = level.getModelDataManager().getAt(pos);
                            if (data == null) data = net.minecraftforge.client.model.data.ModelData.EMPTY;
                            data = model.getModelData(level, pos, state, data);
                            RANDOM.setSeed(state.getSeed(pos));
                            poseStack.pushPose();
                            try {
                                poseStack.translate(x, y, z);
                                for (var layer : model.getRenderTypes(state, RANDOM, data)) {
                                    MESH.fluidTranslucent = layer == RenderType.translucent();
                                    MESH.noMipmaps = layer == RenderType.cutout();
                                    RANDOM.setSeed(state.getSeed(pos));
                                    renderer.renderBatched(state, pos, level, poseStack, MESH, true, RANDOM, data, layer);
                                    MESH.flush();
                                }
                            } finally { poseStack.popPose(); }
						}
					}
				}
			}
		}
		// Holes dug into Skyrim's ground: Minecraft walls where its surface still runs above them.
		var digLookup = dev.skycraft.world.SkyDig.clientDug;
		if (dugCount > 0 && digLookup != null) {
			Minecraft minecraft = Minecraft.getInstance();
			DigWalls.add(level, sx, sy, sz, dug, digLookup, st -> cubeFaces(minecraft, st), MESH::wall);
		}
		if (MESH.vertexCount() == 0 && !SENT.contains(key) && lightCount == 0 && !LIT.contains(key) && solidCount == 0 && !SOLID.contains(key) && dugCount == 0
			&& !DUG.contains(key)) {
			return true;
		}
		ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(MESH.vertexCount()).flip();
		if (SkyLink.tryWriteRender(Proto.REN_SECTION, header, MESH.bytes())) {
			if (MESH.vertexCount() > 0) {
				SENT.add(key);
			} else {
				SENT.remove(key);
			}
			if (lightCount > 0 || LIT.contains(key)) {
				ByteBuffer lightHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(lightCount).flip();
				if (!SkyLink.tryWriteRender(Proto.REN_LIGHTS, lightHeader, LIGHTS.flip())) {
					markDirty(sx, sy, sz); // ring full; send both again later
				} else if (lightCount > 0) {
					LIT.add(key);
				} else {
					LIT.remove(key);
				}
			}
			if (solidCount > 0 || SOLID.contains(key)) {
				ByteBuffer solidHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(solidCount).flip();
				ByteBuffer bits = ByteBuffer.allocate(solidCount > 0 ? 512 : 0).order(ByteOrder.LITTLE_ENDIAN);
				if (solidCount > 0) {
					for (long word : SOLID_BITS) {
						bits.putLong(word);
					}
				}
				if (!SkyLink.tryWriteRender(Proto.REN_SOLIDS, solidHeader, bits.flip())) {
					markDirty(sx, sy, sz);
				} else if (solidCount > 0) {
					SOLID.add(key);
				} else {
					SOLID.remove(key);
				}
			}
			// Cells dug out of Skyrim's world: its geometry there goes.
			if (dugCount > 0 || DUG.contains(key)) {
				ByteBuffer dugHeader = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(dugCount)
					.putInt(dev.skycraft.client.SkyDigClient.world()).putInt(0).flip();
				ByteBuffer bits = ByteBuffer.allocate(dugCount > 0 ? 512 : 0).order(ByteOrder.LITTLE_ENDIAN);
				if (dugCount > 0) {
					for (long word : dug) {
						bits.putLong(word);
					}
				}
				if (!SkyLink.tryWriteRender(Proto.REN_DUG, dugHeader, bits.flip())) {
					markDirty(sx, sy, sz);
				} else if (dugCount > 0) {
					DUG.add(key);
				} else {
					DUG.remove(key);
				}
			}
			if (++meshesSent <= 10 || meshesSent % 200 == 0) {
				SkyCraft.LOG.info("SkyCraft: block mesh for section {} {} {}: {} vertices ({} sections in Skyrim)", sx, sy, sz, MESH.vertexCount(), SENT.size());
			}
		} else {
			renderRingBlocked = true;
			markDirty(sx, sy, sz); // ring full; preserve edit priority for retry
		}
		return true;
	}

	private static void exportEntities(Minecraft minecraft, ClientLevel level, float partialTick) {
		ENTITIES.clear();
		Vec3 eye = minecraft.player.getEyePosition(partialTick);
		for (Entity e : level.entitiesForRendering()) {
			if (ENTITIES.size() >= Proto.MAX_WORLD_ENTITIES) break;
			// Retained Sable arrows use remote plot coordinates: cull after conversion.
			if (!(e instanceof AbstractArrow) && e.distanceToSqr(eye) > ENTITY_RANGE * ENTITY_RANGE) {
				continue;
			}
			// Vanilla hides rockets attached to an Elytra user; retain that visibility rule.
			if (e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity
				&& !e.shouldRenderAtSqrDistance(e.distanceToSqr(eye))) continue;
			Vec3 p = e.getPosition(partialTick);
			if (e instanceof AbstractArrow arrow) {
				boolean trident = arrow instanceof ThrownTrident;
				int kind = trident ? Proto.WE_TRIDENT : Proto.WE_ARROW;
				float yaw = Mth.rotLerp(partialTick, arrow.yRotO, arrow.getYRot());
				float pitch = Mth.lerp(partialTick, arrow.xRotO, arrow.getXRot());
				var attached = SableRenderCompat.arrowPose(arrow, p, yaw, pitch, partialTick);
				p = attached.position();
				yaw = attached.yaw();
				pitch = attached.pitch();
                Vec3 surface = ((dev.skycraft.world.ArrowSurfaceAttachment)arrow).skycraft$skyrimSurface();
                boolean nativeSurface = !trident && !attached.attached() && surface != null;
                if (nativeSurface) p = surface;
				if (p.distanceToSqr(eye) > ENTITY_RANGE * ENTITY_RANGE) continue;
				// Arrows use Minecraft's arrow model and entity texture; tridents their item icon.
				float[] uv = trident ? ICONS.computeIfAbsent(Items.TRIDENT, i -> iconUv(minecraft, level, new ItemStack(i)))
					: atlas.arrowUv(arrow instanceof SpectralArrow ? 2 : arrow instanceof Arrow tippable && tippable.getColor() > 0 ? 1 : 0);
				if (uv != null) {
					ENTITIES.add(new SkyLink.WorldEntity(kind, e.getId(), (float) p.x, (float) p.y, (float) p.z, yaw, pitch, 1.0F, new float[]{attached.attached() ? 1 : 0, nativeSurface ? 1 : 0, 0}, uv, 0));
				}
			} else if (e instanceof ItemSupplier supplier) {
				addItem(minecraft, level, e, supplier.getItem(), p.add(0, e.getBbHeight() * 0.5 - 0.25, 0), 0.0F);
			} else if (e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof dev.skycraft.combat.SkyrimActorEntity) && !e.isInvisible()
				&& (e != minecraft.player || minecraft.gameRenderer.getMainCamera().isDetached())) {
				// Players and mobs: Skyrim darkens the ground softly under their feet.
				ENTITIES.add(new SkyLink.WorldEntity(Proto.WE_SHADOW, e.getId(), (float) p.x, (float) p.y, (float) p.z, 0.0F, 0.0F, e.getBbWidth(), null, null, 0));
			}
		}
		// Crack geometry is exported in the scene, including moving sublevel transforms.
		SkyLink.writeWorldEntities(ENTITIES, selection(minecraft, level));
	}

	/**
	 * A dropped item the way Minecraft shows it: blocks as small spinning cubes with their own face
	 * textures, everything else as its icon. {@code p} is the item's resting point (bottom).
	 */
	private static void addItem(Minecraft minecraft, ClientLevel level, Entity e, ItemStack stack, Vec3 p, float yaw) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			BlockState state = blockItem.getBlock().defaultBlockState();
			if (state.getRenderShape() == RenderShape.MODEL
				&& Block.isShapeFullBlock(state.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
				float[] faces = cubeFaces(minecraft, state);
				if (faces != null) {
					float size = 0.25F;
					int tint = cubeTint(minecraft, state);
					ENTITIES.add(new SkyLink.WorldEntity(
						Proto.WE_BLOCK, e.getId(), (float) p.x, (float) (p.y + size * 0.5 + 0.02), (float) p.z, yaw, 0.0F, size, null, faces, tint
					));
					return;
				}
			}
		}
		float[] uv = iconUv(minecraft, level, stack);
		if (uv != null) {
			ENTITIES.add(new SkyLink.WorldEntity(Proto.WE_ITEM, e.getId(), (float) p.x, (float) p.y + 0.25F, (float) p.z, yaw, 0.0F, 0.5F, null, uv, 0));
		}
	}

	/** Side, top and bottom atlas rects of a full-cube block's model, cached per block state. */
	private static float[] cubeFaces(Minecraft minecraft, BlockState state) {
		return CUBE_FACES.computeIfAbsent(state, s -> {
			var model = minecraft.getBlockRenderer().getBlockModel(s);
			float[] out = new float[12];
			Direction[] dirs = { Direction.NORTH, Direction.UP, Direction.DOWN };
			for (int f = 0; f < 3; f++) {
				TextureAtlasSprite sprite = null;
				RANDOM.setSeed(42);
				var quads = model.getQuads(s, dirs[f], RANDOM);
				if (!quads.isEmpty()) {
					sprite = quads.get(0).getSprite();
				}
				if (sprite == null) {
					return null;
				}
				System.arraycopy(atlas.rect(sprite), 0, out, f * 4, 4);
			}
			return out;
		});
	}

	/** Grass and leaves are grey in the atlas; Minecraft tints them. RGBA8, 0 for none. */
	private static int cubeTint(Minecraft minecraft, BlockState state) {
		if (minecraft.level == null) {
			return 0;
		}
		int argb = minecraft.getBlockColors().getColor(state, minecraft.level, BlockPos.ZERO, 0);
		if (argb < 0) return 0;
		return 0xFF000000 | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
	}

	/** Cracks over blocks being mined (ours and anyone else's). */
	private static void addCracks(ClientLevel level) {
		for (BlockDestructionProgress progress : ((dev.skycraft.client.mixin.LevelRendererAccessor) Minecraft.getInstance().levelRenderer).skycraft$destroyingBlocks().values()) {
			int stage = progress.getProgress();
			if (stage < 0 || stage > 9 || ENTITIES.size() >= Proto.MAX_WORLD_ENTITIES) {
				continue;
			}
			BlockPos pos = progress.getPos();
			VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
			if (shape.isEmpty()) {
				continue;
			}
			AABB box = shape.bounds().move(pos).inflate(0.004);
			ENTITIES.add(new SkyLink.WorldEntity(
				Proto.WE_CRACK, pos.hashCode(), (float) box.minX, (float) box.minY, (float) box.minZ, 0.0F, 0.0F, 1.0F,
				new float[] { (float) box.getXsize(), (float) box.getYsize(), (float) box.getZsize() }, atlas.crackUv(stage), 0
			));
		}
	}

	/** The item's icon in the combined atlas {u0, v0, u1, v1}, or null. */
	private static float[] iconUv(Minecraft minecraft, ClientLevel level, ItemStack stack) {
		return atlas.rect(minecraft.getItemRenderer().getModel(stack, level, null, 0).getParticleIcon());
	}

	/** Outline of a Minecraft block, the placement cell, or the Skyrim surface cell being mined. */
	private static float[] selection(Minecraft minecraft, ClientLevel level) {
		HitResult hit = minecraft.hitResult;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK || minecraft.screen != null) {
			return null;
		}
		BlockPos pos = blockHit.getBlockPos();
		if (blockHit instanceof SkyClip.SkyrimHitResult skyrimHit) {
			if (!(minecraft.player.getMainHandItem().getItem() instanceof BlockItem)) {
				// Picking reports a placement cell outside the surface; mining opens the cell inside it.
				pos = dev.skycraft.client.SkyDigClient.target(minecraft, skyrimHit);
				if (pos == null) return null;
			}
			return new float[] { pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1 };
		}
		VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
		if (shape.isEmpty()) {
			return null;
		}
		AABB box = shape.bounds().move(pos);
		return new float[] { (float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.maxY, (float) box.maxZ };
	}

	/**
	 * Collects Minecraft's block quads (and fluid vertices) as triangles in the RenVertex layout:
	 * section-relative position, combined-atlas UV, RGBA colour (tint and shading), block/sky light.
	 */
	private static final class MeshBuilder implements VertexConsumer {
		private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
		private int vertices;
		private boolean fluidTranslucent;
		private boolean noMipmaps;
		private boolean surfaceOverlay;
		private boolean poweredDust;
		private final float[] pending = new float[4 * 13];
		private int pendingVertices;
		private int currentVertex = -1;
		private int defaultColor = -1;

		void reset() {
			this.buf.clear();
			this.vertices = 0;
			this.pendingVertices = 0;
			this.currentVertex = -1;
		}

		int vertexCount() {
			return this.vertices;
		}

		ByteBuffer bytes() {
			return this.buf.duplicate().flip();
		}

		private void ensure(int bytes) {
			if (this.buf.remaining() < bytes) {
				ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(this.buf.capacity() * 2, this.buf.position() + bytes)).order(ByteOrder.LITTLE_ENDIAN);
				this.buf.flip();
				bigger.put(this.buf);
				this.buf = bigger;
			}
		}

		private void vertex(float x, float y, float z, float u, float v, int argb, int light, int flags) {
			this.buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
			this.buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
			int block = (light >> 4) & 0xF;
			int sky = (light >> 20) & 0xF;
			this.buf.putInt(block | (sky << 8));
			this.buf.putInt(flags);
			this.vertices++;
		}

		/**
		 * Vertex flags: cutout or translucent, plus the face normal (Direction ordinal + 1) that
		 * Skyrim lights the face with. 0 leaves it without a normal (plants and other quads Minecraft
		 * doesn't shade by direction).
		 */
		private static int flags(boolean translucent, Direction normal) {
			return (translucent ? 2 : 1) | (normal == null ? 0 : (normal.ordinal() + 1) << 4);
		}

		/** Takes Minecraft's fixed face brightness back out of a colour, leaving tint and ambient occlusion. */
		private static int unshade(int argb, float shade) {
			if (shade >= 0.999F || shade <= 0.0F) {
				return argb;
			}
			int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) / shade));
			int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) / shade));
			int b = Math.min(255, Math.round((argb & 0xFF) / shade));
			return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
		}

		/** A dug hole's wall (DigWalls): an untinted opaque quad. */
		void wall(float[] xyz, float[] uv, int light, Direction normal) {
		this.flush();
		this.ensure(6 * Proto.REN_VERTEX_BYTES);
			int flags = flags(false, normal) | 128;
			for (int k : QUAD_TRIANGLES) {
				this.vertex(xyz[k * 3], xyz[k * 3 + 1], xyz[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], 0xFFFFFFFF, light, flags);
			}
		}

		// Skyrim ground's height in the fluid's cell (0..1) and the cell's section-relative y: a
		// Minecraft fluid level counts from the cell's floor, so on Skyrim ground partway up the cell
		// the fluid is squeezed into the space above it (thin edges stay visible on the ground).
		float fluidGround;
		int fluidBaseY;

		@Override
		public VertexConsumer vertex(double px, double py, double pz) {
			float x = (float) px, y = (float) py, z = (float) pz;
			if (this.pendingVertices == 4) this.flush();
			this.currentVertex = this.pendingVertices++;
			int o = this.currentVertex * 13;
			if (this.fluidGround > 0.0F) {
				float t = Math.max(0.0F, Math.min(1.0F, y - this.fluidBaseY));
				y = this.fluidBaseY + this.fluidGround + t * (1.0F - this.fluidGround);
			}
			this.pending[o] = x;
			this.pending[o + 1] = y;
			this.pending[o + 2] = z;
			this.pending[o + 5] = (this.defaultColor >> 16) & 255;
			this.pending[o + 6] = (this.defaultColor >> 8) & 255;
			this.pending[o + 7] = this.defaultColor & 255;
			this.pending[o + 8] = (this.defaultColor >>> 24) & 255;
			return this;
		}

		@Override public void endVertex() { this.currentVertex = -1; }
		@Override public void defaultColor(int r, int g, int b, int a) { this.defaultColor = (a << 24) | (r << 16) | (g << 8) | b; }
		@Override public void unsetDefaultColor() { this.defaultColor = -1; }

		@Override
		public VertexConsumer color(int r, int g, int b, int a) {
			if (this.currentVertex >= 0) {
				int o = this.currentVertex * 13;
				this.pending[o + 5] = r;
				this.pending[o + 6] = g;
				this.pending[o + 7] = b;
				this.pending[o + 8] = a;
			}
			return this;
		}

		@Override
		public VertexConsumer uv(float u, float v) {
			if (this.currentVertex >= 0) {
				int o = this.currentVertex * 13;
				this.pending[o + 3] = atlas.blockU(u);
				this.pending[o + 4] = atlas.blockV(v);
			}
			return this;
		}

		@Override
		public VertexConsumer overlayCoords(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer uv2(int u, int v) {
			if (this.currentVertex >= 0) this.pending[this.currentVertex * 13 + 9] = u | (v << 16);
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			if (this.currentVertex >= 0) {
				int o = this.currentVertex * 13;
				this.pending[o + 10] = x;
				this.pending[o + 11] = y;
				this.pending[o + 12] = z;
			}
			return this;
		}

		private void flush() {
			if (this.pendingVertices != 4) return;
			this.ensure(6 * Proto.REN_VERTEX_BYTES);
			int[] order = QUAD_TRIANGLES;
			for (int vertex : order) {
				int o = vertex * 13;
				float nx = this.pending[o + 10], ny = this.pending[o + 11], nz = this.pending[o + 12];
				Direction normal = nx == 0 && ny == 0 && nz == 0 ? null : Direction.getNearest(nx, ny, nz);
				float shade = normal == null ? 1.0F : switch (normal) {
					case DOWN -> 0.5F;
					case NORTH, SOUTH -> 0.8F;
					case WEST, EAST -> 0.6F;
					case UP -> 1.0F;
				};
				int color = ((int) this.pending[o + 8] << 24) | ((int) this.pending[o + 5] << 16) |
					((int) this.pending[o + 6] << 8) | (int) this.pending[o + 7];
				int light = (int) this.pending[o + 9];
				this.vertex(this.pending[o], this.pending[o + 1], this.pending[o + 2], this.pending[o + 3], this.pending[o + 4],
					unshade(color, shade), light, flags(this.fluidTranslucent, normal) | (this.noMipmaps ? 8 : 0) | (this.surfaceOverlay ? 128 : 0) | (this.poweredDust ? 256 : 0));
			}
			this.pendingVertices = 0;
			this.currentVertex = -1;
		}
	}
}
