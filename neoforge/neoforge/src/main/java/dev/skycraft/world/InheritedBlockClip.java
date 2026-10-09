package dev.skycraft.world;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

/** Invoke the transformed interface default on the actual level, bypassing its override. */
public final class InheritedBlockClip {
    private InheritedBlockClip() {}

    private static final class DefaultMethod {
        private static final MethodHandle CLIP = resolve();

        private static MethodHandle resolve() {
            // Resolve by descriptor: Forge's production method name is obfuscated.
            Method clip = null;
            for (Method candidate : BlockGetter.class.getDeclaredMethods()) {
                if (candidate.isDefault() && candidate.getReturnType() == BlockHitResult.class
                        && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == ClipContext.class) {
                    if (clip != null) throw new IllegalStateException("Ambiguous BlockGetter clip method");
                    clip = candidate;
                }
            }
            if (clip == null) throw new IllegalStateException("Missing BlockGetter default clip method");
            try {
                return MethodHandles.privateLookupIn(BlockGetter.class, MethodHandles.lookup())
                        .unreflectSpecial(clip, BlockGetter.class);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot access BlockGetter default clip", failure);
            }
        }
    }

    public static BlockHitResult clip(BlockGetter level, ClipContext context) {
        try {
            return (BlockHitResult) DefaultMethod.CLIP.invokeExact(level, context);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("BlockGetter default clip failed", failure);
        }
    }
}
