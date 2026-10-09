package dev.skycraft.client.mixin;

import dev.skycraft.client.render.ModRenderCompat;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Request Create's full CPU renderer only during our geometry export; leave Flywheel intact elsewhere. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.FlwApiLinkImpl", remap = false)
public abstract class FlywheelCaptureMixin {
	@Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, remap = false)
	private void skycraft$cpuCapture(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
		if (ModRenderCompat.capturing()) cir.setReturnValue(false);
	}
}
