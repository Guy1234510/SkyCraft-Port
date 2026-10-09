package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.render.WorldExporter;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

/**
 * Per-frame glue between the Minecraft client and Skyrim. Everything here runs on the render
 * thread, called from MinecraftMixin.
 */
public final class SkyClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("skycraft.showWindow");
	// Started by Skyrim (SkyCraft's bundled instance passes -Dskycraft.startHidden=true): no window and
	// no title-screen music from the first frame, even while Skyrim is paused (Alt-Tabbed) and the
	// two haven't linked up yet. Otherwise the window only goes once Skyrim is there.
	private static final boolean START_HIDDEN = Boolean.getBoolean("skycraft.startHidden");
	private static boolean startedHidden;

	private static final SkyLink.SkyState sky = new SkyLink.SkyState();
	private static final SkyLink.McState mc = new SkyLink.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;

	// Teleport / hold state: Skyrim decides where the player is after loads, doors and respawns.
	private static int lastTeleportSeq = -1;
	private static int teleportAck;
	private static boolean teleportPending;
	private static LocalPlayer lastPlayer;
	private static Vec3 holdPos;
	private static Vec3 unlinkedHold;
	private static long holdSince;
	private static Vec3 lastSupportedPos;
	private static long nextTerrainRecoveryCheck;
	private static int supportedWorld;
	private static int supportedEpoch;
	private static LocalPlayer recoveryPlayer;
	private static int flightRecoveryGrace;
	private static long qpcFreq;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean skyrimStalled;
	private static int exporterErrors;

	private SkyClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * True once Skyrim has connected in this session. From then on Minecraft never touches the
	 * real mouse or keyboard again (even if Skyrim closes), since its window is hidden.
	 */
	public static boolean tookOver() {
		return tookOver;
	}

	public static SkyLink.SkyState sky() {
		return sky;
	}

	/** Start of Minecraft.runTick: pull state and input from Skyrim before anything else runs. */
	public static void beginFrame() {
		SkyLink.poll();
		quitWithSkyrim(Minecraft.getInstance());
		if (START_HIDDEN && !startedHidden) {
			startedHidden = true;
			Minecraft minecraft = Minecraft.getInstance();
			hideWindowOnce(minecraft);
			minecraft.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			minecraft.getMusicManager().stopPlaying();
		}
		boolean nowLinked = SkyLink.active();
		dev.skycraft.client.render.ModRenderCompat.syncCulling(nowLinked);
		if (nowLinked) {
			SkyLink.readSkyState(sky); // on a torn read we simply keep last frame's state
			dev.skycraft.world.SkyWater.refresh();
		} else if (Minecraft.getInstance().level == null) {
            // A minimized Skyrim stops its heartbeat. Keep physical water while
            // the Minecraft world still exists; actual world/disconnect resets
            // invalidate the cache, not a temporary native render pause.
            dev.skycraft.world.SkyWater.clear();
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
			if (linked) {
				tookOver = true;
				unlinkedHold = null;
				SkyCollision.startConsumer();
				applyLinkedOptions();
			} else {
				InputBridge.releaseAll();
				LocalPlayer player = Minecraft.getInstance().player;
				unlinkedHold = player != null ? player.position() : null;
			}
		}
		if (!linked) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		MirrorWorld.openWhenReady(minecraft);

		if (sky.menuOpen() || sky.loading()) {
			InputBridge.releaseAll();
		}
		InputBridge.drain(minecraft);
		ProxySync.frame(minecraft);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}

		// A new player object means we just joined or respawned: put it where Skyrim's player is.
		if (player != lastPlayer) {
			lastPlayer = player;
			teleportPending = true;
		}
		if (sky.teleportSeq != lastTeleportSeq) {
			lastTeleportSeq = sky.teleportSeq;
			teleportPending = true;
		}
		if (teleportPending && sky.inGame() && !sky.loading()) {
			requestTeleport(minecraft, sky.x, sky.y, sky.z, sky.yaw, sky.pitch);
			teleportAck = sky.teleportSeq;
			teleportPending = false;
			holdPos = new Vec3(sky.x, sky.y, sky.z);
		}

		// Look direction is driven by Skyrim (zero-latency camera); MC uses it for everything else.
		if (minecraft.screen == null) {
			player.setYRot(sky.yaw);
			player.setXRot(sky.pitch);
			player.yRotO = sky.yaw;
			player.xRotO = sky.pitch;
		}
	}

	// Minecraft is started with Skyrim (the SKSE plugin launches it), so it goes when that Skyrim has
	// closed for good: saved and shut down the normal way. -Dskycraft.quitWithSkyrim=false keeps it
	// running instead (development: restarting Skyrim without restarting Minecraft).
	private static final boolean QUIT_WITH_SKYRIM = Boolean.parseBoolean(System.getProperty("skycraft.quitWithSkyrim", "true"));
	private static long skyrimGoneSince;
	private static long nextSkyrimCheck;
	// Started hidden by Skyrim but never connected: nobody can see or use this Minecraft, and it
	// would stop the next Skyrim from starting a fresh one ("already running"). It goes after this.
	private static final long NEVER_CONNECTED_QUIT_MS = 10 * 60 * 1000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static boolean gaveUpWaiting;

	private static void quitWithSkyrim(Minecraft minecraft) {
		int pid = SkyLink.skyrimPid();
		long now = System.currentTimeMillis();
		if (QUIT_WITH_SKYRIM && START_HIDDEN && pid == 0 && !tookOver && !gaveUpWaiting && now - STARTED_AT > NEVER_CONNECTED_QUIT_MS) {
			gaveUpWaiting = true;
			SkyCraft.LOG.warn("SkyCraft: started hidden but Skyrim never connected in {} minutes; quitting", NEVER_CONNECTED_QUIT_MS / 60000);
			minecraft.stop();
			return;
		}
		if (!QUIT_WITH_SKYRIM || pid == 0 || now < nextSkyrimCheck) {
			return;
		}
		nextSkyrimCheck = now + 1000;
		if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			skyrimGoneSince = 0;
			return;
		}
		if (skyrimGoneSince == 0) {
			skyrimGoneSince = now;
		} else if (now - skyrimGoneSince > 5000) {
			SkyCraft.LOG.info("SkyCraft: Skyrim (pid {}) has closed; saving and quitting", pid);
			minecraft.stop();
		}
	}

	/** Called at the end of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		MirrorWorld.tick(minecraft);
		DiscordPresence.tick(minecraft);
		SkyDigClient.tick(minecraft);
		freezeWhileUnlinked(minecraft);
		holdUntilReady(minecraft);
		recoverFromVoid(minecraft);
		publishTick(minecraft);
	}

	/**
	 * Skyrim went quiet (a long loading screen, a stall, or it closed). Its collision around the
	 * player may be about to change (interior doors), so keep the player exactly where they were
	 * instead of letting them fall; Skyrim puts them where they belong when it's back.
	 */
	private static void freezeWhileUnlinked(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (linked || !tookOver || player == null) {
			return;
		}
		if (unlinkedHold == null) {
			unlinkedHold = player.position();
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(unlinkedHold.x, unlinkedHold.y, unlinkedHold.z);
		player.xo = unlinkedHold.x;
		player.yo = unlinkedHold.y;
		player.zo = unlinkedHold.z;
		player.resetFallDistance();
	}

	/**
	 * Hands Skyrim the raw physics tick (previous + latest feet, smoothed eye height, walk bob) with a
	 * QueryPerformanceCounter timestamp. Skyrim interpolates between them on its own frame clock,
	 * exactly like Minecraft's renderer does with partial ticks.
	 */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (qpcFreq == 0) {
			qpcFreq = SkyLink.qpcFrequency();
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (DeltaTracker keeps the remainder).
		float remainder = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
		mc.tickQpc = SkyLink.qpc() - (long) (remainder * tickMs * qpcFreq / 1000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.eyeHeightO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.eyeHeightT = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get() && !player.isPassenger();
		mc.walkDistO = bob ? player.walkDistO : 0.0F;
		mc.walkDist = bob ? player.walkDist : 0.0F;
		mc.bobO = bob ? player.oBob : 0.0F;
		mc.bob = bob ? player.bob : 0.0F;
		SkyLink.writeMcState(mc);
	}

	/** Freeze the player until Skyrim's collision around them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!sky.inGame() || sky.loading()) {
			// Skyrim is on its main menu or a loading screen: park the player where they are.
			if (holdPos == null) {
				holdPos = player.position();
			}
			teleportPending = true;
		}
		if (holdPos == null) {
			holdSince = 0;
			return;
		}
		if (holdSince == 0) {
			holdSince = System.currentTimeMillis();
		}
		int bx = (int) Math.floor(holdPos.x), by = (int) Math.floor(holdPos.y), bz = (int) Math.floor(holdPos.z);
		boolean known = SkyCollision.isKnown(bx, by - 1, bz) && SkyCollision.isKnown(bx, by, bz)
			&& SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);
		// Release once there is actual ground below (or after a timeout, e.g. when mid-air on purpose).
		boolean trianglesReady = SkyCollision.hasTriangleCoverage(player.getBoundingBox().inflate(0.05));
		boolean ready = known && trianglesReady && (SkyCollision.hasSolidBelow(bx, by, bz, 12) || System.currentTimeMillis() - holdSince > 6000);
		if (ready && sky.inGame() && !sky.loading()) {
			// Skyrim's feet can sit a fraction of a voxel inside our ground layer. Minecraft's
			// collision never pushes you out of a shape, so you'd drop through: lift out first.
			Vec3 safe = liftOutOfGeometry(player, holdPos);
			if (safe.y != holdPos.y) {
				player.setPos(safe.x, safe.y, safe.z);
				player.yo = safe.y;
				SkyCraft.LOG.info("SkyCraft: lifted player {} blocks out of the ground", String.format("%.3f", safe.y - holdPos.y));
			}
			holdPos = null;
			return;
		}
		player.walkDist = player.walkDistO = 0.0F;
		player.bob = player.oBob = 0.0F;
		player.setSprinting(false);
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(holdPos.x, holdPos.y, holdPos.z);
		player.xo = holdPos.x;
		player.yo = holdPos.y;
		player.zo = holdPos.z;
		player.resetFallDistance();
	}

	private static Vec3 liftOutOfGeometry(LocalPlayer player, Vec3 pos) {
		// Stand on the exact Skyrim ground if it is slightly above the feet (up to 2.5 blocks).
		double ground = SkyCollider.groundAt(pos.x, pos.y, pos.z, 2.5);
		return !Double.isNaN(ground) && ground > pos.y ? new Vec3(pos.x, ground, pos.z) : pos;
	}

	/** Keep a supported position for recovery from a streamed-world fall below the dimension. */
	private static void recoverFromVoid(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null || minecraft.level == null || !sky.inGame() || sky.loading()) return;
		if (recoveryPlayer != player || supportedWorld != sky.worldId || supportedEpoch != sky.collisionEpoch) {
			recoveryPlayer = player;
			flightRecoveryGrace = 0;
			lastSupportedPos = null;
			supportedWorld = sky.worldId;
			supportedEpoch = sky.collisionEpoch;
		}
		if (!player.isAlive() || player.isDeadOrDying() || player.isPassenger() || holdPos != null) return;
		if (lastSupportedPos != null && player.getY() < minecraft.level.getMinBuildHeight() + 16) {
			recoverPlayer(minecraft, player, "below the mirror dimension (flying=" + player.isFallFlying() + ")");
			return;
		}
		if (onMinecraftSupport(minecraft, player)) return;
		// If streamed terrain arrived after the player crossed its surface, lift at the
		// current column rather than leaving them trapped or returning them far downhill.
		if (!player.isSpectator() && !player.getAbilities().flying && !player.isFallFlying()
			&& System.nanoTime() >= nextTerrainRecoveryCheck
			&& lastSupportedPos != null && player.getY() < lastSupportedPos.y
			&& Math.hypot(player.getX() - lastSupportedPos.x, player.getZ() - lastSupportedPos.z) < 24.0) {
			nextTerrainRecoveryCheck = System.nanoTime() + 250_000_000L;
			var dug = dev.skycraft.world.SkyDig.clientDug;
			var cell = player.blockPosition();
			if (dug == null || !dug.isDug(cell.getX(), cell.getY(), cell.getZ())) {
				var probe = new dev.skycraft.world.SkyDig.Probe().around(player.getX(), player.getY(), player.getZ(),
					player.getX(), player.getY() + 8, player.getZ());
				double ground = probe.landHeight(player.getX(), player.getZ());
				if (Double.isFinite(ground) && ground > player.getY() + 0.5 && ground < player.getY() + 8
					&& ground < lastSupportedPos.y + 8 && (dug == null || !dug.isDug(cell.getX(), (int) Math.floor(ground - 0.01), cell.getZ()))) {
					AABB destination = player.getBoundingBox().move(0, ground + 0.01 - player.getY(), 0);
					if (SkyCollision.hasTriangleCoverage(destination) && !minecraft.level.getBlockCollisions(player, destination).iterator().hasNext()) {
						requestTeleport(minecraft, player.getX(), ground + 0.01, player.getZ(), player.getYRot(), player.getXRot());
						lastSupportedPos = player.position();
						return;
					}
				}
			}
		}
		// Elytra flight does not set abilities.flying. Missing high-altitude snapshots are
		// expected while gliding, and must not send the player back to their last ground point.
		if (player.getAbilities().flying || player.isFallFlying()) {
			flightRecoveryGrace = 40;
			return;
		}
		if (flightRecoveryGrace > 0) {
			flightRecoveryGrace--;
			return;
		}
		// Recover an actual fall into missing snapshots instead of cancelling gravity and making
		// an invisible platform. Known empty/dug regions retain normal falling and fall damage.
		AABB feet = new AABB(player.getBoundingBox().minX, player.getY() - 1.0, player.getBoundingBox().minZ,
			player.getBoundingBox().maxX, player.getY() + 0.05, player.getBoundingBox().maxZ);
		boolean missingFall = lastSupportedPos != null && player.getY() < lastSupportedPos.y - 4.0 && player.fallDistance > 4.0F
			&& player.getDeltaMovement().y < -0.1 && !SkyCollision.hasTriangleCoverage(feet);
		if (missingFall) {
			recoverPlayer(minecraft, player, "a fall into missing collision");
			return;
		}
		if (holdPos == null && player.onGround() && player.isAlive()
			&& SkyCollision.hasTriangleCoverage(player.getBoundingBox().inflate(0.05))) {
			double ground = SkyCollider.groundAt(player.getX(), player.getY(), player.getZ(), 0.1);
			AABB support = player.getBoundingBox().move(0.0, -0.05, 0.0);
			boolean blocksSupport = minecraft.level.getBlockCollisions(player, support).iterator().hasNext();
			if ((!Double.isNaN(ground) && Math.abs(ground - player.getY()) < 0.15) || blocksSupport) {
				lastSupportedPos = player.position();
			}
		}
	}

	private static void recoverPlayer(Minecraft minecraft, LocalPlayer player, String reason) {
		Vec3 safe = lastSupportedPos;
		Vec3 from = player.position();
		requestTeleport(minecraft, safe.x, safe.y, safe.z, player.getYRot(), player.getXRot());
		holdPos = safe;
		holdSince = 0;
		flightRecoveryGrace = 0;
		SkyCraft.LOG.warn("SkyCraft: recovered player from {} at {} to {}", reason, from, safe);
	}

	private static void requestTeleport(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer player = minecraft.player;
		player.setPos(x, y, z);
		player.walkDist = player.walkDistO = 0.0F;
		player.bob = player.oBob = 0.0F;
		player.setSprinting(false);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.teleportTo(x, y, z);
					sp.setYRot(yaw);
					sp.setXRot(pitch);
					sp.resetFallDistance();
				}
			});
		}
		SkyCraft.LOG.info("SkyCraft: teleported to {} {} {}", x, y, z);
	}

	/** Native Havok has neither Sable decks nor Minecraft entity collision boxes. */
	private static boolean onMinecraftSupport(Minecraft minecraft, LocalPlayer player) {
		if (player.isPassenger() || dev.skycraft.client.render.SableRenderCompat.isOnMovingSupport(player)) return true;
		if (!player.onGround() || player.isSpectator() || player.getAbilities().flying || minecraft.level == null) return false;
		AABB box = player.getBoundingBox();
		AABB sole = new AABB(box.minX + 1e-5, box.minY - 0.02, box.minZ + 1e-5,
			box.maxX - 1e-5, box.minY + 1e-5, box.maxZ - 1e-5);
		return !minecraft.level.getEntityCollisions(player, sole).isEmpty();
	}

	/** After GameRenderer.render(): report the player to Skyrim and ship the overlay frame. */
	public static void afterRender() {
		if (!linked) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		mc.darkness = mc.darknessPulse = mc.nightVision = 0.0F;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.getMainCamera();
            // Keep vanilla world-space feet. A support's visual pose is only for rendering,
            // never for relocating Skyrim's physical player or collision-stream centre.
            if (onMinecraftSupport(minecraft, player)) {
                flags |= Proto.MC_MOVING_SUPPORT;
            }
			flags |= Proto.MC_IN_WORLD;
			if (player.onGround()) {
				flags |= Proto.MC_ON_GROUND;
			}
			if (player.isShiftKeyDown()) {
				flags |= Proto.MC_SNEAKING;
			}
			if (player.isSprinting()) {
				flags |= Proto.MC_SPRINTING;
			}
			if (player.isDeadOrDying()) {
				flags |= Proto.MC_DEAD;
			}
			if (player.isSwimming()) {
				flags |= Proto.MC_SWIMMING;
			}
			if (player.getAbilities().flying) {
				flags |= Proto.MC_FLYING;
			}
			// Darkness is a world effect; the hidden MC renderer cannot shade Skyrim.
			var effect = player.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
			if (effect != null) {
				mc.darkness = effect.getBlendFactor(player, partial);
				float accessibility = minecraft.options.darknessEffectScale().get().floatValue();
				mc.darknessPulse = Math.max(0.0F, net.minecraft.util.Mth.cos((player.tickCount - partial)
					* (float) Math.PI * 0.025F) * 0.45F * mc.darkness) * accessibility * accessibility;
			}
			if (player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) {
				mc.nightVision = net.minecraft.client.renderer.GameRenderer.getNightVisionScale(player, partial);
			}
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			// The eye, not the camera: in third person Minecraft's camera sits behind or in front.
			Vec3 eye = minecraft.options.getCameraType().isFirstPerson() ? camera.getPosition() : player.getEyePosition(partial);
			mc.eyeHeight = (float) (eye.y - feet.y);
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = minecraft.options.fov().get().floatValue();
			// Minecraft's F5 camera: Skyrim puts its camera where Minecraft's would be.
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			// Other render paths can temporarily set up a first-person camera.
            // Preserve F5's distance on those frames instead of snapping to the eye.
            if (mc.cameraMode == 0) mc.cameraDistance = 0.0F;
            else if (camera.isDetached()) mc.cameraDistance = (float) camera.getPosition().distanceTo(eye);
            else if (mc.cameraDistance <= 0.0F) mc.cameraDistance = 4.0F;
			// Walk bob, exactly what GameRenderer.bobView() uses this frame.
			boolean bob = minecraft.options.bobView().get() && !player.isPassenger();
			mc.bobPhase = bob ? player.walkDistO + (player.walkDist - player.walkDistO) * partial : 0.0F;
			mc.bobAmount = bob ? player.oBob + (player.bob - player.oBob) * partial : 0.0F;
		}
		if (minecraft.screen != null) {
			flags |= Proto.MC_SCREEN_OPEN;
		}
		if (minecraft.screen == null && dev.skycraft.client.render.ModRenderCompat.holdsMouse()) flags |= Proto.MC_MOUSE_HELD;
		if (dev.skycraft.client.render.FullScreenEffect.active) flags |= Proto.MC_SCREEN_EFFECT;
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportAck = holdPos == null ? teleportAck : teleportAck - 1; // not "arrived" until we are released
		mc.guiScale = (int) minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		SkyLink.writeMcState(mc);

		if ((flags & Proto.MC_IN_WORLD) != 0) {
            // Snapshot the hand/HUD before optional model renderers can touch GL state/targets.
            FrameExporter.capture(minecraft);
            try (var captureState = new dev.skycraft.client.render.RenderCaptureState()) {
				WorldExporter.frame(minecraft, minecraft.getTimer().getGameTimeDeltaPartialTick(false));
			} catch (RuntimeException e) {
				if (exporterErrors++ < 5) {
					SkyCraft.LOG.error("SkyCraft: world export failed", e);
				}
			}
		}
	}

	/** End of the frame: render at most once per Skyrim frame instead of spinning freely. */
	public static void paceFrame() {
		if (!linked) {
			return;
		}
		if (skyrimStalled && (SkyLink.skyStateSeq() >>> 1) == lastPacedSeq) {
			return; // Skyrim is paused (menu / alt-tab): don't block every frame waiting for it
		}
		skyrimStalled = false;
		long deadline = System.nanoTime() + 25_000_000L;
		// SkyState.seq advances by 2 per Skyrim frame (odd while writing).
		while ((SkyLink.skyStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				java.util.concurrent.locks.LockSupport.parkNanos(250_000L);
			}
		}
		int seqNow = SkyLink.skyStateSeq() >>> 1;
		skyrimStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
	}

	private static void applyLinkedOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		var options = minecraft.options;
		options.pauseOnLostFocus = false;
		// GuiMixin suppresses the 1.21.1 vignette while linked so overlay alpha remains transparent.
		options.enableVsync().set(false);
		options.framerateLimit().set(260);
		// Minecraft doesn't draw the world itself; these only decide how far out placed blocks,
		// arrows and Skyrim NPC stand-ins stay loaded and simulated.
		options.renderDistance().set(8);
		options.simulationDistance().set(8);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		if (options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		}
		options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		GLFW.glfwHideWindow(minecraft.getWindow().getWindow());
		SkyCraft.LOG.info("SkyCraft: game window hidden (run with -Dskycraft.showWindow=true to keep it)");
	}

	private static void applyViewportSize(Minecraft minecraft) {
        if (sky.viewportW <= 0 || sky.viewportH <= 0) return;
        double scale = Math.min(1.0, Math.min((double) Proto.MAX_OVERLAY_W / sky.viewportW,
            (double) Proto.MAX_OVERLAY_H / sky.viewportH));
        var window = minecraft.getWindow();
        // Window.getWidth/Height are framebuffer pixels; getScreenWidth/Height are logical.
        double pixelScaleX = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
        double pixelScaleY = (double) window.getHeight() / Math.max(1, window.getScreenHeight());
        int framebufferW = Math.max(1, (int) Math.round(sky.viewportW * scale));
        int framebufferH = Math.max(1, (int) Math.round(sky.viewportH * scale));
        int w = Math.max(1, (int) Math.round(framebufferW / Math.max(1.0, pixelScaleX)));
        int h = Math.max(1, (int) Math.round(framebufferH / Math.max(1.0, pixelScaleY)));
        if (w != appliedViewportW || h != appliedViewportH) {
            appliedViewportW = w;
            appliedViewportH = h;
            window.setWindowed(w, h);
            minecraft.resizeDisplay();
            SkyCraft.LOG.info("SkyCraft: overlay window {}x{} logical, framebuffer target {}x{}", w, h, framebufferW, framebufferH);
        }
        // A delayed GLFW callback or a mod can resize the target after setWindowed.
        // GameRenderer.resize only resizes post effects, not the main render target.
        if (window.getWidth() != framebufferW || window.getHeight() != framebufferH
                || minecraft.getMainRenderTarget().width != framebufferW || minecraft.getMainRenderTarget().height != framebufferH) {
            window.setWidth(framebufferW);
            window.setHeight(framebufferH);
            minecraft.resizeDisplay();
        }
    }
}
