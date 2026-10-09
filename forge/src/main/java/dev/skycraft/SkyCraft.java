package dev.skycraft;

import dev.skycraft.combat.SkyCombat;
import net.minecraft.world.entity.EquipmentSlot;


import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkyCraft {
	public static final String MOD_ID = "skycraft";
	public static final String WORLD_NAME = "SkyCraft";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	private static final String KIT2_TAG = "skycraft_builder_kit";

	public void onInitialize() {
		SkyCombat.init();
		dev.skycraft.net.SkyNet.init();
		dev.skycraft.world.SkyDig.init();
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStartedEvent event) -> configureServer(event.getServer()));
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) -> {
			if (event.getEntity() instanceof ServerPlayer player) {
				giveStarterKit(player);
				giveBuilderKit(player);
				dressTestGuest(player);
			}
		});
	}

	/** The mirror world is a void that only exists to host the player; Skyrim drives time and spawning. */
	private static void configureServer(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
		rules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, server);
		rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, server);
		rules.getRule(GameRules.RULE_DOINSOMNIA).set(false, server);
		rules.getRule(GameRules.RULE_DISABLE_ELYTRA_MOVEMENT_CHECK).set(true, server);
		rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
		rules.getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
		rules.getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(false, server);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
		LOG.info("SkyCraft: mirror world configured");
	}

	/**
	 * Local multiplayer test guests (tools/fake_guest.py; named Guest, Guest2, ...) wear a random
	 * mix of iron and diamond armour, so they're easy to tell apart.
	 */
	private static void dressTestGuest(ServerPlayer player) {
		if (!player.getName().getString().startsWith("Guest")) {
			return;
		}
		var random = player.getRandom();
		EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
		net.minecraft.world.item.Item[][] pieces = {
			{ Items.IRON_HELMET, Items.DIAMOND_HELMET },
			{ Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE },
			{ Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS },
			{ Items.IRON_BOOTS, Items.DIAMOND_BOOTS },
		};
		for (int i = 0; i < slots.length; i++) {
			player.setItemSlot(slots[i], new ItemStack(pieces[i][random.nextBoolean() ? 1 : 0]));
		}
		LOG.info("SkyCraft: dressed test guest {} in iron and diamond", player.getName().getString());
	}

	private static void giveStarterKit(ServerPlayer player) {
		if (!player.getInventory().isEmpty()) {
			return;
		}
		player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
		player.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
		player.getInventory().add(new ItemStack(Items.BOW));
		player.getInventory().add(new ItemStack(Items.COOKED_BEEF, 32));
		player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
		player.getInventory().add(new ItemStack(Items.TORCH, 32));
		player.getInventory().add(new ItemStack(Items.ARROW, 64));
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		LOG.info("SkyCraft: gave starter kit to {}", player.getName().getString());
	}

	/**
	 * Once per player: armor (Skyrim's enemies hit back now) and building materials, since there is
	 * no Minecraft terrain to mine in Skyrim.
	 */
	private static void giveBuilderKit(ServerPlayer player) {
		if (player.getTags().contains(KIT2_TAG)) {
			return;
		}
		equipIfEmpty(player, EquipmentSlot.HEAD, Items.IRON_HELMET);
		equipIfEmpty(player, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
		equipIfEmpty(player, EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
		equipIfEmpty(player, EquipmentSlot.FEET, Items.IRON_BOOTS);
		var inventory = player.getInventory();
		inventory.add(new ItemStack(Items.COBBLESTONE, 64));
		inventory.add(new ItemStack(Items.STONE_BRICKS, 64));
		inventory.add(new ItemStack(Items.OAK_LOG, 64));
		inventory.add(new ItemStack(Items.GLASS, 64));
		inventory.add(new ItemStack(Items.OAK_STAIRS, 64));
		inventory.add(new ItemStack(Items.OAK_SLAB, 64));
		inventory.add(new ItemStack(Items.OAK_DOOR, 8));
		inventory.add(new ItemStack(Items.LADDER, 32));
		inventory.add(new ItemStack(Items.LANTERN, 16));
		inventory.add(new ItemStack(Items.CRAFTING_TABLE));
		inventory.add(new ItemStack(Items.WATER_BUCKET));
		inventory.add(new ItemStack(Items.ARROW, 64));
		inventory.add(new ItemStack(Items.GOLDEN_APPLE, 4));
		player.addTag(KIT2_TAG);
		LOG.info("SkyCraft: gave builder kit to {}", player.getName().getString());
	}

	private static void equipIfEmpty(ServerPlayer player, EquipmentSlot slot, net.minecraft.world.item.Item item) {
		if (player.getItemBySlot(slot).isEmpty()) {
			player.setItemSlot(slot, new ItemStack(item));
		} else {
			player.getInventory().add(new ItemStack(item));
		}
	}
}
