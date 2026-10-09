package dev.skycraft.world;

import net.minecraft.world.phys.Vec3;

/** Exact native impact, retained only while an arrow is stuck in Skyrim geometry. */
public interface ArrowSurfaceAttachment {
    Vec3 skycraft$skyrimSurface();
}
