package dev.skycraft.world;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import static dev.skycraft.link.Proto.*;

/** Original Skyrim landscape vertices, used only to draw the excavation boundary. */
public final class VisualTerrainStore {
	private record Mesh(List<SkyTri> triangles, Set<Long> regions) {}
	private record Snapshot(Map<Long, Mesh> meshes, Map<Long, List<SkyTri>> regions) {}
	private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

	public synchronized void clear() { snapshot = new Snapshot(Map.of(), Map.of()); }

	/** Replace one mesh atomically; zero triangles removes unloaded/restored geometry. */
	public synchronized boolean read(ByteBuffer payload, int epoch, LongConsumer changed) {
		if (payload.limit() < COL_VISUAL_HEADER_BYTES) throw new IllegalArgumentException("Truncated visual terrain header");
		long id = payload.getLong(0);
		int messageEpoch = payload.getInt(8), count = payload.getInt(12);
		if (count < 0 || count > 65535 || COL_VISUAL_HEADER_BYTES + (long) count * COL_TRI_BYTES != payload.limit())
			throw new IllegalArgumentException("Invalid visual terrain triangle count");
		if (messageEpoch != epoch) return false;
		List<SkyTri> triangles = new ArrayList<>(count);
		Set<Long> regions = new HashSet<>();
		float[] coordinates = new float[9];
		for (int i = 0, offset = COL_VISUAL_HEADER_BYTES; i < count; i++, offset += COL_TRI_BYTES) {
			for (int k = 0; k < 9; k++) {
				coordinates[k] = payload.getFloat(offset + k * 4);
				if (!Float.isFinite(coordinates[k]) || Math.abs(coordinates[k]) >= 1e6f)
					throw new IllegalArgumentException("Invalid visual terrain coordinate");
			}
			SkyTri triangle = new SkyTri(coordinates, 0, payload.getInt(offset + 36));
			if (!triangle.terrain || triangle.stairHelper || triangle.degenerate()) continue;
			triangles.add(triangle);
			int x0 = region(triangle.minX), x1 = region(triangle.maxX);
			int y0 = region(triangle.minY), y1 = region(triangle.maxY);
			int z0 = region(triangle.minZ), z1 = region(triangle.maxZ);
			if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 4096)
				throw new IllegalArgumentException("Unbounded visual terrain triangle");
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
				regions.add(BlockPos.asLong(x, y, z));
		}
		Snapshot before = snapshot;
		Map<Long, Mesh> meshes = new HashMap<>(before.meshes);
		Set<Long> dirty = new HashSet<>(regions);
		Mesh old = meshes.remove(id);
		if (old != null) dirty.addAll(old.regions);
		if (!triangles.isEmpty()) meshes.put(id, new Mesh(List.copyOf(triangles), Set.copyOf(regions)));
		Map<Long, List<SkyTri>> index = new HashMap<>(before.regions);
		for (long key : dirty) {
			List<SkyTri> list = new ArrayList<>();
			BlockPos at = BlockPos.of(key);
			double x = at.getX() * 8.0, y = at.getY() * 8.0, z = at.getZ() * 8.0;
			for (Mesh mesh : meshes.values()) if (mesh.regions.contains(key))
				for (SkyTri t : mesh.triangles) if (t.maxX >= x && t.minX <= x + 8 && t.maxY >= y && t.minY <= y + 8 && t.maxZ >= z && t.minZ <= z + 8)
					list.add(t);
			if (list.isEmpty()) index.remove(key); else index.put(key, List.copyOf(list));
		}
		snapshot = new Snapshot(Map.copyOf(meshes), Map.copyOf(index));
		for (long key : dirty) {
			BlockPos at = BlockPos.of(key);
			changed.accept(BlockPos.asLong(at.getX() * 8, at.getY() * 8, at.getZ() * 8));
		}
		return true;
	}

	/** Append matching visual triangles once, including vertices shared by region borders. */
	public int near(AABB box, List<SkyTri> out) {
		Snapshot current = snapshot;
		Set<SkyTri> seen = new HashSet<>();
		int added = 0;
		for (int x = region(box.minX); x <= region(box.maxX); x++)
			for (int y = region(box.minY); y <= region(box.maxY); y++)
				for (int z = region(box.minZ); z <= region(box.maxZ); z++) {
					List<SkyTri> list = current.regions.get(BlockPos.asLong(x, y, z));
					if (list == null) continue;
					for (SkyTri t : list) if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY
						&& t.maxZ >= box.minZ && t.minZ <= box.maxZ && seen.add(t)) { out.add(t); added++; }
				}
		return added;
	}

	private static int region(double coordinate) { return Math.floorDiv((int) Math.floor(coordinate), 8); }
}
