package dev.skycraft.forge;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SkyCraft.MOD_ID)
public final class SkyCraftForge {
    public SkyCraftForge() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        SkyCombat.register(modBus); new SkyCraft().onInitialize();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            modBus.addListener(dev.skycraft.client.SkyCraftClient::registerRenderers);
            modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) ->
                event.enqueueWork(() -> new dev.skycraft.client.SkyCraftClient().onInitializeClient()));
        });
    }
}
