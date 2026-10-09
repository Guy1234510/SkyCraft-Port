package dev.skycraft.client;

import dev.skycraft.combat.SkyCombat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.glfw.GLFW;

/**
 * Replays Skyrim-captured input into Minecraft's own input handlers, as if the (hidden) MC
 * window had focus. Keeps a virtual keyboard so InputConstants.isKeyDown() still works.
 */
public final class InputBridge {
	private static final boolean[] KEYS = new boolean[512];
	private static final boolean[] BUTTONS = new boolean[8];
	private static double cursorX, cursorY;
	private static int modifiers;
	private static int clickLogs;
	private static final GuiKeyRepeat REPEAT = new GuiKeyRepeat();

	private InputBridge() {
	}

	public static boolean isKeyDown(int keyCode) {
		return keyCode >= 0 && keyCode < KEYS.length && KEYS[keyCode];
	}

	public static void drain(Minecraft minecraft) {
		SkyLink.drainInput((type, code, a, b, c) -> dispatch(minecraft, type, code, a, b, c));
		if (REPEAT.due(minecraft.screen, System.nanoTime())) {
			var screen = minecraft.screen;
			minecraft.keyboardHandler.keyPress(minecraft.getWindow().getWindow(), REPEAT.key(), REPEAT.scan(), GLFW.GLFW_REPEAT, modifiers);
			if (minecraft.screen == screen && REPEAT.codePoint() != 0 && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) == 0) {
				((dev.skycraft.client.mixin.KeyboardHandlerAccessor) minecraft.keyboardHandler).skycraft$charTyped(
					minecraft.getWindow().getWindow(), REPEAT.codePoint(), modifiers);
			}
		}
	}

	private static void dispatch(Minecraft minecraft, int type, int code, int a, int b, int c) {
		long handle = minecraft.getWindow().getWindow();
		switch (type) {
			case Proto.IN_KEY -> key(minecraft, handle, code, a != 0);
			case Proto.IN_MOUSE_BUTTON -> {
				if (code > 0 && code < BUTTONS.length) {
					if (BUTTONS[code] == (a != 0)) break;
					BUTTONS[code] = a != 0;
				}
				if (a != 0 && clickLogs++ < 20) {
					var hit = minecraft.hitResult;
					dev.skycraft.SkyCraft.LOG.info("SkyCraft: click {} -> {} {} (grabbed {}, screen {})", code, hit == null ? "null" : hit.getType(),
						hit instanceof net.minecraft.world.phys.EntityHitResult eh ? eh.getEntity().getName().getString() : hit == null ? "" : hit.getLocation(),
						minecraft.mouseHandler.isMouseGrabbed(), minecraft.screen);
				}
				int button = switch (code) {
					case 1 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
					case 2 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
				case 3 -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
				case 4 -> GLFW.GLFW_MOUSE_BUTTON_4;
				case 5 -> GLFW.GLFW_MOUSE_BUTTON_5;
				default -> -1;
				};
				if (button >= 0) ((dev.skycraft.client.mixin.MouseHandlerAccessor) minecraft.mouseHandler).skycraft$onPress(
					handle, button, a != 0 ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, modifiers);
			}
			case Proto.IN_SCROLL -> ((dev.skycraft.client.mixin.MouseHandlerAccessor) minecraft.mouseHandler).skycraft$onScroll(handle, 0.0, a / 120.0);
			case Proto.IN_CURSOR -> {
				double dx = a - cursorX;
				double dy = b - cursorY;
				cursorX = a;
				cursorY = b;
				((dev.skycraft.client.mixin.MouseHandlerAccessor) minecraft.mouseHandler).skycraft$onMove(handle, a, b);
			}
			case Proto.IN_MOUSE_DELTA -> {
				if (minecraft.screen == null && minecraft.player != null) {
					var mouse = minecraft.mouseHandler;
					((dev.skycraft.client.mixin.MouseHandlerAccessor) mouse).skycraft$onMove(handle, mouse.xpos() + a, mouse.ypos() + b);
				}
			}
			case Proto.IN_TEXT -> {
				// Skyrim sends Unicode code points. Reject stale/encoded key values so
				// a malformed native event cannot turn into random characters.
				if (minecraft.screen != null && textAllowed() && a >= 0 && a <= Character.MAX_CODE_POINT && Character.isValidCodePoint(a)) {
					REPEAT.text(a, System.nanoTime());
					((dev.skycraft.client.mixin.KeyboardHandlerAccessor) minecraft.keyboardHandler).skycraft$charTyped(handle, a, modifiers);
				}
			}
			case Proto.IN_RELEASE_ALL -> releaseAll();
			case Proto.IN_HURT -> hurt(minecraft, code, a / 100.0F, b, c);
            case Proto.IN_FIRE_REGION -> {
                var server = minecraft.getSingleplayerServer();
                if (server != null && minecraft.player != null) {
                    var uuid = minecraft.player.getUUID();
                    server.execute(() -> {
                        var player = server.getPlayerList().getPlayer(uuid);
                        if (player != null) dev.skycraft.world.SkyNativeFire.accept(player.serverLevel(), code, a, b, c);
                    });
                }
            }
			case Proto.IN_OPEN_MENU -> {
				if (minecraft.screen == null && minecraft.player != null) {
					releaseAll();
					minecraft.setScreen(new PauseScreen(true));
				}
			}
			default -> {
			}
		}
	}

	/** Skyrim hit the player: apply it as Minecraft damage on the integrated server (or the host's). */
	private static void hurt(Minecraft minecraft, int kind, float skyrimDamage, int attacker, int flags) {
		var server = minecraft.getSingleplayerServer();
		if (minecraft.player == null) {
			return;
		}
		if (server == null) {
			// A guest in a friend's world: the host's server applies it.
			if (dev.skycraft.net.SkyNet.canSend()) {
				dev.skycraft.net.SkyNet.sendToServer(new dev.skycraft.net.SkyNet.Hurt(kind, skyrimDamage, attacker, flags));
			}
			return;
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				SkyCombat.hurtPlayer(player, kind, skyrimDamage, attacker, flags);
			}
		});
	}

	private static void key(Minecraft minecraft, long handle, int scancode, boolean down) {
		int keyCode = glfwKeyFromHid(scancode);
		if (keyCode < 0 || keyCode >= KEYS.length) {
			return;
		}
		boolean wasDown = KEYS[keyCode];
		// Vanilla toggles F3 on RELEASE; a duplicate release toggles it twice.
		if (!down && !wasDown) return;
		if (down && wasDown && !repeatable(keyCode)) return;
		if (!down) REPEAT.release(keyCode);
		else if (minecraft.screen != null && repeatable(keyCode)) {
			if (wasDown) REPEAT.sourceRepeat(System.nanoTime());
			else REPEAT.press(keyCode, scancode, minecraft.screen, System.nanoTime());
		}
		KEYS[keyCode] = down;
		updateModifiers();
		int action = down ? (wasDown ? GLFW.GLFW_REPEAT : GLFW.GLFW_PRESS) : GLFW.GLFW_RELEASE;
		minecraft.keyboardHandler.keyPress(handle, keyCode, scancode, action, modifiers);
		if (down != wasDown) CobblemonInputCompat.edge(minecraft, keyCode, scancode, down);
	}

	private static boolean textAllowed() {
		// AltGr may produce text with Ctrl+Right Alt. Ordinary shortcuts must
		// never emit a character after selecting/pasting/cutting text.
		return (modifiers & GLFW.GLFW_MOD_SUPER) == 0 &&
			((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0 || KEYS[GLFW.GLFW_KEY_RIGHT_ALT]);
	}

	private static boolean repeatable(int key) {
		return (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT)
			|| (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_EQUAL && key != GLFW.GLFW_KEY_KP_ENTER)
			|| key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_TAB
			|| (key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_END);
	}

	private static void updateModifiers() {
		int m = 0;
		if (KEYS[GLFW.GLFW_KEY_LEFT_SHIFT] || KEYS[GLFW.GLFW_KEY_RIGHT_SHIFT]) m |= GLFW.GLFW_MOD_SHIFT;
		if (KEYS[GLFW.GLFW_KEY_LEFT_CONTROL] || KEYS[GLFW.GLFW_KEY_RIGHT_CONTROL]) m |= GLFW.GLFW_MOD_CONTROL;
		if (KEYS[GLFW.GLFW_KEY_LEFT_ALT] || KEYS[GLFW.GLFW_KEY_RIGHT_ALT]) m |= GLFW.GLFW_MOD_ALT;
		modifiers = m;
	}

	/** Converts the USB HID keyboard usages sent by Skyrim into GLFW key values used by 1.21.1. */
	private static int glfwKeyFromHid(int hid) {
		if (hid >= 4 && hid <= 29) return GLFW.GLFW_KEY_A + hid - 4;
		if (hid >= 30 && hid <= 38) return GLFW.GLFW_KEY_1 + hid - 30;
		if (hid >= 58 && hid <= 69) return GLFW.GLFW_KEY_F1 + hid - 58;
		if (hid >= 89 && hid <= 97) return GLFW.GLFW_KEY_KP_1 + hid - 89;
		return switch (hid) {
			case 39 -> GLFW.GLFW_KEY_0;
			case 40 -> GLFW.GLFW_KEY_ENTER;
			case 41 -> GLFW.GLFW_KEY_ESCAPE;
			case 42 -> GLFW.GLFW_KEY_BACKSPACE;
			case 43 -> GLFW.GLFW_KEY_TAB;
			case 44 -> GLFW.GLFW_KEY_SPACE;
			case 45 -> GLFW.GLFW_KEY_MINUS;
			case 46 -> GLFW.GLFW_KEY_EQUAL;
			case 47 -> GLFW.GLFW_KEY_LEFT_BRACKET;
			case 48 -> GLFW.GLFW_KEY_RIGHT_BRACKET;
			case 49 -> GLFW.GLFW_KEY_BACKSLASH;
			case 51 -> GLFW.GLFW_KEY_SEMICOLON;
			case 52 -> GLFW.GLFW_KEY_APOSTROPHE;
			case 53 -> GLFW.GLFW_KEY_GRAVE_ACCENT;
			case 54 -> GLFW.GLFW_KEY_COMMA;
			case 55 -> GLFW.GLFW_KEY_PERIOD;
			case 56 -> GLFW.GLFW_KEY_SLASH;
			case 57 -> GLFW.GLFW_KEY_CAPS_LOCK;
			case 70 -> GLFW.GLFW_KEY_PRINT_SCREEN;
			case 71 -> GLFW.GLFW_KEY_SCROLL_LOCK;
			case 72 -> GLFW.GLFW_KEY_PAUSE;
			case 73 -> GLFW.GLFW_KEY_INSERT;
			case 74 -> GLFW.GLFW_KEY_HOME;
			case 75 -> GLFW.GLFW_KEY_PAGE_UP;
			case 76 -> GLFW.GLFW_KEY_DELETE;
			case 77 -> GLFW.GLFW_KEY_END;
			case 78 -> GLFW.GLFW_KEY_PAGE_DOWN;
			case 79 -> GLFW.GLFW_KEY_RIGHT;
			case 80 -> GLFW.GLFW_KEY_LEFT;
			case 81 -> GLFW.GLFW_KEY_DOWN;
			case 82 -> GLFW.GLFW_KEY_UP;
			case 83 -> GLFW.GLFW_KEY_NUM_LOCK;
			case 84 -> GLFW.GLFW_KEY_KP_DIVIDE;
			case 85 -> GLFW.GLFW_KEY_KP_MULTIPLY;
			case 86 -> GLFW.GLFW_KEY_KP_SUBTRACT;
			case 87 -> GLFW.GLFW_KEY_KP_ADD;
			case 88 -> GLFW.GLFW_KEY_KP_ENTER;
			case 98 -> GLFW.GLFW_KEY_KP_0;
			case 99 -> GLFW.GLFW_KEY_KP_DECIMAL;
			case 100 -> GLFW.GLFW_KEY_WORLD_1;
			case 101 -> GLFW.GLFW_KEY_MENU;
			case 224 -> GLFW.GLFW_KEY_LEFT_CONTROL;
			case 225 -> GLFW.GLFW_KEY_LEFT_SHIFT;
			case 226 -> GLFW.GLFW_KEY_LEFT_ALT;
			case 227 -> GLFW.GLFW_KEY_LEFT_SUPER;
			case 228 -> GLFW.GLFW_KEY_RIGHT_CONTROL;
			case 229 -> GLFW.GLFW_KEY_RIGHT_SHIFT;
			case 230 -> GLFW.GLFW_KEY_RIGHT_ALT;
			case 231 -> GLFW.GLFW_KEY_RIGHT_SUPER;
			default -> GLFW.GLFW_KEY_UNKNOWN;
		};
	}

	/** Lift every key and button we think is held (focus moved to Skyrim, link dropped, ...). */
	public static void releaseAll() {
		REPEAT.reset();
		Minecraft minecraft = Minecraft.getInstance();
		CobblemonInputCompat.cancel(minecraft);
		long handle = minecraft.getWindow().getWindow();
		for (int keyCode = 0; keyCode < KEYS.length; keyCode++) {
			if (KEYS[keyCode]) {
				KEYS[keyCode] = false;
				updateModifiers();
				minecraft.keyboardHandler.keyPress(handle, keyCode, 0, GLFW.GLFW_RELEASE, modifiers);
			}
		}
		for (int button = 1; button < BUTTONS.length; button++) {
			if (BUTTONS[button]) {
				BUTTONS[button] = false;
				int glfwButton = switch (button) {
					case 1 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
					case 2 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
					case 3 -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
					case 4 -> GLFW.GLFW_MOUSE_BUTTON_4;
					case 5 -> GLFW.GLFW_MOUSE_BUTTON_5;
					default -> -1;
				};
				if (glfwButton >= 0) ((dev.skycraft.client.mixin.MouseHandlerAccessor) minecraft.mouseHandler)
					.skycraft$onPress(handle, glfwButton, GLFW.GLFW_RELEASE, modifiers);
			}
		}
	}
}
