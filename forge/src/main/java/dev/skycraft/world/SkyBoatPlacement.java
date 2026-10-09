package dev.skycraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

/** Exact hull support and obstruction checks; voxel approximations are unsuitable for low hulls. */
public final class SkyBoatPlacement {
    private SkyBoatPlacement() {}

    private static List<SkyTri> triangles(Boat boat) {
        AABB area = boat.getBoundingBox().inflate(0.5);
        List<SkyTri> tris = new ArrayList<>();
        SkyCollision.trianglesNear(area, tris);
        return SkyTriCut.withoutDug(tris, SkyDig.lookup(boat.level()), area);
    }

    public static void seat(Boat boat, HitResult hit) {
        if (!(hit instanceof SkyClip.SkyrimHitResult)) return;
        AABB box = boat.getBoundingBox();
        double floor = TriCollider.groundAt(triangles(boat), boat.getX(), boat.getY(), boat.getZ(),
            0.4, SkyDig.lookup(boat.level()), Math.max(box.getXsize(), box.getZsize()) * 0.5);
        if (Double.isFinite(floor)) boat.setPos(boat.getX(), Math.max(boat.getY(), floor) + 0.001, boat.getZ());
    }

    public static boolean fits(Boat boat) {
        AABB box = boat.getBoundingBox();
        return TriCollider.canOccupy(triangles(boat), boat.getX(), box.minY, boat.getZ(),
            Math.max(box.getXsize(), box.getZsize()) * 0.5, box.getYsize(), SkyDig.lookup(boat.level()));
    }
}
