package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.skycraft.SkyCraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Optional rendering APIs, resolved once without requiring Create or Flywheel to be installed. */
public final class ModRenderCompat {
	private static final ThreadLocal<Integer> CAPTURE = ThreadLocal.withInitial(() -> 0);
	private static boolean resolved, warned;
	private static Object outliner, adaptedBuffers;
	private static MultiBufferSource destination;
	private static Method renderOutlines;
	private static Method holdActive, staffRotating;
	private static Object staffHandler;
	private static boolean cullingResolved, cullingOverridden;
	private static Object cullingConfig;
	private static java.lang.reflect.Field skipCulling;
	private static boolean originalSkipCulling;

	/** The skipped vanilla world render cannot refresh Entity Culling's visibility tags. */
	public static void syncCulling(boolean linked) {
		// Do not initialize optional client mods from the title screen/startup hook.
		if (!linked && !cullingOverridden) return;
		if (cullingResolved && skipCulling == null) return;
		try {
			if (!cullingResolved) {
				Class<?> type = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
				Object instance = type.getField("instance").get(null);
				if (instance == null) return;
				Object config = type.getField("config").get(instance);
				// Instance and config become ready at different points in mod startup.
				// Retry on a later linked tick instead of caching an incomplete result.
				if (config == null) return;
				java.lang.reflect.Field field = config.getClass().getField("skipEntityCulling");
				cullingConfig = config;
				skipCulling = field;
				cullingResolved = true;
			}
			if (skipCulling == null) return;
			if (linked) {
				if (!cullingOverridden) {
					originalSkipCulling = skipCulling.getBoolean(cullingConfig);
					cullingOverridden = true;
					SkyCraft.LOG.info("SkyCraft: suspending Entity Culling while linked; full entity/projectile ticks restored");
				}
				skipCulling.setBoolean(cullingConfig, true);
			} else if (cullingOverridden) {
				skipCulling.setBoolean(cullingConfig, originalSkipCulling);
				cullingOverridden = false;
			}
		} catch (ClassNotFoundException ignored) {
			// Optional mod is not installed.
			cullingResolved = true;
		} catch (ReflectiveOperationException | LinkageError error) {
			SkyCraft.LOG.warn("SkyCraft: Entity Culling compatibility unavailable", error);
			skipCulling = null;
			cullingResolved = true;
		}
	}

	private ModRenderCompat() {}
	public static boolean capturing() { return CAPTURE.get() > 0; }
	public static void begin() { CAPTURE.set(CAPTURE.get() + 1); }
	public static void end() { CAPTURE.set(Math.max(0, CAPTURE.get() - 1)); }

	private static void resolve() {
		if (resolved) return;
		resolved = true;
		try {
			Class<?> type = Class.forName("net.createmod.catnip.outliner.Outliner");
			Class<?> bufferType = Class.forName("net.createmod.catnip.render.SuperRenderTypeBuffer");
			outliner = type.getMethod("getInstance").invoke(null);
			renderOutlines = type.getMethod("renderOutlines", PoseStack.class, bufferType, Vec3.class, float.class);
			adaptedBuffers = Proxy.newProxyInstance(bufferType.getClassLoader(), new Class<?>[] { bufferType }, (proxy, method, args) -> {
				if (com.mojang.blaze3d.vertex.VertexConsumer.class.isAssignableFrom(method.getReturnType()) && args != null && args.length == 1) return destination.getBuffer((net.minecraft.client.renderer.RenderType) args[0]);
				if (com.mojang.blaze3d.vertex.VertexConsumer.class.isAssignableFrom(method.getReturnType()) && args != null && args.length == 1) return destination.getBuffer((net.minecraft.client.renderer.RenderType) args[0]);
				return switch (method.getName()) {
					case "getBuffer", "getEarlyBuffer", "getLateBuffer" -> destination.getBuffer((net.minecraft.client.renderer.RenderType) args[0]);
					case "draw" -> null; // CPU captures are flushed by AvatarExporter.
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "SkyCraft outline capture";
					default -> throw new UnsupportedOperationException(method.toString());
				};
			});
		} catch (ClassNotFoundException ignored) {
			// Create is optional.
		} catch (ReflectiveOperationException | LinkageError error) {
			SkyCraft.LOG.warn("SkyCraft: Create outline API unavailable", error);
			renderOutlines = null;
		}
		try {
			holdActive = Class.forName("dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager").getMethod("isActive");
		} catch (ClassNotFoundException ignored) {
		} catch (ReflectiveOperationException | LinkageError error) {
			SkyCraft.LOG.warn("SkyCraft: held-interaction API unavailable", error);
		}
	}

	private static boolean staffResolved;
    private static boolean staffHoldsMouse() {
        if (!staffResolved) {
            staffResolved = true;
            try {
                staffHandler = Class.forName("dev.simulated_team.simulated.SimulatedClient").getField("PHYSICS_STAFF_CLIENT_HANDLER").get(null);
                staffRotating = staffHandler.getClass().getDeclaredMethod("isRotating");
                staffRotating.setAccessible(true);
            } catch (ClassNotFoundException ignored) {
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                SkyCraft.LOG.warn("SkyCraft: Physics Staff rotation API unavailable", error);
            }
        }
        if (staffRotating != null) try { return Boolean.TRUE.equals(staffRotating.invoke(staffHandler)); }
        catch (ReflectiveOperationException error) {
            SkyCraft.LOG.warn("SkyCraft: couldn't query Physics Staff rotation", error);
            staffRotating = null;
        }
        return false;
    }

	public static boolean holdsMouse() {
		resolve();
		if (staffHoldsMouse()) return true;
		if (holdActive == null) return false;
		try { return Boolean.TRUE.equals(holdActive.invoke(null)); }
		catch (ReflectiveOperationException error) {
			SkyCraft.LOG.warn("SkyCraft: couldn't query held mouse interaction", error);
			holdActive = null;
			return false;
		}
	}

	public static void outlines(PoseStack pose, MultiBufferSource buffers, Vec3 camera, float partialTick) {
		resolve();
		if (renderOutlines == null) return;
		destination = buffers;
		try {
			renderOutlines.invoke(outliner, pose, adaptedBuffers, camera, partialTick);
		} catch (ReflectiveOperationException | RuntimeException error) {
			if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't capture Create outlines", error); }
		} finally { destination = null; }
	}
}
