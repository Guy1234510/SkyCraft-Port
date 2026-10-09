package dev.skycraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(net.minecraft.world.entity.vehicle.Boat.class)
public abstract class BoatWaterMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "getGroundFriction", at = @At("RETURN"), cancellable = true)
    private void skycraft$nativeGround(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        if (!dev.skycraft.link.SkyLink.active()) return;
        float vanilla = cir.getReturnValue();
        if (Float.isFinite(vanilla) && vanilla > 0.0F) return;
        var boat = (net.minecraft.world.entity.vehicle.Boat)(Object)this;
        // Use the previous real movement's contact, not a second collision solve
        // during status evaluation, which can select another seam/step surface.
        if (boat.onGround()) cir.setReturnValue(0.6F);
    }

    @WrapOperation(method = {"getWaterLevelAbove", "checkInWater", "isUnderwater"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState skycraft$water(Level level, BlockPos pos, Operation<FluidState> original) {
        FluidState real = original.call(level, pos);
        if (!real.isEmpty() || !SkyWater.active()) return real;
        FluidState virtual = SkyWater.fluidAt(level, pos);
        return virtual == null ? real : virtual;
    }
    @WrapOperation(method = {"getWaterLevelAbove", "checkInWater", "isUnderwater"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;getHeight(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float skycraft$height(FluidState state, BlockGetter level, BlockPos pos, Operation<Float> original) {
        float virtual = SkyWater.active() ? SkyWater.substitutedHeight(level, pos) : -1.0F;
        return virtual < 0.0F ? original.call(state, level, pos) : virtual;
    }
}
