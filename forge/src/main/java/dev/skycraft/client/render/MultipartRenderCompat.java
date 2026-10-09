package dev.skycraft.client.render;

import dev.skycraft.SkyCraft;
import java.lang.reflect.Method;
import java.util.Iterator;
import net.minecraft.world.entity.Entity;

/** Use Twilight Forest's own iterator so TFPart segments keep their registered renderers. */
public final class MultipartRenderCompat {
	private static boolean resolved, warned;
	private static Object utility;
	private static Method injectParts;
	private MultipartRenderCompat() {}

	public static Iterable<Entity> entities(Iterable<Entity> entities) {
		if (!resolved) {
			resolved = true;
			try {
				Class<?> type = Class.forName("twilightforest.util.multiparts.MultipartEntityUtil");
				utility = type.getConstructor().newInstance();
				injectParts = type.getMethod("injectTFPartEntities", Iterator.class);
			} catch (ClassNotFoundException ignored) {
			} catch (ReflectiveOperationException | LinkageError error) {
				SkyCraft.LOG.warn("SkyCraft: Twilight Forest multipart API unavailable", error);
			}
		}
		if (injectParts == null) return entities;
		return () -> wrap(entities.iterator());
	}

	@SuppressWarnings("unchecked")
	private static Iterator<Entity> wrap(Iterator<Entity> entities) {
		try { return (Iterator<Entity>) injectParts.invoke(utility, entities); }
		catch (ReflectiveOperationException | RuntimeException error) {
			if (!warned) { warned = true; SkyCraft.LOG.warn("SkyCraft: couldn't capture multipart entities", error); }
			return entities;
		}
	}
}
