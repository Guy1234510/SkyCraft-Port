package dev.skycraft.client.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeState")
public interface RenderSetupAccessor {
	@Accessor("textureState")
	RenderStateShard.EmptyTextureStateShard skycraft$textureState();
	@Accessor("cullState")
	RenderStateShard.CullStateShard skycraft$cullState();
}
