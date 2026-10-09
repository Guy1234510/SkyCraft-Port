package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyEntityCollider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apply the same exact collision to players and mobs on both logical sides. */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {
	@Shadow
	private static Vec3 collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes) {
		throw new AssertionError();
	}

	/** Optimized collision sweepers may bypass BlockCollisions, which supplies our dug walls. */
	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void skycraft$validateDugWalls(Vec3 requested, CallbackInfoReturnable<Vec3> cir) {
		Entity entity = (Entity) (Object) this;
		if (entity.noPhysics || !SkyCollision.usesSmoothCollider(entity)) return;
		Vec3 movement = cir.getReturnValue();
		if (movement.lengthSqr() == 0) return;
		double step = Math.max(0, entity.getStepHeight());
		AABB box = entity.getBoundingBox();
		AABB swept = box.expandTowards(movement).expandTowards(0, step, 0);
		if (!dev.skycraft.world.SkyDig.hasDugNear(entity.level(), swept)) return;
		List<VoxelShape> shapes = new java.util.ArrayList<>();
		// Use the same iterator as the server's new-collision check, even with Radium installed.
		entity.level().getBlockCollisions(entity, swept).forEach(shapes::add);
		shapes.addAll(entity.level().getEntityCollisions(entity, swept));
		var border = entity.level().getWorldBorder();
		if (border.isInsideCloseToBorder(entity, swept)) shapes.add(border.getCollisionShape());
		Vec3 allowed = collideWithShapes(movement, box, shapes);
		boolean horizontal = allowed.x != movement.x || allowed.z != movement.z;
		if (horizontal && step > 0 && (entity.onGround() || (movement.y < 0 && allowed.y != movement.y))) {
			Vec3 up = collideWithShapes(new Vec3(0, step, 0), box, shapes);
			Vec3 across = collideWithShapes(new Vec3(movement.x, 0, movement.z), box.move(up), shapes);
			Vec3 down = collideWithShapes(new Vec3(0, movement.y - up.y, 0), box.move(up).move(across), shapes);
			Vec3 stepped = up.add(across).add(down);
			if (stepped.horizontalDistanceSqr() > allowed.horizontalDistanceSqr()) allowed = stepped;
		}
		if (!allowed.equals(movement)) cir.setReturnValue(allowed);
	}

	@ModifyVariable(method = "collide", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Vec3 skycraft$smoothSkyrimCollision(Vec3 movement) {
		Entity entity = (Entity) (Object) this;
		// A server player's movement packet already contains the client's resolved displacement.
		// Reapplying slope snapping/wall push-out here can reject that position as moved wrongly.
		// Keep vanilla Minecraft block validation; the smooth-collider predicate excludes Skyrim voxels.
		if (entity instanceof net.minecraft.server.level.ServerPlayer) return movement;
        // A ridden boat's movement packet has the same client authority as its player.
        // Re-snapping the hull here can push it sideways and trigger vehicle rollback.
        if (!entity.level().isClientSide() && entity instanceof net.minecraft.world.entity.vehicle.Boat
            && entity.getControllingPassenger() instanceof net.minecraft.server.level.ServerPlayer) return movement;
		if (!entity.noPhysics && SkyCollision.usesSmoothCollider(entity)) {
			// Set terrain height first. Vanilla then checks real blocks and dug-hole walls
			// at the resolved height, instead of clipping horizontal motion at the old height.
			return SkyEntityCollider.collide(entity, movement);
		}
		return movement;
	}
}
