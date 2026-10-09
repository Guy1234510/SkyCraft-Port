package dev.skycraft.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only pixel address for bulk copies while the owning image remains open. */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
	@Accessor("pixels")
	long skycraft$pixels();
}
