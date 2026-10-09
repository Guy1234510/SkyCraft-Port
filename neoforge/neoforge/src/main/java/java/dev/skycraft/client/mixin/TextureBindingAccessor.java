package dev.skycraft.client.mixin;

import net.minecraft.resources.ResourceLocation;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderStateShard$TextureStateShard")
public interface TextureBindingAccessor {
	@Accessor("texture")
	Optional<ResourceLocation> skycraft$texture();
}
