package dev.skycraft.mixin;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bounded diagnostics for real block placement, on both sides of the ordinary packet. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Unique private static int skycraft$placementLogs;

    @Inject(method = "place", at = @At("RETURN"))
    private void skycraft$placementResult(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!SkyLink.active() || skycraft$placementLogs++ >= 32) return;
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var state = ((BlockItem)(Object)this).getBlock().defaultBlockState();
        var player = context.getPlayer();
        var collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        SkyCraft.LOG.info("SkyCraft: placement {} item {} at {} click {} result {} state {} replaceable {} survives {} unobstructed {}",
            level.isClientSide ? "client" : "server", (Object)this, pos, context.getClickLocation(), cir.getReturnValue(),
            level.getBlockState(pos), level.getBlockState(pos).canBeReplaced(context), state.canSurvive(level, pos),
            level.isUnobstructed(state, pos, collision));
    }
}
