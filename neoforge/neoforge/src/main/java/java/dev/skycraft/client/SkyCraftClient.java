package dev.skycraft.client;

import dev.skycraft.combat.SkyCombat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.minecraft.client.renderer.entity.NoopRenderer;

public final class SkyCraftClient {
	public void onInitializeClient() {
		dev.skycraft.link.SkyLink.announceRunning();
		DiscordPresence.start();
		DestructionToggle.register();
		// Multiplayer without editing files: the host opens their world to LAN (O, Open to LAN) and
		// e4mc gives them a link; friends type /join <link> in chat, and /leave to come back.
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("join")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("link", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> {
						String link = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "link");
						c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Joining " + link.trim() + "..."));
						// After the chat screen has closed: this leaves the current world.
						net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.joinFriend(net.minecraft.client.Minecraft.getInstance(), link));
						return 1;
					})));
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("leave").executes(c -> {
				net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.leaveFriend(net.minecraft.client.Minecraft.getInstance()));
				return 1;
			}));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SkyClient::clientTick);
		// Multiplayer testing on one PC: SKYCRAFT_LAN_PORT opens the world to LAN on that port as soon
		// as it's loaded, and SKYCRAFT_LAN_OFFLINE lets offline (dev) clients join it.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
			String port = System.getenv("SKYCRAFT_LAN_PORT");
			var server = minecraft.getSingleplayerServer();
			if (port == null || port.isBlank() || server == null || server.isPublished()) {
				return;
			}
			minecraft.execute(() -> {
				if (System.getenv("SKYCRAFT_LAN_OFFLINE") != null) {
					server.setUsesAuthentication(false);
				}
				boolean ok = server.publishServer(server.getDefaultGameType(), false, Integer.parseInt(port.trim()));
				dev.skycraft.SkyCraft.LOG.info("SkyCraft: world opened to LAN on port {} ({}{})", port.trim(), ok ? "ok" : "FAILED",
					System.getenv("SKYCRAFT_LAN_OFFLINE") != null ? ", offline logins allowed" : "");
			});
		});
		// A guest in a friend's world: dying there kills this player's own Skyrim character.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.Died.TYPE, (payload, context) -> {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_PLAYER_DIED, payload.attackerFormId(), 0, 0, 0, 0, 0);
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.DugSync.TYPE, (payload, context) ->
			context.client().execute(() -> {
				var level = context.client().level;
				if (level != null) {
					var chunk = level.getChunk(payload.chunkX(), payload.chunkZ());
					var previous = chunk.getData(dev.skycraft.world.SkyDig.DUG.get());
					chunk.setData(dev.skycraft.world.SkyDig.DUG.get(), payload.column());
					SkyDigClient.dugChanged(chunk, previous, payload.column());
				}
			})
		);
		// Players use exact client movement (server packets already contain its result); mobs
		// resolve exact movement on the server too. Skyrim actor proxies are positioned by Skyrim.
		dev.skycraft.world.SkyCollision.setSmoothCollider(e -> (e instanceof net.minecraft.world.entity.LivingEntity
            || e instanceof net.minecraft.world.entity.vehicle.Boat)
			&& !(e instanceof dev.skycraft.combat.SkyrimActorEntity) && SkyClient.linked());
	}

	public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		// This event runs after entity types are registered, unlike the mod constructor.
		event.registerEntityRenderer(SkyCombat.SKYRIM_ACTOR.get(), NoopRenderer::new);
	}
}
