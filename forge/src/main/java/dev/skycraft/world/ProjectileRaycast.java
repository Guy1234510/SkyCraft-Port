package dev.skycraft.world;

import dev.skycraft.link.SkyLink;
import dev.skycraft.mixin.ClipContextAccessor;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

/** Refine the actual level's default clip, including optional sublevel transformations. */
public final class ProjectileRaycast {
    private ProjectileRaycast() {}
    public static BlockHitResult clip(Level level, ClipContext context) {
        return refine(level, context, InheritedBlockClip.clip(level, context));
    }
    public static BlockHitResult refine(Level level, ClipContext context, BlockHitResult hit) {
        if (SkyLink.active() && ((ClipContextAccessor)(Object)context).skycraft$collisionContext() instanceof EntityCollisionContext collision
                && collision.getEntity() instanceof Projectile) {
            return SkyClip.refine(level, context.getFrom(), context.getTo(), hit, SkyClip.Use.PROJECTILE);
        }
        return hit;
    }
}
