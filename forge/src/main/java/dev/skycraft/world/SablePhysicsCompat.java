package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.link.Proto;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Optional Sable/Rapier terrain adapter. Does not add blocks to Minecraft chunks or saves. */
public final class SablePhysicsCompat {
	private static boolean resolved, available, warned;
	private static Method createCollider, addBox, colliderHandle, addSection, sceneHandle, addChunk, removeChunk;
	private static Method bodyBounds, boundsToAabb, bodyVelocity;
	private static Field activeBodies, activeContraptions;
	private static boolean bodyWindowAvailable;
	// Native collider entries are global for the process. Reuse identical geometry across scenes.
	private static final Map<List<AABB>, Integer> COLLIDERS = new HashMap<>();
	private static final Map<Object, State> STATES = new WeakHashMap<>();
	private static final Map<ServerLevel, TerrainCache> TERRAIN = new WeakHashMap<>();
	private static int logs;
	private static int dedupLogs;
	private static int cacheLogs;
	private static long rebuiltCells, reusedCells;
	private record CellInput(List<SkyTri> tris,List<AABB> fallback,List<AABB> wall) {
		boolean same(CellInput other) {
			if(other==null||!fallback.equals(other.fallback)||!wall.equals(other.wall)||tris.size()!=other.tris.size()) return false;
			for(int i=0;i<tris.size();i++) {
				SkyTri a=tris.get(i),b=other.tris.get(i);
				if(a.ax!=b.ax||a.ay!=b.ay||a.az!=b.az||a.bx!=b.bx||a.by!=b.by||a.bz!=b.bz
					||a.cx!=b.cx||a.cy!=b.cy||a.cz!=b.cz||a.terrain!=b.terrain||a.diggable!=b.diggable
					||a.stairHelper!=b.stairHelper||a.material!=b.material) return false;
			} return true;
		}
	}
	private static final class TerrainSection {
		final long generation;
		final long revision;
		final int[] cells = new int[4096];
		final java.util.BitSet candidates = new java.util.BitSet(4096);
		final CellInput[] inputs = new CellInput[4096];
		TerrainSection previous;
		boolean prepared;
		TerrainSection(long generation,long revision,TerrainSection previous) {
			this.generation=generation; this.revision=revision;
			this.previous=previous!=null&&previous.generation==generation?previous:null;
		}
	}
	private static final class TerrainCache {
		long generation = -1;
		final Map<Long,TerrainSection> sections = new java.util.LinkedHashMap<>(256,0.75f,true) {
			@Override protected boolean removeEldestEntry(Map.Entry<Long,TerrainSection> eldest) { return size()>256; }
		};
	}
	private static TerrainSection terrainSection(State state,ServerLevel level,int sx,int sy,int sz) {
		long generation=SkyCollision.physicsGeneration(), key=SectionPos.asLong(sx,sy,sz);
		long revision=SkyCollision.physicsSections().getOrDefault(key,0L);
		TerrainSection retained=state.retained.get(key);
		if(retained!=null&&retained.generation==generation&&retained.revision==revision) return retained;
		synchronized(TERRAIN) {
			TerrainCache cache=TERRAIN.computeIfAbsent(level,ignored->new TerrainCache());
			if(cache.generation!=generation) { cache.sections.clear(); cache.generation=generation; }
			TerrainSection section=cache.sections.get(key);
			if(section==null||section.revision!=revision) {
				section=new TerrainSection(generation,revision,retained!=null?retained:section); cache.sections.put(key,section);
			}
			state.retained.put(key,section);
			return section;
		}
	}

	private static final class State {
		long generation = -1;
		boolean initialized, untrackedBodies;
		final Map<Long, Long> applied = new HashMap<>();
		final Map<Long, int[]> worldBlocks = new HashMap<>();
		final Map<Long, int[]> published = new HashMap<>();
		final Map<Long, TerrainSection> retained = new HashMap<>();
		final Set<Long> required = new HashSet<>(), nativeOnly = new HashSet<>();
		final Vector3d velocity = new Vector3d();
		final ByteBuffer interest = ByteBuffer.allocateDirect(4+64*36).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static State state(Object pipeline) {
		synchronized(STATES) { return STATES.computeIfAbsent(pipeline, ignored -> new State()); }
	}

