package dev.skycraft.neoforge;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.net.SkyNet;
import dev.skycraft.world.SkyDig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

/** NeoForge entry point. Forgified Fabric API keeps the shared game logic and mixins available. */
@Mod(SkyCraft.MOD_ID)
public final class SkyCraftNeoForge {
	public SkyCraftNeoForge(IEventBus modBus) {
		SkyCombat.register(modBus);
		SkyDig.register(modBus);
		NeoForge.EVENT_BUS.addListener((ChunkWatchEvent.Sent event) ->
			SkyNet.sendDugColumn(event.getChunk(), event.getPlayer()));
		new SkyCraft().onInitialize();
		if (FMLEnvironment.dist == Dist.CLIENT) {
			modBus.addListener(dev.skycraft.client.SkyCraftClient::registerRenderers);
			new dev.skycraft.client.SkyCraftClient().onInitializeClient();
		}
	}
}
