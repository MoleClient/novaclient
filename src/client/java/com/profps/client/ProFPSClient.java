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
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.PrimeChunkFinder;
import com.profps.client.donutsmp.StashPinger;
import com.profps.client.donutsmp.StorageEspRenderer;
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

	@Override
	public void onInitializeClient() {
		config = ProFPSConfig.load();
		FullBrightController.initialize(config);
		com.profps.client.data.DataContribution.init(config);
		aimImprovements = new AimImprovementsController(config);
		strafeImprovements = new StrafeImprovementsController(config);
		hitImprovements = new HitImprovementsController(config);
		expandedHitbox = new ExpandedHitboxController(config);
		JumpResetController jumpReset = new JumpResetController(config);
		VelocityController velocity = new VelocityController(config);
		anchorMacro = new AnchorMacroController(config);
		fastUse = new FastUseController(config, anchorMacro);
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
		FreecamController freecam = new FreecamController(config);
		TunnelController tunnel = new TunnelController(config);
		PrimeChunkFinder primeChunk = new PrimeChunkFinder(config);
		StashPinger stashPinger = new StashPinger(config);
		AmethystDetectorRenderer amethystDetector = new AmethystDetectorRenderer(config);
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
		// Restore the last loaded schematic at its saved anchor.
		com.profps.client.extras.SchematicLibrary.restore(config);
		novaCategories = NovaModules.build(config);
		ModuleKeybinds moduleKeybinds = new ModuleKeybinds(config, novaCategories);
		KeyBinding.Category controlsCategory = KeyBinding.Category.create(Identifier.of(ProFPS.MOD_ID, "controls"));

		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.profps.open",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
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
			aimImprovements.tick(client);
			strafeImprovements.tick(client);
			// hitImprovements is ticked from TriggerbotPreMovementMixin to keep attack before
			// flying packet order; ticking it here too would double-fire.
			jumpReset.tick(client);
			velocity.tick(client);
			autoPot.tick(client);
			autoXp.tick(client);
			fastUse.tick(client);
			totemTweaks.tick(client);
			// Both write body rotation; Tunnel must land last, so Freecam ticks first.
			freecam.tick(client);
			tunnel.tick(client);
			advancedEsp.tick(client);
			storageEsp.tick(client);
			primeChunk.tick(client);
			// Reads primeChunk's flag set, so it must tick after it.
			stashPinger.tick(client);
			amethystDetector.tick(client);
			toolMine.tick(client);
			fastPlace.tick(client);
			inventoryAutomation.tick(client);
			// Runs with a screen open: the sign editor is its trigger.
			autoSign.tick(client);
			rtpFinder.tick(client);
			movementInstants.tick(client);
			// autoMace is ticked from firePreMovement to keep attack before flying packet order.
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

		// ProFPSHud is intentionally not registered.
		HudRenderCallback.EVENT.register(new NovaModuleListHud(config, novaCategories));
		HudRenderCallback.EVENT.register(scaffold::renderHud);
		WorldRenderEvents.END_MAIN.register(context -> {
				MinecraftClient mc = MinecraftClient.getInstance();
				// Must run before any aiming module: silent aim is held by continuous
				// request, and this restores the body once nothing is requesting it.
				com.profps.client.aim.SilentAimController.instance().frame(mc, silentAimFrameDelta());
				pearlCatch.frame(mc);
				boolean pearlOwnsRotation = pearlCatch.ownsRotation();
				boolean expandedOwnsRotation = expandedHitbox.isBusy();
				if (expandedOwnsRotation) expandedHitbox.frame(mc);
				boolean creeperOwnsRotation = autoCreeper.ownsRotation();
				boolean spearOwnsRotation = false;
				if (!pearlOwnsRotation && !expandedOwnsRotation && !creeperOwnsRotation) {
					// autoLunge is a momentary burst and outranks autoSpear's standing aim.
					spearOwnsRotation = autoLunge.frame(mc);
					if (!spearOwnsRotation) spearOwnsRotation = autoSpear.frame(mc);
				}
				if (!pearlOwnsRotation && !expandedOwnsRotation && !creeperOwnsRotation
						&& !spearOwnsRotation) {
					schematicBuild.frame(mc);
				}
				boolean schematicOwnsRotation = schematicBuild.ownsRotation();
				// The pot flick must own rotation exclusively while it runs.
				boolean potOwnsRotation = autoPot.ownsRotation();
				if (!schematicOwnsRotation) {
					autoPot.frame(mc);
					tunnel.frame(mc);
				}
				boolean modeRotationBlocked = pearlOwnsRotation || expandedOwnsRotation || creeperOwnsRotation
						|| spearOwnsRotation || schematicOwnsRotation || potOwnsRotation;
				if (!modeRotationBlocked) {
					aimImprovements.frame(mc);
					strafeImprovements.frame(mc);
					hitImprovements.frame(mc);
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
		// Use END_MAIN, not BEFORE_DEBUG_RENDER: performance mods can skip the vanilla
		// debug-renderer invocation and with it Fabric's event.
		WorldRenderEvents.END_MAIN.register(hitboxes::render);
		WorldRenderEvents.END_MAIN.register(advancedEsp::renderWorld);
		WorldRenderEvents.END_MAIN.register(storageEsp::renderWorld);
		WorldRenderEvents.END_MAIN.register(remember::render);
		WorldRenderEvents.END_MAIN.register(
				new com.profps.client.extras.SchematicGhostRenderer(config)::render);
		WorldRenderEvents.END_MAIN.register(primeChunk::renderWorld);
		WorldRenderEvents.END_MAIN.register(stashPinger::renderWorld);
		WorldRenderEvents.END_MAIN.register(amethystDetector::renderWorld);

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			aimImprovements.markAttack(client, entity);
			strafeImprovements.markAttack(client, entity);
			pingEqualizer.markAttack(client, entity);
			swordAi.markAttack(client, entity);
			com.profps.client.data.DataContribution.noteAttack();
			return ActionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			com.profps.client.data.DataContribution.noteBlockPlace();
			return ActionResult.PASS;
		});
		UseBlockCallback.EVENT.register(anchorMacro::onUseBlock);
		UseBlockCallback.EVENT.register(autoCrystal::onUseBlock);
		UseBlockCallback.EVENT.register(autoBed::onUseBlock);
		UseBlockCallback.EVENT.register(autoCreeper::onUseBlock);
		UseBlockCallback.EVENT.register(autoMinecart::onUseBlock);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("nova")
						.then(ClientCommandManager.literal("data")
								.executes(context -> reportDataContribution(MinecraftClient.getInstance())))
		));

		PacketOverlay.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PacketManager.INSTANCE.reset();
			CombatModeRuntime.reset();
			if (pearlCatch != null) pearlCatch.reset();
			// Flush the last partial batch before the session state is lost.
			com.profps.client.data.DataContribution.endSession();
		});

		ProFPS.LOGGER.info("ProFPS client loaded.");
	}

	/** Prints the data recorder's status for {@code /nova data}. */
	private static int reportDataContribution(MinecraftClient client) {
		if (client.player == null) return 0;
		com.profps.client.data.DataContribution recorder = com.profps.client.data.DataContribution.instance();
		if (recorder == null) {
			client.player.sendMessage(net.minecraft.text.Text.literal("Data contribution is not initialised.")
					.formatted(net.minecraft.util.Formatting.RED), false);
			return 1;
		}
		client.player.sendMessage(net.minecraft.text.Text.literal("Data contribution")
				.formatted(net.minecraft.util.Formatting.AQUA), false);
		for (String line : recorder.status()) {
			client.player.sendMessage(net.minecraft.text.Text.literal("  " + line)
					.formatted(net.minecraft.util.Formatting.GRAY), false);
		}
		return 1;
	}

	public static ProFPSConfig config() { return config; }

	/** The built module catalogue used by screens that open the Modules UI. */
	public static java.util.List<NovaModules.Category> novaCategories() { return novaCategories; }
	public static AimImprovementsController aimImprovements() { return aimImprovements; }
	public static StrafeImprovementsController strafeImprovements() { return strafeImprovements; }
	public static HitImprovementsController hitImprovements() { return hitImprovements; }

	/**
	 * Ticks click/action modules at the tail of {@code handleInputEvents()}, before movement is
	 * sent, so action packets precede the flying packet. Called by {@code TriggerbotPreMovementMixin}.
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
				// Disabled controllers still tick so an in-flight hotbar sequence can restore.
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
				// Order lets an in-flight hotbar sequence restore first once Modes are off.
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
		if (anchorMacro != null) anchorMacro.tick(client);
		if (autoCrystal != null) autoCrystal.tick(client);
		if (autoClicker != null) autoClicker.tickPreMovement(client);
		// Read-only, and last, so it samples the state the modules above settled.
		com.profps.client.data.DataContribution recorder = com.profps.client.data.DataContribution.instance();
		if (recorder != null) recorder.tick(client);
	}
	private static long silentAimFrameNanos;

	/** Elapsed render-frame time in tick units. */
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
