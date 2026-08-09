package com.profps.client;

import com.profps.ProFPS;
import com.profps.client.aim.AimImprovementsController;
import com.profps.client.aim.AutoAimController;
import com.profps.client.assists.AutoPotController;
import com.profps.client.assists.ExpandedHitboxController;
import com.profps.client.assists.HitImprovementsController;
import com.profps.client.assists.JumpResetController;
import com.profps.client.assists.VelocityController;
import com.profps.client.assists.HitboxesRenderer;
import com.profps.client.assists.StrafeImprovementsController;
import com.profps.client.ai.SwordAiController;
import com.profps.client.combatmode.CombatMode;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.crystalpvp.AnchorMacroController;
import com.profps.client.crystalpvp.AutoCrystalController;
import com.profps.client.crystalpvp.AutoXPController;
import com.profps.client.crystalpvp.FastUseController;
import com.profps.client.crystalpvp.TotemTweaksController;
import com.profps.client.donutsmp.AdvancedEspRenderer;
import com.profps.client.donutsmp.AmethystDetectorRenderer;
import com.profps.client.donutsmp.BasicEspRenderer;
import com.profps.client.donutsmp.ChunkActivityRenderer;
import com.profps.client.donutsmp.ChunkFinderRenderer;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.NetherPortalMapper;
import com.profps.client.donutsmp.NovaGotoController;
import com.profps.client.donutsmp.PlayerSightingLog;
import com.profps.client.donutsmp.StashPinger;
import com.profps.client.donutsmp.StorageEspRenderer;
import com.profps.client.donutsmp.SuspiciousChunksRenderer;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.classics.BoatFlyController;
import com.profps.client.classics.FlightController;
import com.profps.client.classics.FullBrightController;
import com.profps.client.classics.SpamController;
import com.profps.client.classics.TeleporterController;
import com.profps.client.classics.WaterWalkController;
import com.profps.client.extras.AntiFireballController;
import com.profps.client.extras.ClutchController;
import com.profps.client.extras.RememberController;
import com.profps.client.extras.SchematicBuildController;
import com.profps.client.extras.HeightClutchController;
import com.profps.client.extras.PingEqualizerController;
import com.profps.client.extras.PingSpoofController;
import com.profps.client.extras.RTPFinderController;
import com.profps.client.extras.ScaffoldController;
import com.profps.client.instants.AutoClickerController;
import com.profps.client.instants.AutoBreachSwapController;
import com.profps.client.instants.AutoLungeSwapController;
import com.profps.client.instants.AutoSpearController;
import com.profps.client.instants.AutoMaceController;
import com.profps.client.instants.AxeCritController;
import com.profps.client.instants.AxeStunController;
import com.profps.client.instants.KnockbackDisplacementController;
import com.profps.client.instants.MovementInstantsController;
import com.profps.client.instants.PearlCatchController;
import com.profps.client.instants.ToolMineController;
import com.profps.client.instants.FastPlaceController;
import com.profps.client.inventory.AutoSignController;
import com.profps.client.inventory.InventoryAutomationController;
import com.profps.client.packet.PacketManager;
import com.profps.client.packet.PacketOverlay;
import com.profps.client.subtiers.AutoBedController;
import com.profps.client.subtiers.AutoCreeperController;
import com.profps.client.subtiers.AutoMinecartController;
import com.profps.client.hypixel.BedBreakerController;
import com.profps.client.ui.NovaModuleListHud;
import com.profps.client.ui.ProFPSHud;
import com.profps.client.ui.nova.ModuleKeybinds;
import com.profps.client.ui.nova.NovaModules;
import com.profps.client.ui.nova.NovaScreenV2;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class ProFPSClient implements ClientModInitializer {
	private static ProFPSConfig config;
	private static java.util.List<NovaModules.Category> novaCategories;
	private static AimImprovementsController aimImprovements;
	private static StrafeImprovementsController strafeImprovements;
	private static HitImprovementsController hitImprovements;
	private static ExpandedHitboxController expandedHitbox;
	private static AutoMaceController autoMace;
	private static AxeCritController axeCrit;
	private static AutoBreachSwapController autoBreachSwap;
	private static AxeStunController axeStun;
	private static PearlCatchController pearlCatch;
	private static AnchorMacroController anchorMacro;
	private static FastUseController fastUse;
	private static TotemTweaksController totemTweaks;
	private static PingSpoofController pingSpoof;
	private static PingEqualizerController pingEqualizer;
	private static SwordAiController swordAi;
	private static AutoCreeperController autoCreeper;
	private static AutoClickerController autoClicker;
	private static AutoCrystalController autoCrystal;
	private static AutoLungeSwapController autoLunge;
	private static AutoSpearController autoSpear;
	private static AntiFireballController antiFireball;
	private static KnockbackDisplacementController kbDisplace;
	private static KeyBinding openKey;
	private static KeyBinding freecamKey;

	@Override
	public void onInitializeClient() {
		config = ProFPSConfig.load();
		FullBrightController.initialize(config);
		aimImprovements = new AimImprovementsController(config);
		strafeImprovements = new StrafeImprovementsController(config);
		hitImprovements = new HitImprovementsController(config);
		expandedHitbox = new ExpandedHitboxController(config);
		JumpResetController jumpReset = new JumpResetController(config);
		VelocityController velocity = new VelocityController(config);
		anchorMacro = new AnchorMacroController(config);
		fastUse = new FastUseController(config);
		totemTweaks = new TotemTweaksController(config);
		pingEqualizer = new PingEqualizerController(config);
		pingSpoof = new PingSpoofController(config, pingEqualizer);
		swordAi = new SwordAiController(config);
		autoCrystal = new AutoCrystalController(config);
		AutoPotController autoPot = new AutoPotController(config);
		AutoXPController autoXp = new AutoXPController(config);
		HitboxesRenderer hitboxes = new HitboxesRenderer(config);
		BasicEspRenderer basicEsp = new BasicEspRenderer(config);
		AdvancedEspRenderer advancedEsp = new AdvancedEspRenderer(config);
		StorageEspRenderer storageEsp = new StorageEspRenderer(config);
		SuspiciousChunksRenderer suspiciousChunks = new SuspiciousChunksRenderer(config);
		StashPinger stashPinger = new StashPinger(config, storageEsp);
		FreecamController freecam = new FreecamController(config);
		TunnelController tunnel = new TunnelController(config);
		NovaGotoController novaGoto = new NovaGotoController(config, stashPinger);
		ChunkActivityRenderer chunkActivity = new ChunkActivityRenderer(config);
		ChunkFinderRenderer chunkFinder = new ChunkFinderRenderer(config, chunkActivity);
		AmethystDetectorRenderer amethystDetector = new AmethystDetectorRenderer(config);
		NetherPortalMapper netherMapper = new NetherPortalMapper(config);
		PlayerSightingLog playerSightings = new PlayerSightingLog(config);
		autoClicker = new AutoClickerController(config);
		autoMace = new AutoMaceController(config);
		axeCrit = new AxeCritController(config);
		autoBreachSwap = new AutoBreachSwapController(config);
		axeStun = new AxeStunController(config);
		pearlCatch = new PearlCatchController(config);
		autoLunge = new AutoLungeSwapController(config);
		autoSpear = new AutoSpearController(config);
		kbDisplace = new KnockbackDisplacementController(config);
		AutoAimController autoAim = new AutoAimController(config);
		ToolMineController toolMine = new ToolMineController(config);
		FastPlaceController fastPlace = new FastPlaceController(config);
		InventoryAutomationController inventoryAutomation = new InventoryAutomationController(config);
		AutoSignController autoSign = new AutoSignController(config);
		RTPFinderController rtpFinder = new RTPFinderController(config);
		BedBreakerController bedBreaker = new BedBreakerController(config);
		MovementInstantsController movementInstants = new MovementInstantsController(config);
		ScaffoldController scaffold = new ScaffoldController(config);
		ClutchController clutch = new ClutchController(config);
		HeightClutchController heightClutch = new HeightClutchController(config);
		antiFireball = new AntiFireballController(config);
		RememberController remember = new RememberController(config);
		SchematicBuildController schematicBuild = new SchematicBuildController(config, remember);
		FlightController flight = new FlightController(config);
		SpamController spam = new SpamController(config);
		WaterWalkController waterWalk = new WaterWalkController(config);
		BoatFlyController boatFly = new BoatFlyController(config);
		TeleporterController teleporter = new TeleporterController(config);
		AutoBedController autoBed = new AutoBedController(config);
		autoCreeper = new AutoCreeperController(config);
		AutoMinecartController autoMinecart = new AutoMinecartController(config);
		novaCategories = NovaModules.build(config);
		ModuleKeybinds moduleKeybinds = new ModuleKeybinds(config, novaCategories);
		KeyBinding.Category controlsCategory = KeyBinding.Category.create(Identifier.of(ProFPS.MOD_ID, "controls"));

		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.profps.open",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				controlsCategory
		));
		freecamKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.profps.freecam",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_F6,
				controlsCategory
		));
		ClientTickEvents.START_CLIENT_TICK.register(autoMinecart::preTick);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				try {
					client.setScreen(new NovaScreenV2(config, novaCategories));
				} catch (RuntimeException exception) {
					ProFPS.LOGGER.error("Failed to open the Nova GUI.", exception);
				}
			}
			moduleKeybinds.tick(client);
			com.profps.client.classics.NicknameManager.update(config);
			while (freecamKey.wasPressed()) {
				freecam.toggle();
			}
			aimImprovements.tick(client);
			strafeImprovements.tick(client);
			// hitImprovements (triggerbot) is driven from TriggerbotPreMovementMixin instead,
			// so its attack packet is sent BEFORE the flying packet (vanilla order) and never
			// trips Grim's "Post" check. Do NOT also tick it here or it would double-fire.
			jumpReset.tick(client);
			velocity.tick(client);
			autoPot.tick(client);
			autoXp.tick(client);
			anchorMacro.tick(client);
			fastUse.tick(client);
			totemTweaks.tick(client);
			freecam.tick(client);
			tunnel.tick(client);
			novaGoto.tick(client);
			advancedEsp.tick(client);
			storageEsp.tick(client);
			suspiciousChunks.tick(client);
			// Stash Pinger reads Storage ESP's areas, so it ticks after it.
			stashPinger.tick(client);
			chunkActivity.tick(client);
			amethystDetector.tick(client);
			netherMapper.tick(client);
			playerSightings.tick(client);
			toolMine.tick(client);
			fastPlace.tick(client);
			inventoryAutomation.tick(client);
			// Auto Sign deliberately runs with a screen open — the sign editor IS its trigger.
			autoSign.tick(client);
			rtpFinder.tick(client);
			movementInstants.tick(client);
			// autoMace is driven from TriggerbotPreMovementMixin (firePreMovement) so its attack
			// packet is sent BEFORE the flying packet (vanilla order) and won't trip Grim "Post".
			autoAim.tick(client);
			scaffold.tick(client);
			clutch.tick(client);
			heightClutch.tick(client);
			remember.tick(client);
			schematicBuild.tick(client);
			flight.tick(client);
			spam.tick(client);
			waterWalk.tick(client);
			boatFly.tick(client);
			teleporter.tick(client);
			pingEqualizer.tick(client);
			pingSpoof.tick(client);
			swordAi.tick(client);
			autoBed.tick(client);
			autoMinecart.tick(client);
			bedBreaker.tick(client);
		});

		// Top-left FPS box removed. (ProFPSHud kept in the codebase, just not registered.)
		HudRenderCallback.EVENT.register(new NovaModuleListHud(config, novaCategories));
		HudRenderCallback.EVENT.register(scaffold::renderHud);
		HudRenderCallback.EVENT.register(stashPinger);
		HudRenderCallback.EVENT.register(chunkActivity);
		HudRenderCallback.EVENT.register(netherMapper);
		HudRenderCallback.EVENT.register(playerSightings);
		WorldRenderEvents.END_MAIN.register(context -> {
				MinecraftClient mc = MinecraftClient.getInstance();
				// Runs unconditionally and before any aiming module: silent aim is
				// held by continuous request, so this is what walks the body back
				// under the camera the moment nothing is asking for it any more —
				// including when a higher-priority controller stops the mace's
				// frame hook from running at all.
				com.profps.client.aim.SilentAimController.instance().frame(mc, silentAimFrameDelta());
				pearlCatch.frame(mc);
				boolean pearlOwnsRotation = pearlCatch.ownsRotation();
				boolean expandedOwnsRotation = expandedHitbox.isBusy();
				if (expandedOwnsRotation) expandedHitbox.frame(mc);
				boolean creeperOwnsRotation = autoCreeper.ownsRotation();
				boolean spearOwnsRotation = false;
				if (!pearlOwnsRotation && !expandedOwnsRotation && !creeperOwnsRotation) {
					// Auto Spear steers the approach; the lunge swap never takes the camera.
					spearOwnsRotation = autoSpear.frame(mc) || autoLunge.frame(mc);
				}
				if (!pearlOwnsRotation && !expandedOwnsRotation && !creeperOwnsRotation
						&& !spearOwnsRotation) {
					schematicBuild.frame(mc);
				}
				boolean schematicOwnsRotation = schematicBuild.ownsRotation();
				boolean modeRotationBlocked = pearlOwnsRotation || expandedOwnsRotation || creeperOwnsRotation
						|| spearOwnsRotation || schematicOwnsRotation;
				if (!modeRotationBlocked) {
					aimImprovements.frame(mc);
					strafeImprovements.frame(mc);
					hitImprovements.frame(mc);
				}
				if (!schematicOwnsRotation) {
					autoPot.frame(mc);
					tunnel.frame(mc);
				}
				if (!modeRotationBlocked) {
					autoMace.frame(mc);
					kbDisplace.frame(mc);
					autoAim.frame(mc);
					swordAi.frame(mc);
				}
				if (!schematicOwnsRotation && !pearlOwnsRotation && !expandedOwnsRotation) autoCreeper.frame(mc);
				if (!schematicOwnsRotation) autoMinecart.frame(mc);
			});
		// Do not attach overlays to BEFORE_DEBUG_RENDER. Performance mods can skip the
		// vanilla debug-renderer invocation entirely, which also skips Fabric's event.
		// END_MAIN is part of the normal world pass and renders these overlays after
		// terrain/translucency regardless of whether debug rendering is active.
		WorldRenderEvents.END_MAIN.register(hitboxes::render);
		WorldRenderEvents.END_MAIN.register(advancedEsp::renderWorld);
		WorldRenderEvents.END_MAIN.register(storageEsp::renderWorld);
		WorldRenderEvents.END_MAIN.register(suspiciousChunks::renderWorld);
		WorldRenderEvents.END_MAIN.register(remember::render);
		WorldRenderEvents.END_MAIN.register(chunkFinder::renderWorld);
		WorldRenderEvents.END_MAIN.register(amethystDetector::renderWorld);
		WorldRenderEvents.END_MAIN.register(netherMapper::renderWorld);
		WorldRenderEvents.END_MAIN.register(playerSightings::renderWorld);

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			aimImprovements.markAttack(client, entity);
			strafeImprovements.markAttack(client, entity);
			pingEqualizer.markAttack(client, entity);
			swordAi.markAttack(client, entity);
			return ActionResult.PASS;
		});
		UseBlockCallback.EVENT.register(anchorMacro::onUseBlock);
		UseBlockCallback.EVENT.register(autoCrystal::onUseBlock);
		UseBlockCallback.EVENT.register(autoBed::onUseBlock);
		UseBlockCallback.EVENT.register(autoCreeper::onUseBlock);
		UseBlockCallback.EVENT.register(autoMinecart::onUseBlock);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("nova")
						.then(ClientCommandManager.literal("goto")
								.then(ClientCommandManager.literal("near")
										.executes(context -> novaGoto.startNear(MinecraftClient.getInstance())))
								.then(ClientCommandManager.literal("stop")
										.executes(context -> novaGoto.stop(MinecraftClient.getInstance()))))
						.then(ClientCommandManager.literal("bases")
								.executes(context -> listBases(MinecraftClient.getInstance(), chunkActivity)))
		));

		// Packet Utils: draw the in-GUI toolbar on every screen and reset its live state on
		// disconnect so a reconnect never rejoins already silenced or holding a stale queue.
		PacketOverlay.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PacketManager.INSTANCE.reset();
			CombatModeRuntime.reset();
			if (pearlCatch != null) pearlCatch.reset();
		});

		ProFPS.LOGGER.info("ProFPS client loaded.");
	}

	/**
	 * Chat listing of every archived (confirmed) base on this server, strongest
	 * first, with distance from the player; each entry click-copies its coords.
	 */
	private static int listBases(MinecraftClient client, ChunkActivityRenderer engine) {
		if (client.player == null) return 0;
		java.util.List<ChunkActivityRenderer.BaseSummary> bases = engine.baseSummaries();
		if (bases.isEmpty()) {
			client.player.sendMessage(net.minecraft.text.Text.literal("[ProFPS] ")
					.formatted(net.minecraft.util.Formatting.DARK_GRAY)
					.append(net.minecraft.text.Text.literal("No confirmed bases archived yet — Base Heat saves red-tier chunks automatically.")
							.formatted(net.minecraft.util.Formatting.GRAY)), false);
			return 1;
		}
		client.player.sendMessage(net.minecraft.text.Text.literal("[ProFPS] ")
				.formatted(net.minecraft.util.Formatting.DARK_GRAY)
				.append(net.minecraft.text.Text.literal("Confirmed bases (click to copy coords):")
						.formatted(net.minecraft.util.Formatting.GOLD)), false);
		int shown = 0;
		for (ChunkActivityRenderer.BaseSummary base : bases) {
			if (shown++ >= 10) break;
			int dist = (int) Math.sqrt(client.player.squaredDistanceTo(base.blockX(), client.player.getY(), base.blockZ()));
			String coords = base.blockX() + " " + base.blockZ();
			client.player.sendMessage(net.minecraft.text.Text.literal("  " + coords)
					.styled(style -> style
							.withColor(net.minecraft.util.Formatting.YELLOW)
							.withClickEvent(new net.minecraft.text.ClickEvent.CopyToClipboard(coords)))
					.append(net.minecraft.text.Text.literal("  " + dist + "m · " + base.why())
							.formatted(net.minecraft.util.Formatting.GRAY)), false);
		}
		return 1;
	}

	public static ProFPSConfig config() { return config; }

	/** The built module catalogue, for screens (home/menus) that open the Modules UI. */
	public static java.util.List<NovaModules.Category> novaCategories() { return novaCategories; }
	public static AimImprovementsController aimImprovements() { return aimImprovements; }
	public static StrafeImprovementsController strafeImprovements() { return strafeImprovements; }
	public static HitImprovementsController hitImprovements() { return hitImprovements; }

	/**
	 * Tick click/action modules at the tail of {@code handleInputEvents()}, the same phase
	 * vanilla handles a click and before the player tick sends movement. Their action packets
	 * therefore keep vanilla action → flying order. Driven by {@code TriggerbotPreMovementMixin}.
	 */
	public static void firePreMovement(MinecraftClient client) {
		CombatModeRuntime.beginPreMovementTick(config);
		if (pearlCatch != null) {
			pearlCatch.tick(client);
			if (pearlCatch.isBusy()) {
				if (autoClicker != null) autoClicker.suspend();
				return;
			}
		}
		if (antiFireball != null) antiFireball.tick(client);
		if (expandedHitbox != null) expandedHitbox.tickPreMovement(client);
		if (autoCreeper != null) autoCreeper.tick(client);

		CombatMode mode = CombatModePolicy.mode(config);
		switch (mode) {
			case SWORD -> {
				// Disabled controllers still tick first so an in-flight hotbar sequence from
				// the previously selected mode can restore its item and clear transient state.
				if (autoMace != null) autoMace.tick(client);
				if (autoBreachSwap != null) autoBreachSwap.tick(client);
				if (axeStun != null) axeStun.tick(client);
				if (axeCrit != null) axeCrit.tick(client);
				if (hitImprovements != null) hitImprovements.tick(client);
			}
			case AXE -> {
				if (autoMace != null) autoMace.tick(client);
				if (autoBreachSwap != null) autoBreachSwap.tick(client);
				if (axeStun != null) axeStun.tick(client);
				if (axeCrit != null) axeCrit.tick(client);
				if (hitImprovements != null) hitImprovements.tick(client);
			}
			case MACE -> {
				if (axeStun != null) axeStun.tick(client);
				if (axeCrit != null) axeCrit.tick(client);
				if (hitImprovements != null) hitImprovements.tick(client);
				if (autoBreachSwap != null) autoBreachSwap.tick(client);
				if (autoMace != null) autoMace.tick(client);
			}
			case OFF -> {
				// Let any in-flight hotbar sequence restore first after Modes are turned off;
				// the shared action claim still permits only one ordered action in this tick.
				if (axeStun != null) axeStun.tick(client);
				if (axeCrit != null) axeCrit.tick(client);
				if (autoBreachSwap != null) autoBreachSwap.tick(client);
				if (autoMace != null) autoMace.tick(client);
				if (hitImprovements != null) hitImprovements.tick(client);
			}
		}
		if (autoLunge != null) autoLunge.tickPreMovement(client);
		if (autoSpear != null) autoSpear.tick(client);
		if (kbDisplace != null) kbDisplace.tick(client);
		if (autoCrystal != null) autoCrystal.tick(client);
		if (autoClicker != null) autoClicker.tickPreMovement(client);
	}
	private static long silentAimFrameNanos;

	/** Elapsed render-frame time in tick units, so the hand-back is frame-rate independent. */
	private static float silentAimFrameDelta() {
		long now = System.nanoTime();
		long previous = silentAimFrameNanos;
		silentAimFrameNanos = now;
		if (previous == 0L) return 1.0F;
		return (float) Math.clamp((now - previous) / 1_000_000_000.0D * 20.0D, 0.05D, 3.0D);
	}

	public static AnchorMacroController anchorMacro() { return anchorMacro; }
	public static FastUseController fastUse() { return fastUse; }
	public static TotemTweaksController totemTweaks() { return totemTweaks; }
	public static PingSpoofController pingSpoof() { return pingSpoof; }
	public static PingEqualizerController pingEqualizer() { return pingEqualizer; }
}
