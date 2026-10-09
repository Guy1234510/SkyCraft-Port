package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SablePhysicsCompat;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rapier builds terrain outside vanilla BlockCollisions, including all-air sections. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class SablePhysicsMixin {
	@Shadow(remap = false) @Final private ServerLevel level;

	@WrapOperation(method = "handleChunkSectionAddition", remap = false, at = @At(value = "INVOKE", remap = false,
		target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;addChunk(JIII[IZI)V"))
	private void skycraft$terrain(long scene, int sx, int sy, int sz, int[] blocks, boolean world, int body, Operation<Void> original) {
		if (!world || SablePhysicsCompat.merge(this, this.level, sx, sy, sz, blocks)) {
			original.call(scene, sx, sy, sz, blocks, world, body);
			if(world) SablePhysicsCompat.uploaded(this,sx,sy,sz,blocks);
		}
	}

	@WrapOperation(method = "handleBlockChange", remap = false, at = @At(value = "INVOKE", remap = false,
		target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;changeBlock(JIIII)V"))
	private void skycraft$changed(long scene, int x, int y, int z, int block, Operation<Void> original) {
		original.call(scene, x, y, z, SablePhysicsCompat.mergeBlock(this, this.level, x, y, z, block));
	}

	@Inject(method = "prePhysicsTicks", remap = false, at = @At("HEAD"))
	private void skycraft$refreshTerrain(CallbackInfo ci) {
		SablePhysicsCompat.update(this, this.level);
	}

	@Inject(method = "handleChunkSectionRemoval", remap = false, at = @At("RETURN"))
	private void skycraft$removed(int sx, int sy, int sz, CallbackInfo ci) {
		SablePhysicsCompat.sectionRemoved(this, sx, sy, sz);
	}

	@Inject(method = "dispose", remap = false, at = @At("RETURN"))
	private void skycraft$disposed(CallbackInfo ci) { SablePhysicsCompat.dispose(this); }

	@Inject(method = {"addBox", "addRope"}, remap = false, at = @At("RETURN"))
	private void skycraft$standaloneBody(CallbackInfoReturnable<Object> ci) { SablePhysicsCompat.untrackedBody(this); }
}
