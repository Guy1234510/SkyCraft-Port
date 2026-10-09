package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import dev.skycraft.client.render.WorldExporter;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Light propagation can finish after the block-change mesh was captured. */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheLightMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void skycraft$lightChanged(LightLayer layer, SectionPos section, CallbackInfo ci) {
        if (!SkyClient.linked()) return;
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    WorldExporter.markDirty(section.x() + x, section.y() + y, section.z() + z);
    }
}
