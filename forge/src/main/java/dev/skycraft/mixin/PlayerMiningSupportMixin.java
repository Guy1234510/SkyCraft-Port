package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import dev.skycraft.world.SkyEntityCollider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Packet-authoritative players still need actual Skyrim floor support when mining. */
@Mixin(Player.class)
public abstract class PlayerMiningSupportMixin {
    @ModifyExpressionValue(method = "getDigSpeed", remap = false, at = @At(value = "INVOKE", remap = true,
        target = "Lnet/minecraft/world/entity/player/Player;onGround()Z"))
    private boolean skycraft$miningSupport(boolean grounded) {
        if (grounded || !((Object)this instanceof ServerPlayer player)
                || !SkyLink.active() || !SkyNet.isHost(player) || player.isFallFlying()
                || player.getAbilities().flying || player.isPassenger()) return grounded;
        // Query support without moving or snapping the server player again.
        double supported = SkyEntityCollider.collide(player, new Vec3(0, -0.02, 0)).y;
        return supported > -0.01999 && supported <= 0.03;
    }
}
