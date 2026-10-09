package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.world.SkyDig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * The pause menu's "Skyrim destruction" button (its top left corner, clear of the menu at any GUI
 * scale) and its setting, {@code destruction=} in config/skycraft.properties (see SkyDig.destruction).
 */
public final class DestructionToggle {
	private static final String KEY = "destruction";

	private DestructionToggle() {
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("skycraft.properties");
	}

	public static void register() {
		load();
		MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
			var screen = event.getScreen();
			var minecraft = Minecraft.getInstance();
			if (screen instanceof PauseScreen pause && ((dev.skycraft.client.mixin.PauseScreenAccessor) pause).skycraft$showsPauseMenu() && minecraft.player != null) {
				event.addListener(button(minecraft));
			}
		});
	}

	private static Button button(Minecraft minecraft) {
		// The host's server does all the digging, so in a friend's world it's their setting.
		boolean host = minecraft.hasSingleplayerServer();
		Button button = Button.builder(label(), b -> {
			SkyDig.destruction = !SkyDig.destruction;
			b.setMessage(label());
			save();
			SkyCraft.LOG.info("SkyCraft: Skyrim destruction {}", SkyDig.destruction ? "on" : "off");
		}).bounds(4, 4, 150, 20).tooltip(Tooltip.create(Component.literal(host
			? "Mining Skyrim's ground and rocks, and explosions, dig into Skyrim's world. Holes already dug stay either way."
			: "In a friend's world, their setting decides."))).build();
		button.active = host;
		return button;
	}

	private static Component label() {
		return Component.literal("Skyrim destruction: " + (SkyDig.destruction ? "On" : "Off"));
	}

	private static void load() {
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(file())) {
			props.load(in);
		} catch (NoSuchFileException e) {
			return;
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", file(), e);
			return;
		}
		SkyDig.destruction = !"false".equalsIgnoreCase(props.getProperty(KEY, "true").trim());
	}

	/** Rewrites only its own line, keeping the file's comments and other settings (join=). */
	private static void save() {
		Path file = file();
		String line = KEY + "=" + SkyDig.destruction;
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>(List.of("# SkyCraft"));
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith(KEY + "=")) {
					lines.set(i, line);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Mining and explosions dig into Skyrim's world (the pause menu's \"Skyrim destruction\" button).");
				lines.add(line);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", file, e);
		}
	}
}
