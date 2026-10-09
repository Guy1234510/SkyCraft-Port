package dev.skycraft.client.mixin;

import net.minecraft.client.server.IntegratedPlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * A world opened to friends takes up to 100 players, not Minecraft's fixed 8 for LAN worlds (it's the
 * host's own PC doing the serving, over e4mc).
 */
@Mixin(IntegratedPlayerList.class)
public abstract class IntegratedServerMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 8))
	private static int skycraft$morePlayers(int original) {
		return 100;
	}
}
