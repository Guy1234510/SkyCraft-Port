package dev.skycraft.client.mixin;

import dev.skycraft.SkyCraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mod's empty mirror has experimental worldgen metadata. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @org.spongepowered.asm.mixin.gen.Invoker("doLoadLevel")
    protected abstract void skycraft$loadMirror(Screen screen, String world, boolean safeMode, boolean prompt);

    @Inject(method = "loadLevel", at = @At("HEAD"), cancellable = true)
    private void skycraft$openMirror(Screen screen, String world, CallbackInfo ci) {
        if (SkyCraft.WORLD_NAME.equals(world)) {
            this.skycraft$loadMirror(screen, world, false, false);
            ci.cancel();
        }
    }

    @Inject(method = "askForBackup", at = @At("HEAD"), cancellable = true)
    private void skycraft$skipBackupPrompt(Screen screen, String world, boolean customized, Runnable proceed, CallbackInfo ci) {
        if (SkyCraft.WORLD_NAME.equals(world)) { proceed.run(); ci.cancel(); }
    }
}
