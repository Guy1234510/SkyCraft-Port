package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.lang.reflect.Method;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Compare plot-local hits in world space without changing the hit consumed by Sable. */
public final class SableRayCompat {
    private static boolean resolved, warned;
    private static Object helper;
    private static Method project;
    private SableRayCompat() {}
    public static Vec3 world(net.minecraft.world.level.BlockGetter view, Vec3 point) {
        if (!(view instanceof Level level)) return point;
        if (!resolved) {
            resolved = true;
            try {
                helper = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
                project = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").getType()
                    .getMethod("projectOutOfSubLevel", Level.class, Vec3.class);
            } catch (ClassNotFoundException ignored) {
            } catch (ReflectiveOperationException | LinkageError error) {
                SkyCraft.LOG.warn("SkyCraft: optional Sable ray projection unavailable", error);
            }
        }
        if (project != null) try { return (Vec3) project.invoke(helper, level, point); }
        catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't project Sable ray", error); }
        }
        return point;
    }
}