	private SablePhysicsCompat() {}

	private static boolean resolve() {
		if (resolved) return available;
		resolved = true;
		try {
			Class<?> rapier = Class.forName("dev.ryanhcode.sable.physics.impl.rapier.Rapier3D");
			Class<?> data = Class.forName("dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData");
			Class<?> callback = Class.forName("dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback");
			Class<?> pipeline = Class.forName("dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline");
			createCollider = rapier.getMethod("createVoxelColliderEntry", double.class, double.class, double.class, boolean.class, callback);
			addBox = data.getMethod("addBox", Vector3dc.class, Vector3dc.class);
			colliderHandle = data.getMethod("handle");
			addSection = pipeline.getMethod("handleChunkSectionAddition", net.minecraft.world.level.chunk.LevelChunkSection.class,
				int.class, int.class, int.class, boolean.class);
			sceneHandle = pipeline.getDeclaredMethod("getSceneHandle"); sceneHandle.setAccessible(true);
			// Rapier's native terrain entry points are package-private in Sable 2.0.6.
			addChunk = rapier.getDeclaredMethod("addChunk", long.class,int.class,int.class,int.class,int[].class,boolean.class,int.class);
			addChunk.setAccessible(true);
			removeChunk = rapier.getDeclaredMethod("removeChunk", long.class,int.class,int.class,int.class,boolean.class);
			removeChunk.setAccessible(true);
			try {
				activeBodies = pipeline.getDeclaredField("activeSubLevels"); activeBodies.setAccessible(true);
				activeContraptions = pipeline.getDeclaredField("activeContraptions"); activeContraptions.setAccessible(true);
				bodyBounds = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel").getMethod("boundingBox");
				boundsToAabb = bodyBounds.getReturnType().getMethod("toMojang");
				bodyVelocity = pipeline.getMethod("getLinearVelocity", Class.forName("dev.ryanhcode.sable.api.physics.PhysicsPipelineBody"),Vector3d.class);
				bodyWindowAvailable = true;
			} catch(ReflectiveOperationException error) {
				// Unknown optional versions retain all received collision coverage.
				SkyCraft.LOG.info("SkyCraft: Sable body bounds unavailable; retaining complete terrain coverage");
			}
			available = true;
			SkyCraft.LOG.info("SkyCraft: optional Sable/Rapier Skyrim terrain collision ready");
		} catch (ClassNotFoundException ignored) {
			// Neither Sable nor its backend is required in ordinary modpacks.
		} catch (ReflectiveOperationException | LinkageError error) { warn(error); }
		return available;
	}

	private static boolean linked(ServerLevel level) {
		// Only the local mirror can share the native collision snapshot. Other dimensions stay ordinary.
		return SkyLink.active() && SkyCollision.active() && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
			&& level.getServer().isSingleplayer();
	}

	/** Same x + (z << 4) + (y << 8) order and handle packing as RapierPhysicsPipeline. */
	public static boolean merge(Object pipeline, ServerLevel level, int sx, int sy, int sz, int[] blocks) {
		if (!linked(level) || blocks.length != 4096 || !resolve()) return true;
		State state=state(pipeline);
		long key=SectionPos.asLong(sx,sy,sz);
		// prePhysicsTicks publishes every required section before the first real solver step.
		if(!state.initialized||!state.required.contains(key)) return true;
		try {
			state.worldBlocks.put(key,blocks.clone());
			TerrainSection cache=terrainSection(state,level,sx,sy,sz);
			prepare(cache,level,sx,sy,sz);
			var chunk=level.getChunkSource().getChunkNow(sx,sz);
			if(chunk==null) return true;
			var section=chunk.getSections()[level.getSectionIndexFromSectionY(sy)];
			int count = 0;
			for (int index=cache.candidates.nextSetBit(0);index>=0;index=cache.candidates.nextSetBit(index+1)) {
				int x=index&15,z=(index>>>4)&15,y=index>>>8;
				// Minecraft blocks keep their own collider and callbacks. Cache only the
				// immutable native contribution, so breaking a real block cannot stale it.
				if(!section.getBlockState(x,y,z).isAir()) continue;
				int nativeBlock=cache.cells[index];
				int merged = nativeBlock==0 ? blocks[index] : nativeBlock;
				if (merged != blocks[index]) { blocks[index] = merged; count++; }
			}
			state.nativeOnly.remove(key);
			state.applied.put(key,cache.revision);
			if (count > 0 && logs++ < 12) SkyCraft.LOG.info("SkyCraft: Sable terrain section {} merged ({} Skyrim collision cells)",
				SectionPos.of(sx, sy, sz), count);
			return !java.util.Arrays.equals(state.published.get(key),blocks);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); }
		return true;
	}

