package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.skycraft.client.render.ModRenderCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Native lights shade captured animated components; don't bake Catnip's fake lights too. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer", remap = false)
public abstract class CatnipDiffuseCaptureMixin {
	@ModifyExpressionValue(method = "renderInto", remap = false,
		at = @At(value = "FIELD", target = "Lnet/createmod/catnip/render/ShadeSeparatingSuperByteBuffer;disableDiffuse:Z", opcode = 180, remap = false))
	private boolean skycraft$nativeDiffuse(boolean disabled) {
		// Change this read only. Catnip caches/reuses these buffers for normal rendering.
		return disabled || ModRenderCompat.capturing();
	}
}
