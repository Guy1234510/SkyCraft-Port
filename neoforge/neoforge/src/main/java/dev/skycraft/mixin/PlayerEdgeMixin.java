package dev.skycraft.mixin;

import dev.skycraft.link.SkyLink;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crouching doesn't stop at edges. Minecraft looks for block collision under the player to decide
 * where an edge is; the Skyrim ground the player walks on isn't blocks (the player collides with
 * its exact triangles), so every direction looked like a drop and crouching froze the player.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
	private void skycraft$crouchWalkAnywhere(Vec3 delta, MoverType moverType, CallbackInfoReturnable<Vec3> cir) {
		Player player = (Player)(Object)this;
        // Keep vanilla edge protection when standing on Minecraft blocks.
        // Native triangle ground alone must not freeze crouching movement.
        boolean blockSupport = player.level().getBlockCollisions(player,
            player.getBoundingBox().move(0, -0.6, 0)).iterator().hasNext();
        if (SkyLink.active() && !blockSupport) {
			cir.setReturnValue(delta);
		}
	}
}
