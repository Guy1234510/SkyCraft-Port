package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.client.SkyClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** F5 changes the view, while positional audio remains at the player's ears. */
@Mixin(SoundEngine.class)
public abstract class SoundListenerMixin {
    @WrapOperation(method = "updateSource", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 skycraft$playerEars(Camera camera, Operation<Vec3> original) {
        var player = Minecraft.getInstance().player;
        return SkyClient.tookOver() && player != null ? player.getEyePosition() : original.call(camera);
    }
}
