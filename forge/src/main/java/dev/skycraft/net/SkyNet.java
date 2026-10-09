package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Forge networking; Skyrim's shared-memory protocol remains unchanged. */
public final class SkyNet {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(SkyCraft.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);
    public static Consumer<Died> onDied = ignored -> {};
    public static Consumer<DugSync> onDugSync = ignored -> {};
    private SkyNet() {}
    public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) {}
    public record Died(int attackerFormId) {}
    public record DigOpen(int world, BlockPos pos, int material) {}
    public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) {}
    public record DugSync(int chunkX, int chunkZ, SkyDig.DugColumn column) {}

    public static void init() {
        CHANNEL.messageBuilder(Hurt.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder((p, b) -> { b.writeVarInt(p.kind()); b.writeFloat(p.skyrimDamage()); b.writeInt(p.attackerFormId()); b.writeVarInt(p.flags()); })
            .decoder(b -> new Hurt(b.readVarInt(), b.readFloat(), b.readInt(), b.readVarInt()))
            .consumerMainThread((p, c) -> {
                var player = c.get().getSender();
                if (player != null && Float.isFinite(p.skyrimDamage())) SkyCombat.hurtPlayer(player, p.kind(), Math.max(0, Math.min(10000, p.skyrimDamage())), p.attackerFormId(), p.flags());
            }).add();
        CHANNEL.messageBuilder(Died.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder((p, b) -> b.writeInt(p.attackerFormId())).decoder(b -> new Died(b.readInt()))
            .consumerMainThread((p, c) -> onDied.accept(p)).add();
        CHANNEL.messageBuilder(DigOpen.class, 2, NetworkDirection.PLAY_TO_SERVER)
            .encoder((p, b) -> { b.writeInt(p.world()); b.writeBlockPos(p.pos()); b.writeVarInt(p.material()); })
            .decoder(b -> new DigOpen(b.readInt(), b.readBlockPos(), b.readVarInt()))
            .consumerMainThread((p, c) -> { var player = c.get().getSender(); if (player != null) SkyDig.open(player, p.world(), p.pos(), p.material()); }).add();
        CHANNEL.messageBuilder(DigReveal.class, 3, NetworkDirection.PLAY_TO_SERVER)
            .encoder((p, b) -> {
                if (p.cells().size() > 64 || p.cells().size() != p.materials().size()) throw new IllegalArgumentException("Invalid reveal batch");
                b.writeInt(p.world()); b.writeVarInt(p.cells().size());
                for (int i = 0; i < p.cells().size(); i++) { b.writeBlockPos(p.cells().get(i)); b.writeVarInt(p.materials().get(i)); }
            }).decoder(SkyNet::readReveal)
            .consumerMainThread((p, c) -> { var player = c.get().getSender(); if (player != null) SkyDig.reveal(player, p.world(), p.cells(), p.materials().stream().mapToInt(Integer::intValue).toArray()); }).add();
        CHANNEL.messageBuilder(DugSync.class, 4, NetworkDirection.PLAY_TO_CLIENT)
            .encoder((p, b) -> { b.writeInt(p.chunkX()); b.writeInt(p.chunkZ()); p.column().write(b); })
            .decoder(b -> new DugSync(b.readInt(), b.readInt(), SkyDig.DugColumn.read(b)))
            .consumerMainThread((p, c) -> onDugSync.accept(p)).add();
    }
    private static DigReveal readReveal(FriendlyByteBuf b) {
        int world = b.readInt(), n = b.readVarInt();
        if (n < 0 || n > 64) throw new IllegalArgumentException("Invalid reveal batch size");
        List<BlockPos> cells = new ArrayList<>(n); List<Integer> materials = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { cells.add(b.readBlockPos()); materials.add(b.readVarInt()); }
        return new DigReveal(world, List.copyOf(cells), List.copyOf(materials));
    }
    public static void sendToServer(Object message) { CHANNEL.sendToServer(message); }
    public static void sendToPlayer(ServerPlayer player, Object message) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message); }
    public static boolean canSend(ServerPlayer player) { return CHANNEL.isRemotePresent(player.connection.connection); }
    public static boolean canSend() { return dev.skycraft.client.SkyCraftClient.canSend(CHANNEL); }
    /** Send replacements before terrain cuts; vanilla's batched block packet arrives later. */
    public static void sendDugReplacement(LevelChunk chunk, net.minecraft.core.BlockPos pos) {
        if (chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var packet = new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, pos);
            for (ServerPlayer player : level.players()) player.connection.send(packet);
        }
    }

    public static void sendDugColumn(LevelChunk chunk) {
        if (chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
            for (ServerPlayer player : level.players()) sendDugColumn(chunk, player);
    }
    public static void sendDugColumn(LevelChunk chunk, ServerPlayer player) {
        if (canSend(player)) sendToPlayer(player, new DugSync(chunk.getPos().x, chunk.getPos().z, SkyDig.column(chunk)));
    }
    public static boolean isHost(ServerPlayer player) {
        var server = player.level().getServer();
        return server != null && server.isSingleplayerOwner(player.getGameProfile());
    }
}