	/** Only remember successful backend uploads; failed native calls must remain retryable. */
	public static void uploaded(Object pipeline,int sx,int sy,int sz,int[] blocks) {
		State state=state(pipeline); long key=SectionPos.asLong(sx,sy,sz);
		if(state.required.contains(key)) state.published.put(key,blocks.clone());
	}
	private static int rememberBlock(State state,long key,int index,int value) {
		int[] published=state.published.get(key); if(published!=null) published[index]=value;
		return value;
	}

	public static int mergeBlock(Object pipeline, ServerLevel level, int x, int y, int z, int original) {
		State state=state(pipeline); long key=SectionPos.asLong(x>>4,y>>4,z>>4);
		int[] ordinary=state.worldBlocks.get(key);
		int index=(x&15)|((z&15)<<4)|((y&15)<<8);
		if(ordinary!=null) ordinary[index]=original;
		if(!linked(level)||!resolve()||!SkyCollision.isKnown(x,y,z)||!state.required.contains(key)) return rememberBlock(state,key,index,original);
		try {
            var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
            if(chunk==null||!chunk.getSections()[level.getSectionIndexFromSectionY(y>>4)].getBlockState(x&15,y&15,z&15).isAir()) return rememberBlock(state,key,index,original);
            TerrainSection cache=terrainSection(state,level,x>>4,y>>4,z>>4);
            prepare(cache,level,x>>4,y>>4,z>>4);
            int nativeBlock=cache.cells[index];
            return rememberBlock(state,key,index,nativeBlock==0?original:nativeBlock);
        }
		catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); return original; }
	}

	private static Map<Integer, List<SkyTri>> sectionSurfaces(int sx,int sy,int sz,SkyDig.DugLookup dug) {
        var area = new AABB(sx*16.0,sy*16.0,sz*16.0,(sx+1)*16.0,(sy+1)*16.0,(sz+1)*16.0).inflate(1e-5);
        var gathered = new java.util.ArrayList<SkyTri>(); SkyCollision.trianglesNear(area,gathered);
        List<SkyTri> tris = PhysicsSurfaceBoxes.distinct(gathered);
        if (tris.size() != gathered.size() && dedupLogs++ < 6)
            SkyCraft.LOG.info("SkyCraft: physics section {} {} {} reuses {} distinct surfaces from {} regional copies", sx,sy,sz,tris.size(),gathered.size());
        if (dug != null) tris = SkyTriCut.withoutDug(tris,dug,area);
        Map<Integer,List<SkyTri>> cells = new HashMap<>();
        for(var tri:tris) {
            int x0=Math.max(0,(int)Math.floor(tri.minX)-sx*16),x1=Math.min(15,(int)Math.floor(tri.maxX)-sx*16);
            int y0=Math.max(0,(int)Math.floor(tri.minY)-sy*16),y1=Math.min(15,(int)Math.floor(tri.maxY)-sy*16);
            int z0=Math.max(0,(int)Math.floor(tri.minZ)-sz*16),z1=Math.min(15,(int)Math.floor(tri.maxZ)-sz*16);
            for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) for(int x=x0;x<=x1;x++)
                cells.computeIfAbsent(x|(z<<4)|(y<<8),ignored->new java.util.ArrayList<>()).add(tri);
        }
        return cells;
    }

	private static void prepare(TerrainSection cache,ServerLevel level,int sx,int sy,int sz) throws ReflectiveOperationException {
		if(cache.prepared) return;
		boolean walls=SkyDig.hasDugNear(level,new AABB(sx*16.0,sy*16.0,sz*16.0,(sx+1)*16.0,(sy+1)*16.0,(sz+1)*16.0).inflate(1));
		SkyDig.DugLookup dug=walls?SkyDig.lookup(level):null;
		Map<Integer,List<SkyTri>> surfaces=sectionSurfaces(sx,sy,sz,dug);
		var pos=new BlockPos.MutableBlockPos(); int rebuilt=0,reused=0;
		for(int index=0;index<4096;index++) {
			int x=index&15,z=(index>>>4)&15,y=index>>>8;
			pos.set((sx<<4)+x,(sy<<4)+y,(sz<<4)+z);
			if(dug!=null&&dug.isDug(pos.getX(),pos.getY(),pos.getZ())) continue;
			List<SkyTri> tris=surfaces.getOrDefault(index,List.of());
			// An exact snapshot includes empty air. Coarse padding cannot replace it.
			VoxelShape fallback=tris.isEmpty()&&!SkyCollision.hasTrianglesAt(pos)?SkyCollision.shapeAt(pos):null;
			VoxelShape wall=walls?SkyDig.wallShape(level,pos):null;
			if(tris.isEmpty()&&fallback==null&&wall==null) continue;
			CellInput input=new CellInput(tris,fallback==null?List.of():fallback.toAabbs(),wall==null?List.of():wall.toAabbs());
			cache.inputs[index]=input; int nativeBlock;
			if(cache.previous!=null&&input.same(cache.previous.inputs[index])) {
				nativeBlock=cache.previous.cells[index]; reused++;
			} else { nativeBlock=terrainBlock(pos,input); rebuilt++; }
			cache.cells[index]=nativeBlock;
			if(nativeBlock!=0) cache.candidates.set(index);
		}
		cache.prepared=true; cache.previous=null; rebuiltCells+=rebuilt; reusedCells+=reused;
		if(cacheLogs++<8) SkyCraft.LOG.info("SkyCraft: physics section {} {} {} rebuilt {} cells, reused {}",sx,sy,sz,rebuilt,reused);
	}

    private static int terrainBlock(BlockPos pos,CellInput input) throws ReflectiveOperationException {
        int original=0;
        var boxes = new java.util.ArrayList<AABB>();
        if (!input.tris.isEmpty()) {
            for(var b:PhysicsSurfaceBoxes.build(input.tris,pos.getX(),pos.getY(),pos.getZ()))
                boxes.add(new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()));
        } else boxes.addAll(input.fallback);
        boxes.addAll(input.wall);
        if(boxes.isEmpty()) return original;
        boxes.sort(java.util.Comparator.comparingDouble((AABB b)->b.minX).thenComparingDouble(b->b.minY).thenComparingDouble(b->b.minZ)
            .thenComparingDouble(b->b.maxX).thenComparingDouble(b->b.maxY).thenComparingDouble(b->b.maxZ));
        // Rapier's packed entries have 16-bit handles shared with ordinary mods.
        // Reserve room for them; the bounded old vocabulary remains a fallback.
        if (COLLIDERS.size() >= 48_000) {
            double minX=1,minY=1,minZ=1,maxX=0,maxY=0,maxZ=0;
            for(var b:boxes) { minX=Math.min(minX,b.minX); minY=Math.min(minY,b.minY); minZ=Math.min(minZ,b.minZ); maxX=Math.max(maxX,b.maxX); maxY=Math.max(maxY,b.maxY); maxZ=Math.max(maxZ,b.maxZ); }
            boxes.clear(); boxes.add(new AABB(Math.floor(minX*4)/4,Math.floor(minY*4)/4,Math.floor(minZ*4)/4,
                Math.ceil(maxX*4)/4,Math.ceil(maxY*4)/4,Math.ceil(maxZ*4)/4));
        }
        List<AABB> key = List.copyOf(boxes);

		int handle;
		synchronized (COLLIDERS) {
			Integer cached = COLLIDERS.get(key);
			if (cached != null) handle = cached;
			else {
				// Friction, volume, restitution, liquid, callback; terrain is stationary and non-bouncy.
				Object data = createCollider.invoke(null, 0.8, 1.0, 0.0, false, null);
				for (AABB box : boxes) addBox.invoke(data, new Vector3d(box.minX, box.minY, box.minZ), new Vector3d(box.maxX, box.maxY, box.maxZ));
				handle = ((Number) colliderHandle.invoke(data)).intValue() + 1;
				if (handle < 1 || handle > 0xFFFF) {
                    COLLIDERS.put(key, 0);
                    if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: Sable collider pool is full; preserving original collider for unsupported shapes"); }
                    return original;
                }
				COLLIDERS.put(key, handle);
			}
		}
		// CORNER (3) is Sable's representation for partial shapes, exposing every surface direction.
		return handle == 0 ? original : (handle << 16) | 3;
	}

	/** Run before native physics steps, so new regions and dug holes reach the simulation first. */
	public static void update(Object pipeline, ServerLevel level) {
		State state=state(pipeline);
		if (!linked(level) && state.applied.isEmpty()) return;
		if (!resolve()) return;
		try {
			long generation = SkyCollision.physicsGeneration();
			Map<Long, Long> revisions = SkyCollision.physicsSections();
			boolean linked = linked(level), reset = state.generation != generation || !linked;
			if(reset) state.published.clear();
			state.required.clear();
			if(linked) requiredSections(pipeline,state,revisions);
			state.initialized=true;
			Set<Long> candidates = new HashSet<>(state.applied.keySet());
			candidates.addAll(state.required);
			for (long key : candidates) {
				long revision = state.required.contains(key) ? revisions.getOrDefault(key, 0L) : 0L;
				int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
				var chunk = level.getChunkSource().getChunkNow(sx, sz);
				if (level.isOutsideBuildHeight(sy * 16)) { state.applied.remove(key); continue; }
				if (!reset && state.applied.getOrDefault(key,-1L)==revision && state.nativeOnly.contains(key)==(chunk==null)) continue;
				long scene=((Number)sceneHandle.invoke(pipeline)).longValue();
				if(revision==0) {
					// Restore original MC collider/callback data, or remove only the unloaded native chunk.
					int[] ordinary=state.worldBlocks.get(key);
					if(chunk!=null&&ordinary!=null) addChunk.invoke(null,scene,sx,sy,sz,ordinary,true,-1);
					else if(chunk!=null) addSection.invoke(pipeline,chunk.getSections()[level.getSectionIndexFromSectionY(sy)],sx,sy,sz,false);
					else removeChunk.invoke(null,scene,sx,sy,sz,true);
					state.applied.remove(key); state.worldBlocks.remove(key); state.retained.remove(key); state.nativeOnly.remove(key); state.published.remove(key);
				} else {
					if(chunk==null) {
						TerrainSection cache=terrainSection(state,level,sx,sy,sz); prepare(cache,level,sx,sy,sz);
						// Do not force-load MC chunks: Rapier can keep received Skyrim terrain independently.
						if(!cache.candidates.isEmpty()) {
							if(!java.util.Arrays.equals(state.published.get(key),cache.cells)) {
								addChunk.invoke(null,scene,sx,sy,sz,cache.cells,true,-1); uploaded(pipeline,sx,sy,sz,cache.cells);
							}
						} else if(state.applied.containsKey(key)) { removeChunk.invoke(null,scene,sx,sy,sz,true); state.published.remove(key); }
						state.worldBlocks.remove(key); state.nativeOnly.add(key);
					} else {
						int[] ordinary=state.worldBlocks.get(key);
						if(ordinary==null) addSection.invoke(pipeline,chunk.getSections()[level.getSectionIndexFromSectionY(sy)],sx,sy,sz,false);
						else {
							int[] blocks=ordinary.clone();
							if(merge(pipeline,level,sx,sy,sz,blocks)) {
								addChunk.invoke(null,scene,sx,sy,sz,blocks,true,-1); uploaded(pipeline,sx,sy,sz,blocks);
							}
						}
						state.nativeOnly.remove(key);
					}
					state.applied.put(key,revision);
				}
			}
			// A removed section may stop being needed before it is uploaded again.
			// Pin only the current body window; don't accumulate old ship routes.
			state.retained.keySet().retainAll(state.required);
			state.generation = generation;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); }
	}

	public static void sectionRemoved(Object pipeline, int sx, int sy, int sz) {
		synchronized (STATES) {
			State state = STATES.get(pipeline);
			if (state != null) { long key=SectionPos.asLong(sx,sy,sz); state.applied.remove(key); state.worldBlocks.remove(key); state.nativeOnly.remove(key); state.published.remove(key); }
		}
	}

	public static void dispose(Object pipeline) { synchronized (STATES) { STATES.remove(pipeline); } }

	/** Unknown/non-sublevel bodies retain complete coverage rather than silently losing collisions. */
	public static void untrackedBody(Object pipeline) { state(pipeline).untrackedBodies=true; }

	private static void requiredSections(Object pipeline,State state,Map<Long,Long> revisions) throws ReflectiveOperationException {
		ByteBuffer interest=state.interest; interest.clear(); interest.putInt(0); int count=0;
		if(!bodyWindowAvailable) { state.required.addAll(revisions.keySet()); return; }
		if(state.untrackedBodies||!((Map<?,?>)activeContraptions.get(pipeline)).isEmpty()) state.required.addAll(revisions.keySet());
		for(Object body:((Map<?,?>)activeBodies.get(pipeline)).values()) {
			AABB box=(AABB)boundsToAabb.invoke(bodyBounds.invoke(body));
			bodyVelocity.invoke(pipeline,body,state.velocity);
			// Stream ahead along one second of travel and down to the landing surface.
			AABB swept=box.expandTowards(state.velocity.x(),state.velocity.y(),state.velocity.z()).inflate(16);
			AABB reach=new AABB(swept.minX,swept.minY-64,swept.minZ,swept.maxX,swept.maxY,swept.maxZ);
			if(!Double.isFinite(reach.minX)||!Double.isFinite(reach.maxX)||!Double.isFinite(reach.minY)||!Double.isFinite(reach.maxY)
				||!Double.isFinite(reach.minZ)||!Double.isFinite(reach.maxZ)) { state.required.addAll(revisions.keySet()); return; }
			if(count<64) {
				interest.putFloat((float)reach.minX).putFloat((float)reach.minY).putFloat((float)reach.minZ);
				interest.putFloat((float)reach.maxX).putFloat((float)reach.maxY).putFloat((float)reach.maxZ);
				interest.putFloat((float)((box.minX+box.maxX)*.5)).putFloat((float)box.minY).putFloat((float)((box.minZ+box.maxZ)*.5)); count++;
			}
			// Enumerating enormous/high-speed bodies must not spend time walking unknown space.
			double volume=(Math.floor(reach.maxX/16)-Math.floor(reach.minX/16)+1)
				*(Math.floor(reach.maxY/16)-Math.floor(reach.minY/16)+1)*(Math.floor(reach.maxZ/16)-Math.floor(reach.minZ/16)+1);
			if(volume>65536) {
				for(long key:revisions.keySet()) {
					int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
					if(sx*16.0<=reach.maxX&&(sx+1)*16.0>=reach.minX&&sy*16.0<=reach.maxY&&(sy+1)*16.0>=reach.minY
						&&sz*16.0<=reach.maxZ&&(sz+1)*16.0>=reach.minZ) state.required.add(key);
				} continue;
			}
			for(int sx=(int)Math.floor(reach.minX/16);sx<=(int)Math.floor(reach.maxX/16);sx++)
				for(int sy=(int)Math.floor(reach.minY/16);sy<=(int)Math.floor(reach.maxY/16);sy++)
					for(int sz=(int)Math.floor(reach.minZ/16);sz<=(int)Math.floor(reach.maxZ/16);sz++) {
						long key=SectionPos.asLong(sx,sy,sz); if(revisions.containsKey(key)) state.required.add(key);
					}
		}
		interest.putInt(0,count); interest.flip(); SkyLink.tryWriteRender(Proto.REN_PHYSICS_INTEREST,interest,null);
	}

	private static void warn(Throwable error) {
		if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't provide Skyrim terrain to Sable physics", error); }
	}
}
