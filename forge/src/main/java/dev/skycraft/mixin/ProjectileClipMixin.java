package dev.skycraft.mixin;
import dev.skycraft.world.ProjectileRaycast;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;

/** Map the inherited method while keeping its special invocation outside Mixin remapping. */
@Mixin(Level.class)
@Implements(@Interface(iface = BlockGetter.class, prefix = "skycraft$", remap = Interface.Remap.ALL))
public abstract class ProjectileClipMixin implements BlockGetter {
    public BlockHitResult skycraft$clip(ClipContext context) {
        return ProjectileRaycast.clip((Level)(Object)this, context);
    }
}
