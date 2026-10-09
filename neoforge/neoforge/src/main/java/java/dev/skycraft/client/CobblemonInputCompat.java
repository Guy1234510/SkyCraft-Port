package dev.skycraft.client;

import java.lang.reflect.Method;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import dev.skycraft.SkyCraft;

/** Blocking Cobblemon bindings sample down/up instead of consuming queued clicks. */
public final class CobblemonInputCompat {
    private static boolean resolved, warned;
    private static Class<?> blocking;
    private static Method tick, setWasDown;
    private CobblemonInputCompat() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            blocking = Class.forName("com.cobblemon.mod.common.client.keybind.CobblemonBlockingKeyBinding");
            tick = blocking.getMethod("onTick");
            setWasDown = blocking.getMethod("setWasDown", boolean.class);
        } catch (ClassNotFoundException ignored) {
            // Optional mod; ordinary modpacks keep vanilla input behavior.
        } catch (ReflectiveOperationException | LinkageError error) {
            tick = null;
            SkyCraft.LOG.warn("SkyCraft: Cobblemon blocking input API unavailable", error);
        }
    }

    /** Observe each replayed transition before a later transition in the same drain hides it. */
    public static void edge(Minecraft minecraft, int keyCode, int scan, boolean down) {
        resolve();
        if (tick == null || minecraft.player == null || minecraft.screen != null) return;
        try {
            for (KeyMapping mapping : minecraft.options.keyMappings) {
                if (blocking.isInstance(mapping) && mapping.matches(keyCode, scan)) {
                    // The original KeyboardHandler has already updated this binding.
                    // Its normal onTick guards transitions via wasDown, so the ordinary
                    // client tick does not duplicate press/release actions.
                    tick.invoke(mapping);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't forward Cobblemon key edge", error); }
        }
    }

    /** Focus/link loss cancels a held action instead of launching a Pokemon on synthetic release. */
    public static void cancel(Minecraft minecraft) {
        if (setWasDown == null) return;
        try {
            for (KeyMapping mapping : minecraft.options.keyMappings) {
                if (blocking.isInstance(mapping)) setWasDown.invoke(mapping, false);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't cancel Cobblemon held input", error); }
        }
    }
}
