package dev.skycraft.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import dev.skycraft.world.SkyDig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.simple.SimpleChannel;

public final class SkyCraftClient {
    public void onInitializeClient() {
        SkyLink.announceRunning(); DiscordPresence.start(); DestructionToggle.register();
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
            var dispatcher = event.getDispatcher();
            dispatcher.register(Commands.literal("join").then(Commands.argument("link", StringArgumentType.greedyString()).executes(c -> {
                String link = StringArgumentType.getString(c, "link");
                c.getSource().sendSuccess(() -> Component.literal("Joining " + link.trim() + "..."), false);
                Minecraft.getInstance().execute(() -> MirrorWorld.joinFriend(Minecraft.getInstance(), link)); return 1;
            })));
            dispatcher.register(Commands.literal("leave").executes(c -> {
                Minecraft.getInstance().execute(() -> MirrorWorld.leaveFriend(Minecraft.getInstance())); return 1;
            }));
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) SkyClient.clientTick(Minecraft.getInstance());
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            Minecraft minecraft = Minecraft.getInstance(); String port = System.getenv("SKYCRAFT_LAN_PORT");
            var server = minecraft.getSingleplayerServer();
            if (port == null || port.isBlank() || server == null || server.isPublished()) return;
            minecraft.execute(() -> {
                if (System.getenv("SKYCRAFT_LAN_OFFLINE") != null) server.setUsesAuthentication(false);
                boolean ok = server.publishServer(server.getDefaultGameType(), false, Integer.parseInt(port.trim()));
                SkyCraft.LOG.info("SkyCraft: world opened to LAN on port {} ({})", port.trim(), ok ? "ok" : "FAILED");
            });
        });
        SkyNet.onDied = payload -> {
            if (SkyLink.active()) SkyLink.pushEvent(Proto.EV_PLAYER_DIED, payload.attackerFormId(), 0, 0, 0, 0, 0);
        };
        SkyNet.onDugSync = payload -> {
            var level = Minecraft.getInstance().level; if (level == null) return;
            var chunk = level.getChunk(payload.chunkX(), payload.chunkZ()); var previous = SkyDig.column(chunk);
            SkyDig.setColumn(chunk, payload.column()); SkyDigClient.dugChanged(chunk, previous, payload.column());
        };
        dev.skycraft.world.SkyCollision.setSmoothCollider(e -> (e instanceof net.minecraft.world.entity.LivingEntity
            || e instanceof net.minecraft.world.entity.vehicle.Boat)
            && !(e instanceof dev.skycraft.combat.SkyrimActorEntity) && SkyClient.linked());
    }
    public static boolean canSend(SimpleChannel channel) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && channel.isRemotePresent(connection.getConnection());
    }
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SkyCombat.SKYRIM_ACTOR.get(), NoopRenderer::new);
    }
}
