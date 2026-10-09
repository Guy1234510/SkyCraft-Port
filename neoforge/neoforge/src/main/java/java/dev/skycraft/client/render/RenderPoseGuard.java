package dev.skycraft.client.render;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** A second renderer invocation must not accumulate changes to entity head/body rotations. */
final class RenderPoseGuard implements AutoCloseable {
    private final LivingEntity entity;
    private final float body, oldBody, head, oldHead, yaw, oldYaw;

    RenderPoseGuard(Entity input) {
        entity = input instanceof LivingEntity living ? living : null;
        body = entity == null ? 0 : entity.yBodyRot;
        oldBody = entity == null ? 0 : entity.yBodyRotO;
        head = entity == null ? 0 : entity.yHeadRot;
        oldHead = entity == null ? 0 : entity.yHeadRotO;
        yaw = input.getYRot(); oldYaw = input.yRotO;
        if (entity != null) {
            entity.setYRot(Mth.wrapDegrees(yaw));
            entity.yRotO = entity.getYRot()+Mth.wrapDegrees(oldYaw-yaw);
            entity.yBodyRot = Mth.wrapDegrees(body);
            entity.yBodyRotO = entity.yBodyRot+Mth.wrapDegrees(oldBody-body);
            entity.yHeadRot = entity.yBodyRot+Mth.clamp(Mth.wrapDegrees(head-body),-85,85);
            entity.yHeadRotO = entity.yBodyRotO+Mth.clamp(Mth.wrapDegrees(oldHead-oldBody),-85,85);
        }
    }

    public void close() {
        if (entity == null) return;
        entity.setYRot(yaw); entity.yRotO = oldYaw;
        entity.yBodyRot = body; entity.yBodyRotO = oldBody;
        entity.yHeadRot = head; entity.yHeadRotO = oldHead;
    }
}
