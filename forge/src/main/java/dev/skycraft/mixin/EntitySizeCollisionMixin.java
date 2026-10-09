package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Subtract obstacles only inside the search volume, avoiding a huge union of terrain voxels. */
@Mixin(Entity.class)
public abstract class EntitySizeCollisionMixin {
    @WrapOperation(method = "refreshDimensions", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;findFreePosition(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/Vec3;DDD)Ljava/util/Optional;"))
    private Optional<Vec3> skycraft$boundedSearch(Level level, Entity entity, VoxelShape search, Vec3 target,
        double width, double height, double depth, Operation<Optional<Vec3>> original) {
        if (!SkyCollision.active() || search.isEmpty()) return original.call(level, entity, search, target, width, height, depth);
        AABB bounds = search.bounds();
        VoxelShape free = search;
        for (VoxelShape obstacle : level.getBlockCollisions(entity, bounds.inflate(width, height, depth))) {
            if (level.getWorldBorder() != null && !level.getWorldBorder().isWithinBounds(obstacle.bounds())) continue;
            for (AABB box : obstacle.toAabbs()) {
                AABB expanded = box.inflate(width / 2, height / 2, depth / 2);
                if (!expanded.intersects(bounds)) continue;
                // (S minus union(O)) equals sequential subtraction. Clipping O to bounds(S)
                // retains every valid candidate without introducing distant grid coordinates.
                free = Shapes.join(free, Shapes.create(expanded.intersect(bounds)), BooleanOp.ONLY_FIRST);
                if (free.isEmpty()) { return Optional.empty(); }
            }
        }
        return free.closestPointTo(target);
    }
}
