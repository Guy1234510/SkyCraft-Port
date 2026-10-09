package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyBoatPlacement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep vanilla reach, occlusion, entity checks, item consumption, stats and placement events. */
@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
    @Unique private static int skycraft$placementLogs;

    @Inject(method = "getBoat", at = @At("RETURN"))
    private void skycraft$seatHull(Level level, HitResult hit, ItemStack stack, Player player, CallbackInfoReturnable<Boat> cir) {
        SkyBoatPlacement.seat(cir.getReturnValue(), hit);
    }

    @WrapOperation(method = "use", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean skycraft$checkHull(Level level, Entity entity, AABB box, Operation<Boolean> original) {
        boolean blocksClear = original.call(level, entity, box);
        boolean terrainClear = !(entity instanceof Boat boat) || SkyBoatPlacement.fits(boat);
        if (SkyLink.active() && skycraft$placementLogs++ < 24) {
            SkyCraft.LOG.info("SkyCraft: boat placement side={} pos={} blocksClear={} terrainClear={}",
                level.isClientSide() ? "client" : "server", entity.position(), blocksClear, terrainClear);
        }
        return blocksClear && terrainClear;
    }
}
