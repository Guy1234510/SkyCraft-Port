package dev.skycraft.client.mixin;
import dev.skycraft.client.render.ModRenderCompat;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animate the captured player's active hand while eating/drinking in F5. */
@Mixin(HumanoidModel.class)
public abstract class ConsumePoseMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void skycraft$consume(LivingEntity entity, float limbSwing, float limbAmount, float age, float yaw, float pitch, CallbackInfo ci) {
        if (!ModRenderCompat.capturing() || !(entity instanceof Player player) || !player.isUsingItem()) return;
        UseAnim action = player.getUseItem().getUseAnimation();
        if (action != UseAnim.EAT && action != UseAnim.DRINK) return;
        boolean right = player.getMainArm() == HumanoidArm.RIGHT;
        if (player.getUsedItemHand() != InteractionHand.MAIN_HAND) right = !right;
        ModelPart arm = right ? rightArm : leftArm;
        float wave = (float)Math.sin((player.getUseItemRemainingTicks() - (age - player.tickCount)) * 1.5f);
        arm.xRot = -1.35f + head.xRot * 0.6f + wave * 0.08f;
        arm.yRot = head.yRot + (right ? -0.18f : 0.18f);
        arm.zRot = (right ? -1 : 1) * (0.12f + wave * 0.03f);
    }
}
