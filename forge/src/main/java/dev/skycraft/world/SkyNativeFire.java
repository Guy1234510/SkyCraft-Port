package dev.skycraft.world;

import dev.skycraft.combat.SkyrimActorEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Short-lived native flame volumes; never creates or saves Minecraft blocks. */
public final class SkyNativeFire {
    private record Key(int x, int y, int z) {}
    private record Region(AABB bounds, double x, double z, double radius, long expires) {}
    private static final Map<ServerLevel, Map<Key, Region>> LEVELS = new WeakHashMap<>();
    private SkyNativeFire() {}
    public static void accept(ServerLevel level, int radius, int x, int y, int z) {
        if (radius < 1 || radius > 1000) return;
        double cx=x/100.0, cy=y/100.0, cz=z/100.0, r=radius/100.0;
        Map<Key, Region> regions=LEVELS.computeIfAbsent(level, ignored -> new HashMap<>());
        if (regions.size() >= 256 && !regions.containsKey(new Key(x,y,z))) return;
        regions.put(new Key(x,y,z), new Region(new AABB(cx-r,cy-0.23,cz-r,cx+r,cy+0.69,cz+r),cx,cz,r,level.getGameTime()+30));
    }
    public static void tick(ServerLevel level) {
        Map<Key, Region> regions=LEVELS.get(level);
        if (regions == null || level.getGameTime()%5 != 0) return;
        regions.values().removeIf(r -> r.expires()<level.getGameTime());
        for (Region region : regions.values()) {
            for (var entity : level.getEntities((net.minecraft.world.entity.Entity) null, region.bounds(), e -> !(e instanceof Player)
                    && !(e instanceof SkyrimActorEntity) && e.isAlive() && !e.fireImmune() && !e.isInWaterOrBubble())) {
                AABB body=entity.getBoundingBox();
                double dx=region.x()-Math.max(body.minX,Math.min(body.maxX,region.x()));
                double dz=region.z()-Math.max(body.minZ,Math.min(body.maxZ,region.z()));
                if (dx*dx+dz*dz < region.radius()*region.radius()) entity.setSecondsOnFire(3);
            }
        }
    }
    public static void clear() { LEVELS.clear(); }
}
