package dev.skycraft.client;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyTri;
import dev.skycraft.world.TriCollider;
import dev.skycraft.SkyCraft;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;

/** Client ground queries; movement is shared with server entities through SkyEntityCollider. */
public final class SkyCollider {
	private SkyCollider() {
	}

	/** Highest Skyrim surface at or below {@code maxAbove} over the feet at (x, y, z), or NaN. */
	public static double groundAt(double x, double y, double z, double maxAbove) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(x - 1, y - 4, z - 1, x + 1, y + maxAbove + 1, z + 1), tris);
		return TriCollider.groundAt(tris, x, y, z, maxAbove, dev.skycraft.world.SkyDig.clientDug);
	}
}
