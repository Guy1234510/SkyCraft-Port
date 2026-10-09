package dev.skycraft.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserve a captured material's actual face-culling state without touching OpenGL. */
@Mixin(targets = "net.minecraft.client.renderer.RenderStateShard$BooleanStateShard")
public interface BooleanRenderStateAccessor {
    @Accessor("enabled")
    boolean skycraft$enabled();
}
