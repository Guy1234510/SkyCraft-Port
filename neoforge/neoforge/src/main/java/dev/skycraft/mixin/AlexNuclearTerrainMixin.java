package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyDigBlast;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.List;
import java.util.Stack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Alex's nuke carves sections directly instead of calling Explosion.explode. */
@Pseudo
@Mixin(targets = "com.github.alexmodguy.alexscaves.server.entity.item.NuclearExplosionEntity", remap = false)
public abstract class AlexNuclearTerrainMixin {
    @Shadow(remap = false) private Stack<BlockPos> destroyingChunks;
    @Shadow(remap = false) public abstract float getSize();
    @Unique private boolean skycraft$notified;
    @Shadow(remap = false) protected abstract boolean isDestroyable(BlockState state);
    @Unique private SkyDigBlast skycraft$section;

    @Inject(method = "removeChunk", remap = false, at = @At("HEAD"))
    private void skycraft$begin(int radius, CallbackInfo ci) {
        this.skycraft$section = null;
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel level && !this.destroyingChunks.empty()) {
            if (!this.skycraft$notified && SkyLink.active()) {
                this.skycraft$notified = true;
                SkyLink.pushEvent(Proto.EV_EXPLOSION, 0, (float) self.getX(), (float) self.getY(),
                    (float) self.getZ(), (float) Math.ceil(this.getSize()) * 15, 0);
            }
            this.skycraft$section = SkyDigBlast.section(level, this.destroyingChunks.peek());
        }
    }

    // Ordinal zero is the cell already selected by the nuke's radius/noise tests.
    // The later read is the cell below it for fire placement and must stay untouched.
    @WrapOperation(method = "removeChunk", remap = true, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0))
    private BlockState skycraft$terrain(Level level, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(level, pos);
        if (this.skycraft$section == null || !state.isAir()) return state;
        BlockState virtual = this.skycraft$section.virtualState(pos, state);
        if (!virtual.isAir() && this.isDestroyable(virtual)) {
            this.skycraft$section.materialize(List.of(pos.immutable()), true);
            return original.call(level, pos);
        }
        return state;
    }

    @Inject(method = "removeChunk", remap = false, at = @At("RETURN"))
    private void skycraft$finish(int radius, CallbackInfo ci) {
        if (this.skycraft$section != null) this.skycraft$section.finish();
        this.skycraft$section = null;
    }
}
