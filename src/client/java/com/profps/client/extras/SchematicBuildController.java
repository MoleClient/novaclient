package com.profps.client.extras;

import com.profps.ProFPS;
import com.profps.client.aim.SilentAimController;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.HumanizedAim;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.mixin.BlockItemInvoker;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Layer-by-layer builder for Remember captures and loaded schematics. Each layer is
 * filled deepest interior cell first per {@link SchematicLayerOrder}, and sweeps repeat
 * until one places nothing. With Auto Move on the controller drives the body and view;
 * with it off it prints through silent aim while the player walks. Cells with no support
 * face get a temporary bridge from {@link SchematicSupportPlanner}, mined back out after.
 */
public final class SchematicBuildController {
	private static final double MAX_REACH = 4.5D;
	private static final double MAX_REACH_SQUARED = MAX_REACH * MAX_REACH;
	private static final long RECENT_POSITION_NS = 900_000_000L;
	private static final int SOURCE_SCAN_PER_TICK = 32_768;
	private static final int MAX_PENDING_PER_LAYER = 65_536;
	private static final int MAX_FOOTPRINT_CELLS = 262_144;
	// Ticks since the last real placement before a layer's remainder is carried to the next sweep.
	private static final int LAYER_STALL_TICKS = 600;
	private static final int MAX_BUILD_PASSES = 64;
	private static final int MAX_LAYER_STEPS_PER_TICK = 64;
	private static final int TARGET_CANDIDATES_PER_TICK = 3;
	private static final int STAND_RADIUS = 4;
	private static final int STAND_MIN_DY = -3;
	private static final int STAND_MAX_DY = 3;
	// Frame-time budget: proving one stand costs up to thirty raycasts plus a placement prediction.
	private static final int STAND_EXAMINE_LIMIT = 8;
	private static final int MAX_PATH_NODES = 32_768;
	// Failed route searches are rate-limited rather than retried every tick.
	private static final int ROUTE_ATTEMPT_INTERVAL = 8;
	private static final int SUPPORT_PATH_NODES = 32_768;
	private static final int SUPPORT_HORIZONTAL_HORIZON = 128;
	private static final int SUPPORT_DOWNWARD_HORIZON = 128;
	private static final int MAX_TEMPORARY_BLOCKS_PER_PLAN = 320;
	private static final int MANUAL_PAUSE_TICKS = 10;
	private static final int TARGET_RETRY_TICKS = 35;
	// Printer mode (Auto Move off): server confirm window for a clicked cell, and how long
	// a cell whose proof failed sits out of the per-tick proof budget.
	private static final long PRINTER_CONFIRM_NS = 350_000_000L;
	private static final int PRINTER_DEFER_TICKS = 10;
	private static final int PRINTER_PROOFS_PER_TICK = 8;
	private static final int TEMPORARY_CELL_RETRY_TICKS = 400;
	private static final Set<String> PLACEMENT_PROPERTIES = Set.of(
			"axis", "facing", "half", "type", "rotation", "attachment", "face",
			"part", "hinge", "shape", "waterlogged", "hanging", "orientation",
			"vertical_direction", "open", "mode", "delay", "note", "inverted",
			"layers", "candles", "pickles", "eggs");
	private static final Set<String> STACKING_PROPERTIES = Set.of("layers", "candles", "pickles", "eggs");
	private static final Set<String> INTERACTION_PROPERTIES = Set.of("open", "mode", "delay", "note", "inverted");
	private static final Set<String> TEMPORARY_ORIENTATION_PROPERTIES = Set.of(
			"axis", "facing", "half", "type", "rotation", "attachment", "face",
			"part", "hinge", "shape", "hanging", "orientation", "vertical_direction");

	private static SchematicBuildController instance;

	private final ProFPSConfig config;
	private final RememberController remember;
	private final Random random = new Random();
	private final HumanizedAim aim = new HumanizedAim();
	private final SchematicNavigator navigator = new SchematicNavigator(random);
	private final LitematicaBridge litematica = new LitematicaBridge();

	private long lastPlaceNanos;
	private BlockPos recentPosition;
	private long recentPositionUntil;

	// Movement is read from the tick thread by InputMixin; the look goal is read from
	// the render thread by frame().
	private volatile boolean controlling;
	private volatile PlayerInput movementInput = PlayerInput.DEFAULT;
	private volatile boolean ownsRotation;
	private volatile Vec3d aimGoal;
	private volatile float aimSpeed = 1.0F;
	private long lastFrameNanos;

	// Source/layer scanner.
	private ClientWorld sourceWorld;
	private long knownRememberRevision = Long.MIN_VALUE;
	private long knownLitematicaSignature = Long.MIN_VALUE;
	private Map<BlockPos, BlockState> remembered = Map.of();
	private List<Map.Entry<BlockPos, BlockState>> rememberedEntries = List.of();
	private List<SourceBounds> schematicBounds = List.of();
	private final LinkedHashMap<BlockPos, BlockState> pending = new LinkedHashMap<>();
	private final Set<BlockPos> pendingKeys = new HashSet<>();
	private final Set<Long> layerFootprint = new HashSet<>();
	private Map<Long, Integer> layerDepth = Map.of();
	private int minimumLayer;
	private int maximumLayer;
	private int activeLayer = Integer.MIN_VALUE;
	private int scanBoundsIndex;
	private int scanX;
	private int scanZ;
	private boolean scanBoundsInitialized;
	private boolean rememberedLayerScanned;
	private int rememberedScanIndex;
	private boolean layerScanComplete;
	private boolean layerOverflowed;
	private boolean layerDepthReady;
	private int layerDepthFloor = Integer.MIN_VALUE;
	private int layerProgressTick = Integer.MIN_VALUE;
	private boolean buildFinished;
	private int nextMaintenanceTick;
	private int sourceCheckTick;

	// Repeat-until-nothing-moves sweep state.
	private int passIndex;
	// Fluids are skipped for the dry build, then swept once on their own pass.
	private boolean fluidPass;
	private boolean sawDeferredFluid;
	private int passPlacements;
	private int passUnplaced;
	private BlockPos passUnplacedSample;

	// Current target/placement state.
	private DesiredBlock target;
	private WorkKind workKind = WorkKind.SCHEMATIC;
	private PlacementAim placementAim;
	private int phaseTicks;
	private int settleTicks;
	private int confirmationTicks;
	private int manualPauseTicks;
	private final ArrayDeque<DesiredBlock> temporaryQueue = new ArrayDeque<>();
	private final LinkedHashMap<BlockPos, BlockState> ownedTemporaryBlocks = new LinkedHashMap<>();
	private DesiredBlock temporarySupportGoal;
	private int nextCleanupAttemptTick;
	private int nextRouteAttemptTick;
	// Posture the current stand was proved under; standing up moves the eye 0.35 blocks.
	private boolean standSneak;
	private boolean placementSent;
	private boolean breakingTemporary;
	private int breakSwingTicks;
	private final Map<BlockPos, Integer> retryAfter = new HashMap<>();
	// Stands proved from a distance that did not work out on arrival. The proof is
	// deterministic, so re-planning would otherwise return the same stand.
	private final Map<BlockPos, Set<SchematicPathfinder.Node>> failedStands = new HashMap<>();
	// Printer-mode bookkeeping: cells awaiting the server, cells parked after a failed
	// proof, and the cell the silently-turned body is converging on.
	private final Map<BlockPos, Long> printerClickedAt = new HashMap<>();
	private final Map<BlockPos, Integer> printerDeferUntil = new HashMap<>();
	private DesiredBlock printerTarget;
	private PlacementAim printerAimPlan;
	private int printerPhaseTicks;
	private boolean printerEngaged;
	private int nextStatusTick;
	private boolean wasEnabled;
	private String lastTrace = "";

	public SchematicBuildController(ProFPSConfig config, RememberController remember) {
		this.config = config;
		this.remember = remember;
		instance = this;
	}

	/** True while the builder owns the ordinary movement input path. */
	public static boolean isAutoMoving() {
		return instance != null && instance.controlling;
	}

	/** Input applied by {@code InputMixin} after the keyboard has been sampled. */
	public static PlayerInput movementInput() {
		return instance == null ? PlayerInput.DEFAULT : instance.movementInput;
	}

	/** The Y layer being built, or {@link Integer#MIN_VALUE} when idle. */
	public static int activeLayerY() {
		return instance == null ? Integer.MIN_VALUE : instance.activeLayer;
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	/** Steers the view at the display refresh rate rather than the 20 Hz tick. */
	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dtTicks = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0D * 20.0D, 0.01D, 2.0D);
		lastFrameNanos = now;
		Vec3d goal = aimGoal;
		if (!ownsRotation || goal == null || client == null || client.player == null) return;
		aim.aimFrame(client.player, goal, aimSpeed, dtTicks);
		client.player.headYaw = client.player.getYaw();
	}

	public void tick(MinecraftClient client) {
		if (!ready(client)) {
			cancelTemporaryBreaking(client);
			resetAll();
			wasEnabled = false;
			return;
		}

		if (!wasEnabled) {
			resetPlanning();
			wasEnabled = true;
		}

		if (manualInteracting(client)) {
			cancelTemporaryBreaking(client);
			manualPauseTicks = MANUAL_PAUSE_TICKS;
			navigator.pause();
			releaseControl();
			return;
		}
		if (manualPauseTicks > 0) {
			manualPauseTicks--;
			navigator.pause();
			releaseControl();
			return;
		}

		// Another module owns the view or the movement keys.
		if (TunnelController.isControlling() || FreecamController.isActive()) {
			cancelTemporaryBreaking(client);
			navigator.pause();
			releaseControl();
			return;
		}

		syncSources(client);
		if (remembered.isEmpty() && schematicBounds.isEmpty()) {
			releaseControl();
			status(client, SchematicLibrary.isLoaded() ? "Loaded schematic is already complete"
					: litematica.diagnosis(), Formatting.GRAY, 80);
			return;
		}

		// Auto Move off: print through silent aim and never touch the movement keys.
		// Everything below this block is stand/route/layer machinery.
		if (!config.schematicAutoMove) {
			cancelTemporaryBreaking(client);
			if (target != null || navigator.isRouting()) {
				clearTarget();
				releaseControl();
			}
			controlling = false;
			movementInput = PlayerInput.DEFAULT;
			printerTick(client);
			return;
		}

		if (buildFinished && ownedTemporaryBlocks.isEmpty() && client.player.age >= nextMaintenanceTick) {
			resetLayerScanner();
			status(client, "Checking the completed build", Formatting.GRAY, 0);
		}

		int tick = client.player.age;
		retryAfter.entrySet().removeIf(entry -> tick >= entry.getValue());
		// Hold the layer clock still while a support route runs: scaffold time is
		// neither layer progress nor layer stall.
		if (layerProgressTick != Integer.MIN_VALUE
				&& (!temporaryQueue.isEmpty() || workKind == WorkKind.TEMP_PLACE)) {
			layerProgressTick++;
		}
		if (target != null) {
			if (workKind == WorkKind.TEMP_REMOVE) {
				BlockState current = client.world.getBlockState(target.pos());
				if (current.isReplaceable()) {
					cancelTemporaryBreaking(client);
					ownedTemporaryBlocks.remove(target.pos());
					nextCleanupAttemptTick = 0;
					if (ownedTemporaryBlocks.isEmpty()) nextMaintenanceTick = client.player.age + 100;
					clearTarget();
				} else if (current.getBlock() != target.state().getBlock()) {
					// Changed by someone else: relinquish ownership rather than mine it.
					cancelTemporaryBreaking(client);
					ownedTemporaryBlocks.remove(target.pos());
					nextCleanupAttemptTick = 0;
					if (ownedTemporaryBlocks.isEmpty()) nextMaintenanceTick = client.player.age + 100;
					clearTarget();
				}
			} else if (desiredComplete(client.world, target.pos(), target.state())) {
				if (workKind == WorkKind.TEMP_PLACE) {
					// Claim cleanup ownership only for placements this controller sent.
					if (placementSent) ownedTemporaryBlocks.put(target.pos(), target.state());
					if (!temporaryQueue.isEmpty() && temporaryQueue.peekFirst().pos().equals(target.pos())) {
						temporaryQueue.removeFirst();
					}
				} else {
					pending.remove(target.pos());
					pendingKeys.remove(target.pos());
					passPlacements++;
					layerProgressTick = client.player.age;
					if (sameDesiredBlock(temporarySupportGoal, target)) temporarySupportGoal = null;
				}
				clearTarget();
			}
		}

		if (confirmationTicks > 0) {
			confirmationTicks--;
			if (target != null) {
				aimGoal = target.center();
				ownsRotation = true;
			}
			if (confirmationTicks == 0 && target != null
					&& !desiredComplete(client.world, target.pos(), target.state())) {
				placementAim = null;
				settleTicks = 0;
			}
			return;
		}

		// A route in progress owns the tick.
		if (navigator.isRouting()) {
			driveRoute(client);
			return;
		}

		if (target == null) {
			if (!prepareTarget(client)) {
				releaseControl();
				return;
			}
		}

		if (target == null) return;
		if (navigator.isRouting()) {
			driveRoute(client);
			return;
		}
		if (workKind == WorkKind.TEMP_REMOVE) alignAndBreakTemporary(client);
		else alignAndPlace(client);
	}

	private void syncSources(MinecraftClient client) {
		if (sourceWorld != client.world) {
			temporaryQueue.clear();
			ownedTemporaryBlocks.clear();
			temporarySupportGoal = null;
			nextCleanupAttemptTick = 0;
			sourceWorld = client.world;
			resetPlanning();
		}
		if (sourceCheckTick-- > 0) return;
		sourceCheckTick = 20;

		long rememberRevision = remember.revision();
		List<SourceBounds> merged = new ArrayList<>(litematica.bounds());
		int[] library = SchematicLibrary.bounds();
		if (library != null) {
			merged.add(new SourceBounds(library[0], library[1], library[2],
					library[3], library[4], library[5], 0));
		}
		List<SourceBounds> bounds = List.copyOf(merged);
		// The library revision joins the signature so a different file at the same
		// coordinates does not hash identically.
		long signature = boundsSignature(bounds) * 31L + SchematicLibrary.revision();
		if (rememberRevision == knownRememberRevision && signature == knownLitematicaSignature) return;

		knownRememberRevision = rememberRevision;
		knownLitematicaSignature = signature;
		remembered = remember.desiredStatesSnapshot();
		rememberedEntries = new ArrayList<>(remembered.entrySet());
		schematicBounds = bounds;
		trace("sources changed: " + remembered.size() + " remembered cell(s), "
				+ bounds.size() + " usable litematica placement(s) from "
				+ litematica.seenPlacements() + " loaded, " + litematica.enabledPlacements() + " enabled"
				+ (litematica.failure().isEmpty() ? "" : " [" + litematica.failure() + "]"));
		temporaryQueue.clear();
		temporarySupportGoal = null;
		cancelTemporaryBreaking(client);
		clearTarget();
		resetLayerScanner();
	}

	private void resetPlanning() {
		knownRememberRevision = Long.MIN_VALUE;
		knownLitematicaSignature = Long.MIN_VALUE;
		sourceCheckTick = 0;
		remembered = Map.of();
		rememberedEntries = List.of();
		schematicBounds = List.of();
		temporarySupportGoal = null;
		nextCleanupAttemptTick = 0;
		resetLayerScanner();
		clearTarget();
	}

	private void resetLayerScanner() {
		buildFinished = false;
		fluidPass = false;
		sawDeferredFluid = false;
		minimumLayer = Integer.MAX_VALUE;
		maximumLayer = Integer.MIN_VALUE;
		for (BlockPos pos : remembered.keySet()) {
			minimumLayer = Math.min(minimumLayer, pos.getY());
			maximumLayer = Math.max(maximumLayer, pos.getY());
		}
		for (SourceBounds bounds : schematicBounds) {
			minimumLayer = Math.min(minimumLayer, bounds.minY());
			maximumLayer = Math.max(maximumLayer, bounds.maxY());
		}
		passIndex = 0;
		startBuildPass();
	}

	/** Begins one bottom-to-top sweep; sweeps repeat until one places nothing. */
	private void startBuildPass() {
		retryAfter.clear();
		failedStands.clear();
		passPlacements = 0;
		passUnplaced = 0;
		passUnplacedSample = null;
		activeLayer = minimumLayer == Integer.MAX_VALUE ? Integer.MIN_VALUE : minimumLayer;
		startLayerScan();
	}

	private void startLayerScan() {
		pending.clear();
		pendingKeys.clear();
		layerFootprint.clear();
		layerDepth = Map.of();
		scanBoundsIndex = 0;
		scanX = 0;
		scanZ = 0;
		scanBoundsInitialized = false;
		rememberedLayerScanned = false;
		rememberedScanIndex = 0;
		layerScanComplete = false;
		layerOverflowed = false;
		layerDepthReady = false;
		layerDepthFloor = Integer.MIN_VALUE;
		layerProgressTick = Integer.MIN_VALUE;
	}

	/** Re-sweeps the current layer for the next depth band after one drains. */
	private void rescanCurrentLayer() {
		pending.clear();
		pendingKeys.clear();
		scanBoundsIndex = 0;
		scanX = 0;
		scanZ = 0;
		scanBoundsInitialized = false;
		rememberedLayerScanned = false;
		rememberedScanIndex = 0;
		layerScanComplete = false;
		layerOverflowed = false;
		layerDepthFloor = depthFloorBelow(
				layerDepthFloor == Integer.MIN_VALUE ? Integer.MAX_VALUE : layerDepthFloor);
		layerProgressTick = Integer.MIN_VALUE;
	}

	private boolean prepareTarget(MinecraftClient client) {
		if (!temporaryQueue.isEmpty()) return prepareTemporaryTarget(client);
		if (buildFinished) return prepareCleanupTarget(client);
		if (activeLayer == Integer.MIN_VALUE) return false;

		// Iterative rather than recursive: a tall schematic can skip hundreds of empty layers.
		for (int step = 0; step < MAX_LAYER_STEPS_PER_TICK; step++) {
			Boolean settled = prepareTargetStep(client);
			if (settled != null) return settled;
		}
		return false;
	}

	/** One layer's worth of work; null asks the caller to step forward again. */
	private Boolean prepareTargetStep(MinecraftClient client) {
		pending.entrySet().removeIf(entry -> desiredComplete(client.world, entry.getKey(), entry.getValue()));
		pendingKeys.retainAll(pending.keySet());
		if (!layerScanComplete) {
			scanCurrentLayer(client);
			// Interior-first ordering needs the whole footprint before anything is placed.
			if (!layerScanComplete) return false;
		}
		if (!layerDepthReady) {
			layerDepth = layerFootprint.size() >= MAX_FOOTPRINT_CELLS
					? Map.of() : SchematicLayerOrder.depths(layerFootprint);
			layerDepthReady = true;
			// A layer too wide to hold at once filled its first window in raster order;
			// now that depth is known, refill it deepest band first.
			if (layerOverflowed && layerDepthFloor == Integer.MIN_VALUE && !layerDepth.isEmpty()) {
				rescanCurrentLayer();
				return null;
			}
		}

		// Re-stamp if player age ran backwards past a respawn.
		int now = client.player.age;
		if (layerProgressTick == Integer.MIN_VALUE || layerProgressTick > now) layerProgressTick = now;
		if (!pending.isEmpty() && now - layerProgressTick > LAYER_STALL_TICKS) return advanceLayer(client);

		boolean routeTried = false;
		for (int attempt = 0; attempt < TARGET_CANDIDATES_PER_TICK; attempt++) {
			DesiredBlock next = prioritizedSupportGoal(client);
			if (next == null) next = choosePendingTarget(client);
			if (next == null) break;

			// A hanging cell needs its ceiling, which belongs to a later layer.
			DesiredBlock prerequisite = prerequisiteOf(client, next);
			if (prerequisite != null) {
				trace("pulling " + describe(prerequisite.pos()) + " forward for "
						+ describe(next.pos()));
				next = prerequisite;
			}

			BlockState current = client.world.getBlockState(next.pos());
			if (current.isReplaceable() && !hasSupport(client.world, next.pos())) {
				if (!config.schematicTemporaryBlocks) {
					if (sameDesiredBlock(temporarySupportGoal, next)) temporarySupportGoal = null;
					retryAfter.put(next.pos(), client.player.age + TARGET_RETRY_TICKS);
					status(client, "Layer " + activeLayer + " needs a support face (Temporary Blocks is off)",
							Formatting.GOLD, 40);
					continue;
				}
				if (enqueueTemporarySupport(client, next)) return prepareTemporaryTarget(client);
				retryAfter.put(next.pos(), client.player.age + TARGET_RETRY_TICKS);
				status(client, "No safe temporary-block route or enough scaffold material",
						Formatting.GOLD, 40);
				continue;
			}

			if (workableNow(client, next)) {
				beginWork(next, WorkKind.SCHEMATIC);
				return true;
			}
			// Out of reach: one route search per tick before the cell is requeued.
			if (!routeTried && config.schematicAutoMove) {
				routeTried = true;
				if (routeToPlace(client, next, WorkKind.SCHEMATIC)) {
					status(client, "Walking to layer " + activeLayer, Formatting.AQUA, 20);
					return true;
				}
			}
			trace("no stand for " + describe(next.pos()) + " (attempt " + (attempt + 1) + ")");
			if (sameDesiredBlock(temporarySupportGoal, next)) temporarySupportGoal = null;
			retryAfter.put(next.pos(), client.player.age + TARGET_RETRY_TICKS);
		}

		if (!pending.isEmpty()) {
			boolean anyMaterial = pending.entrySet().stream()
					.anyMatch(entry -> materialAvailable(client, entry.getKey(), entry.getValue()));
			if (!anyMaterial) {
					BlockState missing = pending.values().iterator().next();
					status(client, "Layer " + activeLayer + " needs " + missing.getBlock().getName().getString()
							+ " available",
							Formatting.YELLOW, 40);
				return false;
			}
			boolean anyWorkable = pending.entrySet().stream()
					.anyMatch(entry -> !retryAfter.containsKey(entry.getKey())
							&& materialAvailable(client, entry.getKey(), entry.getValue())
							&& canWorkOn(client.world, entry.getKey(), entry.getValue()));
			if (!anyWorkable && pending.keySet().stream().noneMatch(retryAfter::containsKey)) {
				status(client, "Layer " + activeLayer + " needs a support face or obstruction cleared",
						Formatting.GOLD, 40);
			}
			status(client, "Layer " + activeLayer + ": " + pending.size() + " cell(s) out of reach — move closer",
					Formatting.GRAY, 40);
			return false;
		}

		// The pending window filled before the layer was fully swept; pick up the remainder.
		if (layerOverflowed) {
			rescanCurrentLayer();
			return null;
		}
		return advanceLayer(client);
	}

	/** Steps to the next layer or the next sweep; null asks for another step. */
	private Boolean advanceLayer(MinecraftClient client) {
		if (!pending.isEmpty()) {
			passUnplaced += pending.size();
			if (passUnplacedSample == null) passUnplacedSample = pending.keySet().iterator().next();
		}
		activeLayer++;
		if (activeLayer <= maximumLayer) {
			startLayerScan();
			trace("layer " + activeLayer + " begins (pass " + (passIndex + 1) + ")");
			status(client, "Building layer " + activeLayer, Formatting.AQUA, 0);
			return null;
		}

		// Only a sweep that places nothing counts as finished.
		if (passPlacements > 0 && passIndex + 1 < MAX_BUILD_PASSES) {
			passIndex++;
			startBuildPass();
			status(client, "Verify sweep " + (passIndex + 1) + " over every layer", Formatting.AQUA, 0);
			return null;
		}

		// Fluids go in only after the dry build, so a source cannot flow across an
		// unfinished circuit and wash out its components.
		if (!fluidPass && sawDeferredFluid) {
			fluidPass = true;
			passIndex = 0;
			startBuildPass();
			trace("dry build complete; beginning fluid pass");
			status(client, "Dry build complete; placing water and lava last", Formatting.AQUA, 0);
			return null;
		}

		buildFinished = true;
		nextMaintenanceTick = client.player.age + 100;
		if (passUnplaced > 0 && passUnplacedSample != null) {
			status(client, passUnplaced + " block(s) unreachable, first at " + passUnplacedSample.getX() + " "
					+ passUnplacedSample.getY() + " " + passUnplacedSample.getZ(), Formatting.RED, 0);
		} else {
			status(client, ownedTemporaryBlocks.isEmpty() ? "Every schematic block is placed"
					: "Build complete; cleaning " + ownedTemporaryBlocks.size() + " temporary blocks",
					Formatting.GREEN, 0);
		}
		return false;
	}

	private DesiredBlock prioritizedSupportGoal(MinecraftClient client) {
		DesiredBlock goal = temporarySupportGoal;
		if (goal == null) return null;
		BlockState desired = pending.get(goal.pos());
		if (desired == null || desiredComplete(client.world, goal.pos(), desired)) {
			temporarySupportGoal = null;
			return null;
		}
		if (retryAfter.containsKey(goal.pos()) || !materialAvailable(client, goal.pos(), desired)
				|| !canWorkOn(client.world, goal.pos(), desired)) return null;
		return new DesiredBlock(goal.pos(), desired);
	}

	private void scanCurrentLayer(MinecraftClient client) {
		if (activeLayer == Integer.MIN_VALUE || layerScanComplete) return;
		int budget = SOURCE_SCAN_PER_TICK;

		if (!rememberedLayerScanned) {
			while (budget-- > 0 && rememberedScanIndex < rememberedEntries.size()) {
				Map.Entry<BlockPos, BlockState> entry = rememberedEntries.get(rememberedScanIndex++);
				if (entry.getKey().getY() == activeLayer) addPending(client.world, entry.getKey(), entry.getValue());
			}
			rememberedLayerScanned = rememberedScanIndex >= rememberedEntries.size();
			if (!rememberedLayerScanned) return;
		}

		while (budget-- > 0 && scanBoundsIndex < schematicBounds.size()) {
			SourceBounds bounds = schematicBounds.get(scanBoundsIndex);
			if (activeLayer < bounds.minY() || activeLayer > bounds.maxY()) {
				nextBounds();
				continue;
			}
			if (!scanBoundsInitialized) {
				scanX = bounds.minX();
				scanZ = bounds.minZ();
				scanBoundsInitialized = true;
			}

			BlockPos pos = new BlockPos(scanX, activeLayer, scanZ);
			if (!remembered.containsKey(pos)) {
				BlockState desired = SchematicLibrary.stateAt(pos);
					if (desired == null) desired = litematica.stateAt(pos);
				if (desired != null && !desired.isAir()) addPending(client.world, pos, desired);
			}

			scanX++;
			if (scanX > bounds.maxX()) {
				scanX = bounds.minX();
				scanZ++;
				if (scanZ > bounds.maxZ()) nextBounds();
			}
		}
		layerScanComplete = scanBoundsIndex >= schematicBounds.size();
	}

	private void nextBounds() {
		scanBoundsIndex++;
		scanX = 0;
		scanZ = 0;
		scanBoundsInitialized = false;
	}

	private void addPending(ClientWorld world, BlockPos rawPos, BlockState desired) {
		if (desired == null || desired.isAir()) return;
		BlockPos pos = rawPos.toImmutable();
		// Completed cells still belong to the footprint; the depth map has to see them.
		if (layerFootprint.size() < MAX_FOOTPRINT_CELLS) {
			layerFootprint.add(SchematicLayerOrder.key(pos.getX(), pos.getZ()));
		}
		// Judged against the full goal first, water included.
		if (desiredComplete(world, pos, desired)) return;

		// Fluids are held back to the fluid pass. A waterlogged block goes in dry now and
		// gets its bucket on that pass.
		BlockState wanted = desired;
		if (!fluidPass) {
			if (SchematicBlockRules.isFluid(desired)) {
				sawDeferredFluid = true;
				return;
			}
			if (SchematicBlockRules.isWaterlogged(desired)) {
				sawDeferredFluid = true;
				wanted = SchematicBlockRules.dewatered(desired);
			}
		}

		if (desiredComplete(world, pos, wanted) || pendingKeys.contains(pos)) return;
		if (depthOf(pos) < layerDepthFloor || pending.size() >= MAX_PENDING_PER_LAYER) {
			layerOverflowed = true;
			return;
		}
		pendingKeys.add(pos);
		pending.put(pos, wanted);
	}

	/** The goal for a cell as this pass builds it: dry during the main build, watered on the fluid pass. */
	private BlockState plannedStateAt(BlockPos pos) {
		BlockState desired = desiredStateAt(pos);
		if (desired == null || fluidPass) return desired;
		if (SchematicBlockRules.isFluid(desired)) return null;
		return SchematicBlockRules.isWaterlogged(desired) ? SchematicBlockRules.dewatered(desired) : desired;
	}

	/** How far inside the layer footprint a cell sits; 1 is the outer shell. */
	private int depthOf(BlockPos pos) {
		return layerDepth.getOrDefault(SchematicLayerOrder.key(pos.getX(), pos.getZ()), 1);
	}

	/**
	 * Picks the next cell to place. Depth is the strict primary key so remaining cells are
	 * never sealed in; phase breaks ties within a depth ring, not across the whole layer.
	 */
	private DesiredBlock choosePendingTarget(MinecraftClient client) {
		Vec3d eye = client.player.getEyePos();
		Vec3d feet = client.player.getEntityPos();
		Map.Entry<BlockPos, BlockState> best = null;
		int bestDepth = Integer.MIN_VALUE;
		int bestPhase = Integer.MAX_VALUE;
		boolean bestInReach = false;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (Map.Entry<BlockPos, BlockState> entry : pending.entrySet()) {
			BlockPos pos = entry.getKey();
			int depth = depthOf(pos);
			if (depth < bestDepth || retryAfter.containsKey(pos)
					|| !materialAvailable(client, pos, entry.getValue())
					|| !canWorkOn(client.world, pos, entry.getValue())) continue;

			int phase = SchematicBlockRules.phaseOf(entry.getValue()).ordinal();
			Vec3d center = Vec3d.ofCenter(pos);
			boolean inReach = eye.squaredDistanceTo(center) <= MAX_REACH_SQUARED;
			double distance = feet.squaredDistanceTo(center);
			if (depth == bestDepth && !(phase < bestPhase)
					&& !(phase == bestPhase && inReach && !bestInReach)
					&& !(phase == bestPhase && inReach == bestInReach && distance < bestDistance)) continue;

			best = entry;
			bestDepth = depth;
			bestPhase = phase;
			bestInReach = inReach;
			bestDistance = distance;
		}
		return best == null ? null : new DesiredBlock(best.getKey(), best.getValue());
	}

	/**
	 * Walks back from a cell to the schematic cell that must exist before it, pulling a
	 * hanging block's ceiling forward out of a later layer. Returns null when there is none.
	 */
	private DesiredBlock prerequisiteOf(MinecraftClient client, DesiredBlock desired) {
		DesiredBlock current = desired;
		// Bounded: chains of hanging blocks are legitimate, cycles are not.
		for (int hop = 0; hop < 4; hop++) {
			Direction support = SchematicBlockRules.supportDirection(current.state());
			if (support == null) return sameDesiredBlock(current, desired) ? null : current;
			BlockPos anchor = current.pos().offset(support);
			BlockState existing = client.world.getBlockState(anchor);
			boolean solid = !existing.isReplaceable()
					&& !existing.getCollisionShape(client.world, anchor).isEmpty();
			if (solid) return sameDesiredBlock(current, desired) ? null : current;

			BlockState wanted = plannedStateAt(anchor);
			// Not part of the build: the temporary-support planner handles it.
			if (wanted == null || desiredComplete(client.world, anchor, wanted)) {
				return sameDesiredBlock(current, desired) ? null : current;
			}
			if (retryAfter.containsKey(anchor) || !materialAvailable(client, anchor, wanted)) return null;
			current = new DesiredBlock(anchor, wanted);
		}
		return sameDesiredBlock(current, desired) ? null : current;
	}

	/** Deepest band of this layer that still fits the pending window, for layers too wide to hold at once. */
	private int depthFloorBelow(int exclusiveCeiling) {
		int deepest = 0;
		for (int depth : layerDepth.values()) deepest = Math.max(deepest, depth);
		if (deepest == 0) return Integer.MIN_VALUE;

		int[] histogram = new int[deepest + 1];
		for (int depth : layerDepth.values()) {
			if (depth < exclusiveCeiling) histogram[depth]++;
		}
		int running = 0;
		for (int depth = deepest; depth >= 1; depth--) {
			running += histogram[depth];
			// Always emit at least one band; leftovers come back on the next rescan.
			if (running > MAX_PENDING_PER_LAYER) return Math.min(depth + 1, exclusiveCeiling - 1);
		}
		return Integer.MIN_VALUE;
	}

	private boolean canWorkOn(ClientWorld world, BlockPos pos, BlockState desired) {
		BlockState current = world.getBlockState(pos);
		// Unsupported air is still valid work; the temporary-support planner can back it.
		if (current.isReplaceable()) return true;
		return current.getBlock() == desired.getBlock()
				&& (waterloggedMismatch(current, desired) || interactionPropertyMismatch(current, desired)
				|| repeatablePlacementMismatch(current, desired));
	}

	private boolean enqueueTemporarySupport(MinecraftClient client, DesiredBlock desired) {
		BlockState material = chooseTemporaryMaterial(client);
		if (material == null) return false;
		int available = client.player.isCreative() ? MAX_TEMPORARY_BLOCKS_PER_PLAN
				: Math.min(MAX_TEMPORARY_BLOCKS_PER_PLAN, expendableBlockCount(client, material));
		if (available <= 0) return false;

		SchematicSupportPlanner.Cell targetCell = supportCell(desired.pos());
		SchematicSupportPlanner.Space space = new TemporarySupportSpace(client, desired.pos(), Set.of());
		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.planFromSupports(
				targetCell, temporarySupportCandidates(client, desired), space,
				SUPPORT_PATH_NODES, SUPPORT_HORIZONTAL_HORIZON,
				SUPPORT_DOWNWARD_HORIZON, available);
		if (plan.isEmpty()) return false;

		temporaryQueue.clear();
		for (SchematicSupportPlanner.Cell cell : plan) {
			BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
			if (client.world.getBlockState(pos).isReplaceable()) {
				temporaryQueue.addLast(new DesiredBlock(pos, material));
			}
		}
		if (temporaryQueue.isEmpty()) return false;
		temporarySupportGoal = desired;
		status(client, "Creating " + temporaryQueue.size() + "-block temporary support route",
				Formatting.AQUA, 0);
		return true;
	}

	private List<SchematicSupportPlanner.Cell> temporarySupportCandidates(MinecraftClient client, DesiredBlock desired) {
		LinkedHashSet<Direction> order = new LinkedHashSet<>(6);
		String face = propertyValue(desired.state(), "face");
		String half = propertyValue(desired.state(), "half");
		String type = propertyValue(desired.state(), "type");
		String axis = propertyValue(desired.state(), "axis");
		String vertical = propertyValue(desired.state(), "vertical_direction");
		Direction facing = Direction.byId(propertyValue(desired.state(), "facing"));
		boolean hanging = propertyValue(desired.state(), "hanging").equals("true")
				|| face.equals("ceiling") || vertical.equals("down")
				|| propertyValue(desired.state(), "attachment").equals("ceiling");

		if (desired.state().getBlock() instanceof FallingBlock) {
			order.add(Direction.DOWN);
		} else if (hanging) {
			order.add(Direction.UP);
		} else if (face.equals("wall")) {
			if (facing != null && facing.getAxis().isHorizontal()) {
				// Wall-mounted states face away from their backing block.
				order.add(facing.getOpposite());
			} else {
				order.add(Direction.NORTH);
				order.add(Direction.SOUTH);
				order.add(Direction.WEST);
				order.add(Direction.EAST);
			}
		} else if (face.equals("floor")) {
			order.add(Direction.DOWN);
		} else if (axis.equals("x")) {
			order.add(Direction.WEST);
			order.add(Direction.EAST);
		} else if (axis.equals("z")) {
			order.add(Direction.NORTH);
			order.add(Direction.SOUTH);
		} else if (axis.equals("y")) {
			order.add(Direction.DOWN);
			order.add(Direction.UP);
		} else if (half.equals("top") || type.equals("top")) {
			order.add(Direction.UP);
			order.add(Direction.NORTH);
			order.add(Direction.SOUTH);
			order.add(Direction.WEST);
			order.add(Direction.EAST);
		} else {
			// Backing face for states that expose facing without a face property.
			if (facing != null && facing.getAxis().isHorizontal()) order.add(facing.getOpposite());
			order.addAll(List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
					Direction.WEST, Direction.EAST, Direction.UP));
		}

		// Narrow to candidates vanilla accepts; free-standing blocks keep the broader list.
		if (!desired.state().canPlaceAt(client.world, desired.pos())) {
			order.removeIf(direction -> !desired.state().canPlaceAt(
					withBlock(client.world, desired.pos().offset(direction),
							Blocks.COBBLESTONE.getDefaultState()),
					desired.pos()));
		}

		List<SchematicSupportPlanner.Cell> cells = new ArrayList<>(order.size());
		for (Direction direction : order) cells.add(supportCell(desired.pos().offset(direction)));
		return cells;
	}

	private boolean prepareTemporaryTarget(MinecraftClient client) {
		while (!temporaryQueue.isEmpty()) {
			DesiredBlock next = temporaryQueue.peekFirst();
			BlockState current = client.world.getBlockState(next.pos());
			if (desiredComplete(client.world, next.pos(), next.state())) {
				// Pre-existing match: usable, but not added to the cleanup ledger.
				temporaryQueue.removeFirst();
				continue;
			}
			if (!current.isReplaceable() && !current.getCollisionShape(client.world, next.pos()).isEmpty()) {
				// World-owned solid on the route: usable, but not ours to clean up.
				temporaryQueue.removeFirst();
				continue;
			}
			if (!current.isReplaceable() || !hasSupport(client.world, next.pos())) {
				retryAfter.put(next.pos(), client.player.age + TEMPORARY_CELL_RETRY_TICKS);
				deferTemporarySupport(client);
				return false;
			}
			if (workableNow(client, next)) {
				beginWork(next, WorkKind.TEMP_PLACE);
				return true;
			}
			if (config.schematicAutoMove && routeToPlace(client, next, WorkKind.TEMP_PLACE)) return true;
			retryAfter.put(next.pos(), client.player.age + TEMPORARY_CELL_RETRY_TICKS);
			deferTemporarySupport(client);
			return false;
		}
		return false;
	}

	private boolean prepareCleanupTarget(MinecraftClient client) {
		if (ownedTemporaryBlocks.isEmpty()) return false;
		if (client.player.age < nextCleanupAttemptTick) return false;
		List<Map.Entry<BlockPos, BlockState>> candidates = new ArrayList<>(ownedTemporaryBlocks.entrySet());
		for (int i = candidates.size() - 1; i >= 0; i--) {
			Map.Entry<BlockPos, BlockState> entry = candidates.get(i);
			BlockPos pos = entry.getKey();
			if (retryAfter.containsKey(pos)) continue;
			BlockState current = client.world.getBlockState(pos);
			if (current.isReplaceable() || current.getBlock() != entry.getValue().getBlock()) {
				ownedTemporaryBlocks.remove(pos);
				continue;
			}
			if (desiredStateAt(pos) != null || supportsFragileDesiredBlock(client.world, pos)) {
				// Leave the block: removing it could destroy a completed attachment.
				ownedTemporaryBlocks.remove(pos);
				continue;
			}
			if (!reachableNow(client, pos)) {
				if (config.schematicAutoMove
						&& routeToSee(client, new DesiredBlock(pos, entry.getValue()), WorkKind.TEMP_REMOVE)) {
					nextCleanupAttemptTick = 0;
					status(client, "Walking back to a temporary block", Formatting.GRAY, 20);
					return true;
				}
				retryAfter.put(pos, client.player.age + TARGET_RETRY_TICKS);
				continue;
			}
			nextCleanupAttemptTick = 0;
			beginWork(new DesiredBlock(pos, entry.getValue()), WorkKind.TEMP_REMOVE);
			status(client, "Cleaning temporary supports (" + ownedTemporaryBlocks.size() + " left)",
					Formatting.GRAY, 20);
			return true;
		}
		// Nothing tracked is currently in reach.
		nextCleanupAttemptTick = client.player.age + 20;
		return false;
	}

	private void deferTemporarySupport(MinecraftClient client) {
		if (temporarySupportGoal != null) {
			retryAfter.put(temporarySupportGoal.pos(), client.player.age + TARGET_RETRY_TICKS);
		}
		temporaryQueue.clear();
		temporarySupportGoal = null;
	}

	private void beginWork(DesiredBlock next, WorkKind kind) {
		trace("work " + kind + " -> " + describe(next.pos()) + " ("
				+ next.state().getBlock().getName().getString() + ")");
		target = next;
		workKind = kind;
		phaseTicks = 0;
		settleTicks = 0;
		placementAim = null;
		breakingTemporary = false;
		breakSwingTicks = 0;
		placementSent = false;
	}

	private BlockState chooseTemporaryMaterial(MinecraftClient client) {
		BlockState best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = client.player.getInventory().getStack(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
			BlockState state = blockItem.getBlock().getDefaultState();
			if (!safeTemporaryState(client, state, client.player.getBlockPos())) continue;
			int expendable = expendableBlockCount(client, state);
			if (expendable <= 0) continue;
			double score = expendable * 4.0D - state.getHardness(client.world, client.player.getBlockPos()) * 2.0D;
			if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.COBBLESTONE)
					|| state.isOf(Blocks.NETHERRACK) || state.isOf(Blocks.COBBLED_DEEPSLATE)) score += 80.0D;
			if (score > bestScore) {
				bestScore = score;
				best = state;
			}
		}
		if (best != null) return best;
		if (client.player.isCreative() && (findEmptyHotbarSlot(client.player) >= 0
				|| findEmptyInventorySlot(client.player) >= 0)) {
			return Blocks.COBBLESTONE.getDefaultState();
		}
		return null;
	}

	private boolean safeTemporaryState(MinecraftClient client, BlockState state, BlockPos samplePos) {
		for (Property<?> property : state.getProperties()) {
			if (TEMPORARY_ORIENTATION_PROPERTIES.contains(property.getName())) return false;
		}
		return !(state.getBlock() instanceof FallingBlock)
				&& !state.hasBlockEntity()
				&& state.getFluidState().isEmpty()
				&& state.isFullCube(client.world, samplePos)
				&& !state.getCollisionShape(client.world, samplePos).isEmpty()
				&& state.getHardness(client.world, samplePos) >= 0.0F
				&& state.getHardness(client.world, samplePos) <= 8.0F
				&& !state.isOf(Blocks.TNT) && !state.isOf(Blocks.ICE)
				&& !state.isOf(Blocks.PACKED_ICE) && !state.isOf(Blocks.BLUE_ICE)
				&& !state.isOf(Blocks.SLIME_BLOCK) && !state.isOf(Blocks.HONEY_BLOCK);
	}

	private int expendableBlockCount(MinecraftClient client, BlockState material) {
		int count = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = client.player.getInventory().getStack(slot);
			if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == material.getBlock()) {
				count += stack.getCount();
			}
		}
		long reserved = pending.values().stream().filter(state -> state.getBlock() == material.getBlock()).count();
		return Math.max(0, count - (int) Math.min(Integer.MAX_VALUE, reserved));
	}

	private boolean supportsFragileDesiredBlock(ClientWorld world, BlockPos temporary) {
		WorldView withoutTemporary = withoutBlock(world, temporary);
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = temporary.offset(direction);
			BlockState desired = desiredStateAt(neighbor);
			if (desired == null || !desiredComplete(world, neighbor, desired)) continue;
			BlockState actual = world.getBlockState(neighbor);
			if (actual.getBlock() instanceof FallingBlock && temporary.equals(neighbor.down())) return true;
			if (!actual.canPlaceAt(withoutTemporary, neighbor)) return true;
		}
		return false;
	}

	/** Read-only overlay used to ask vanilla whether a block survives with one support removed. */
	private WorldView withoutBlock(ClientWorld world, BlockPos removed) {
		return withBlock(world, removed, Blocks.AIR.getDefaultState());
	}

	private WorldView withBlock(WorldView world, BlockPos overriddenPos, BlockState overriddenState) {
		return (WorldView) Proxy.newProxyInstance(
				WorldView.class.getClassLoader(),
				new Class<?>[]{WorldView.class},
				(proxy, method, args) -> {
					if (args != null && args.length > 0 && args[0] instanceof BlockPos pos
							&& pos.equals(overriddenPos)) {
						if (method.getName().equals("getBlockState")) return overriddenState;
						if (method.getName().equals("getFluidState")) return overriddenState.isAir()
								? Fluids.EMPTY.getDefaultState() : overriddenState.getFluidState();
						if (method.getName().equals("getBlockEntity")) {
							return method.getReturnType() == java.util.Optional.class
									? java.util.Optional.empty() : null;
						}
					}
					try {
						return method.invoke(world, args);
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
	}

	private BlockState desiredStateAt(BlockPos pos) {
		BlockState state = remembered.get(pos);
		if (state == null || state.isAir()) state = SchematicLibrary.stateAt(pos);
		if (state == null || state.isAir()) state = litematica.stateAt(pos);
		return state == null || state.isAir() ? null : state;
	}

	private SchematicSupportPlanner.Cell supportCell(BlockPos pos) {
		return new SchematicSupportPlanner.Cell(pos.getX(), pos.getY(), pos.getZ());
	}

	private final class TemporarySupportSpace implements SchematicSupportPlanner.Space {
		private final MinecraftClient client;
		private final BlockPos desiredTarget;
		private final Set<BlockPos> blocked;
		/** {@code blocked} holds cells the plan must leave open (a stand's body). */
		private TemporarySupportSpace(MinecraftClient client, BlockPos desiredTarget, Set<BlockPos> blocked) {
			this.client = client;
			this.desiredTarget = desiredTarget;
			this.blocked = blocked;
		}

		@Override
		public boolean available(SchematicSupportPlanner.Cell cell) {
			BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
			if (pos.equals(desiredTarget) || blocked.contains(pos)
					|| !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
					|| retryAfter.containsKey(pos)
					|| pos.getY() < client.world.getBottomY()
					|| pos.getY() >= client.world.getBottomY() + client.world.getHeight()) return false;
			BlockState state = client.world.getBlockState(pos);
			return state.isAir() && state.getFluidState().isEmpty() && desiredStateAt(pos) == null;
		}

		@Override
		public boolean anchored(SchematicSupportPlanner.Cell cell) {
			// Only tests for a solid face; reachability is re-proved before any placement.
			BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
			for (Direction direction : Direction.values()) {
				BlockPos neighbor = pos.offset(direction);
				BlockState state = client.world.getBlockState(neighbor);
				if (!state.isReplaceable() && !state.getCollisionShape(client.world, neighbor).isEmpty()) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Finds somewhere the cell can be built from and walks there. The stand is chosen by
	 * proving a whole placement from it, not merely line of sight, so rotations come out right.
	 */
	private boolean routeToPlace(MinecraftClient client, DesiredBlock next, WorkKind kind) {
		if (client.player.age < nextRouteAttemptTick) return false;
		WorldSpace space = new WorldSpace(client, client.player);
		SchematicPathfinder.Node feet = feetNode(client.player);
		List<SchematicPlacementSolver.Stand> stands = viableStands(client, next.pos(), space, feet);
		SchematicPlacementSolver.Solution solution = SchematicPlacementSolver.solve(
				client, next.pos(), next.state(), stands, this::placementMatches, STAND_EXAMINE_LIMIT);
		if (solution == null) {
			nextRouteAttemptTick = client.player.age + ROUTE_ATTEMPT_INTERVAL;
			return false;
		}
		return walkTo(client, next, kind, space, feet, solution.stand());
	}

	/** As {@link #routeToPlace}, but the goal is only to see the block, not to build it. */
	private boolean routeToSee(MinecraftClient client, DesiredBlock next, WorkKind kind) {
		if (client.player.age < nextRouteAttemptTick) return false;
		WorldSpace space = new WorldSpace(client, client.player);
		SchematicPathfinder.Node feet = feetNode(client.player);
		int examined = 0;
		for (SchematicPlacementSolver.Stand stand : viableStands(client, next.pos(), space, feet)) {
			if (examined++ >= STAND_EXAMINE_LIMIT) break;
			if (!standCanSeeWork(client, stand.eye(), next.pos())) continue;
			if (walkTo(client, next, kind, space, feet, stand)) return true;
		}
		nextRouteAttemptTick = client.player.age + ROUTE_ATTEMPT_INTERVAL;
		return false;
	}

	/** Ranked stands for a cell, minus the ones already proved fruitless. */
	private List<SchematicPlacementSolver.Stand> viableStands(MinecraftClient client, BlockPos target,
			WorldSpace space, SchematicPathfinder.Node feet) {
		List<SchematicPlacementSolver.Stand> stands = SchematicPlacementSolver.candidateStands(
				space, target, feet, STAND_RADIUS, STAND_MIN_DY, STAND_MAX_DY);
		Set<SchematicPathfinder.Node> blamed = failedStands.get(target);
		if (blamed != null && !blamed.isEmpty()) stands.removeIf(s -> blamed.contains(s.node()));
		return stands;
	}

	/** Records that the cell could not be built from where the body stands now. */
	private void blameCurrentStand(MinecraftClient client) {
		blameStand(feetNode(client.player));
	}

	private void blameStand(SchematicPathfinder.Node stand) {
		if (target == null || stand == null) return;
		failedStands.computeIfAbsent(target.pos(), ignored -> new HashSet<>()).add(stand);
	}

	private boolean walkTo(MinecraftClient client, DesiredBlock next, WorkKind kind,
			WorldSpace space, SchematicPathfinder.Node feet, SchematicPlacementSolver.Stand stand) {
		List<SchematicPathfinder.Node> route = feet.equals(stand.node()) ? List.of(feet)
				: SchematicPathfinder.groundPathToAny(feet, List.of(stand.node()), space, MAX_PATH_NODES);
		if (route.isEmpty()) {
			nextRouteAttemptTick = client.player.age + ROUTE_ATTEMPT_INTERVAL;
			return false;
		}
		trace("route " + route.size() + " node(s) to " + stand.node()
				+ (stand.sneak() ? " (crouched)" : "") + " for " + describe(next.pos()));
		beginWork(next, kind);
		standSneak = stand.sneak();
		navigator.startRoute(route, stand.sneak());
		nextRouteAttemptTick = 0;
		return true;
	}

	/** Publishes one tick of walking; an unwalkable route is dropped and the cell requeued. */
	private void driveRoute(MinecraftClient client) {
		SchematicNavigator.State state = navigator.tick(client);
		if (state == SchematicNavigator.State.STUCK) {
			trace("route stuck; abandoning " + (target == null ? "route" : describe(target.pos())));
			// Blame the destination, not where the body gave up, or the same route is re-planned.
			blameStand(navigator.destination());
			navigator.clear();
			if (target != null) retryAfter.put(target.pos(), client.player.age + TARGET_RETRY_TICKS);
			clearTarget();
			releaseControl();
			return;
		}
		controlling = true;
		movementInput = navigator.input();
		Vec3d look = navigator.lookGoal();
		if (look != null) {
			aimGoal = look;
			aimSpeed = navigator.lookSpeed();
			ownsRotation = true;
		}
		if (state == SchematicNavigator.State.ARRIVED) navigator.clear();
	}

	/**
	 * True when the eye is within reach of the cell and has a real ray to the block or a
	 * support face beside it.
	 */
	private boolean reachableNow(MinecraftClient client, BlockPos pos) {
		Vec3d eye = client.player.getEyePos();
		if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > MAX_REACH_SQUARED) return false;
		return standCanSeeWork(client, eye, pos);
	}

	/**
	 * True when the cell can be built correctly from where the body stands. Stricter than
	 * {@link #reachableNow}: the rotation must also come out right from here.
	 */
	private boolean workableNow(MinecraftClient client, DesiredBlock desired) {
		Vec3d eye = client.player.getEyePos();
		if (eye.squaredDistanceTo(desired.center()) > MAX_REACH_SQUARED) return false;
		if (!client.world.getBlockState(desired.pos()).isReplaceable()) {
			// Already occupied: a bucket, toggle or stack job, which the solver does not cover.
			return reachableNow(client, desired.pos());
		}
		return SchematicPlacementSolver.solveHere(client, desired.pos(), desired.state(),
				this::placementMatches) != null;
	}

	private boolean standCanSeeWork(MinecraftClient client, Vec3d eye, BlockPos target) {
		BlockState targetState = client.world.getBlockState(target);
		if (!targetState.isReplaceable()) {
			for (Direction side : Direction.values()) {
				Vec3d point = facePoint(target, side, 0);
				BlockHitResult hit = client.world.raycast(new RaycastContext(
						eye, point, RaycastContext.ShapeType.OUTLINE,
						RaycastContext.FluidHandling.NONE, client.player));
				if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return true;
			}
		}
		for (Direction towardSupport : Direction.values()) {
			BlockPos support = target.offset(towardSupport);
			BlockState supportState = client.world.getBlockState(support);
			if (supportState.isReplaceable() || supportState.getCollisionShape(client.world, support).isEmpty()) continue;
			Direction clickedSide = towardSupport.getOpposite();
			Vec3d point = facePoint(support, clickedSide, 0);
			BlockHitResult hit = client.world.raycast(new RaycastContext(
					eye, point, RaycastContext.ShapeType.OUTLINE,
					RaycastContext.FluidHandling.NONE, client.player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
					&& hit.getSide() == clickedSide) return true;
		}
		return false;
	}

	private void alignAndPlace(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		phaseTicks++;
		// Hold the posture for the whole alignment, not only the tick of the click.
		holdPosture(standSneak || (placementAim != null && placementAim.sneak()));

		// Vanilla refuses a placement into the cell the body occupies; route off it.
		if (player.getBoundingBox().intersects(new Box(target.pos()).shrink(0.05D, 0.05D, 0.05D))) {
			blameCurrentStand(client);
			if (config.schematicAutoMove && routeToPlace(client, target, workKind)) {
				status(client, "Stepping off the next cell", Formatting.AQUA, 20);
				return;
			}
			status(client, "Standing in the next cell — step aside", Formatting.GOLD, 40);
			if (phaseTicks > 40) {
				retryAfter.put(target.pos(), player.age + TARGET_RETRY_TICKS);
				clearTarget();
				releaseControl();
			}
			return;
		}

		if (placementAim == null || phaseTicks % 12 == 0) {
			placementAim = findPlacementAim(client, target);
			settleTicks = 0;
			if (placementAim == null) {
				if (phaseTicks > 28) {
					blameCurrentStand(client);
					if (workKind == WorkKind.TEMP_PLACE) {
						retryAfter.put(target.pos(), player.age + TEMPORARY_CELL_RETRY_TICKS);
						deferTemporarySupport(client);
						clearTarget();
						releaseControl();
						return;
					}
					if (workKind == WorkKind.SCHEMATIC && config.schematicTemporaryBlocks
							&& client.world.getBlockState(target.pos()).isReplaceable()
							&& enqueueTemporarySupport(client, target)) {
						clearTarget();
						return;
					}
					retryAfter.put(target.pos(), player.age + TARGET_RETRY_TICKS);
					clearTarget();
					releaseControl();
				}
				return;
			}
		}

		aimGoal = placementAim.aimPoint();
		aimSpeed = 1.85F;
		ownsRotation = true;
		// Sneaking tells vanilla to build rather than trigger the clicked block's use action.
		holdPosture(placementAim.sneak());
		BlockHitResult liveHit = livePlacementHit(client, placementAim, target);
		if (liveHit == null) {
			settleTicks = 0;
			return;
		}
		// Sneak is a physics state: the click must land on a tick the body is already crouched.
		if (placementAim.sneak() && !player.isSneaking()) {
			// Bounded: something upstream may be suppressing the published crouch.
			settleTicks = 0;
			if (phaseTicks > 60) {
				blameCurrentStand(client);
				retryAfter.put(target.pos(), player.age + TARGET_RETRY_TICKS);
				clearTarget();
				releaseControl();
			}
			return;
		}

		if (++settleTicks < 1) return;
		if (player.getInventory().getSelectedSlot() != placementAim.hotbarSlot()) {
			player.getInventory().setSelectedSlot(placementAim.hotbarSlot());
			settleTicks = 0;
			return;
		}

		long now = System.nanoTime();
		long interval = lastPlaceNanos + jitterMs(22, 38);
		if (now < interval) return;

		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, liveHit);
		if (!result.isAccepted() && (placementAim.kind() == PlacementKind.WATERLOG
				|| placementAim.kind() == PlacementKind.UNWATERLOG)) {
			result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		}
		if (!result.isAccepted()) {
			settleTicks = 0;
			placementAim = null;
			return;
		}

		player.swingHand(Hand.MAIN_HAND);
		lastPlaceNanos = now;
		placementSent = true;
		recentPosition = target.pos();
		recentPositionUntil = now + RECENT_POSITION_NS;
		confirmationTicks = 2;
		settleTicks = 0;
	}

	/**
	 * One tick of manual-move building through silent aim. The camera stays under the
	 * player's mouse while the body turns and clicks, and the click only fires once
	 * {@link #livePlacementHit} confirms the body's own ray lands on the planned face.
	 */
	private void printerTick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		long now = System.nanoTime();
		int tick = player.age;
		printerClickedAt.values().removeIf(at -> now - at > PRINTER_CONFIRM_NS);
		printerDeferUntil.values().removeIf(until -> tick >= until);

		if (printerTarget != null && !printerTargetStillWanted(client)) printerClearTarget(client, false);
		if (printerTarget == null) printerChooseTarget(client);
		if (printerTarget == null) {
			printerDisengage();
			ownsRotation = false;
			aimGoal = null;
			status(client, "No unbuilt cell in reach (Auto Move is off)", Formatting.GRAY, 160);
			return;
		}

		// The first engage snapshots the player's rotation as the camera view.
		SilentAimController.instance().engage(player);
		printerEngaged = true;
		printerPhaseTicks++;

		if (printerAimPlan == null || printerPhaseTicks % 12 == 0) {
			PlacementAim plan = findPlacementAim(client, printerTarget);
			if (plan == null || printerPostureBlocked(client, plan)) {
				printerClearTarget(client, true);
				return;
			}
			printerAimPlan = plan;
		}

		aimGoal = printerAimPlan.aimPoint();
		aimSpeed = 1.85F;
		ownsRotation = true;

		BlockHitResult live = livePlacementHit(client, printerAimPlan, printerTarget);
		if (live == null) {
			// Still turning, or the world changed under the plan; give up after a while.
			if (printerPhaseTicks > 50) printerClearTarget(client, true);
			return;
		}
		if (now < lastPlaceNanos + jitterMs(22, 38)) return;
		selectHotbarSlot(client, printerAimPlan.hotbarSlot());
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, live);
		if (!result.isAccepted() && (printerAimPlan.kind() == PlacementKind.WATERLOG
				|| printerAimPlan.kind() == PlacementKind.UNWATERLOG)) {
			result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		}
		if (!result.isAccepted()) {
			printerClearTarget(client, true);
			return;
		}
		player.swingHand(Hand.MAIN_HAND);
		lastPlaceNanos = now;
		printerClickedAt.put(printerTarget.pos(), now);
		trace("printer placed " + describe(printerTarget.pos()));
		printerClearTarget(client, false);
	}

	/** The buildable-now form of a goal: dry, leaving the bucket as its own job. */
	private BlockState dryGoal(BlockState goal) {
		return SchematicBlockRules.isWaterlogged(goal) ? SchematicBlockRules.dewatered(goal) : goal;
	}

	/**
	 * True when the placement needs a posture the printer cannot supply, since it never
	 * touches the keys. The cell waits and the player is hinted on the action bar.
	 */
	private boolean printerPostureBlocked(MinecraftClient client, PlacementAim plan) {
		if (plan.sneak() && !client.player.isSneaking()) {
			status(client, "Crouch to build against interactive blocks", Formatting.GOLD, 40);
			return true;
		}
		if (plan.kind() == PlacementKind.TOGGLE && client.player.isSneaking()) {
			status(client, "Stop crouching to adjust blocks", Formatting.GOLD, 40);
			return true;
		}
		return false;
	}

	/** The target is still wanted, workable, stocked, and in coarse reach. */
	private boolean printerTargetStillWanted(MinecraftClient client) {
		DesiredBlock cell = printerTarget;
		if (printerClickedAt.containsKey(cell.pos())) return false;
		double slack = (MAX_REACH + 1.0D) * (MAX_REACH + 1.0D);
		if (client.player.getEyePos().squaredDistanceTo(cell.center()) > slack) return false;
		BlockState goal = desiredStateAt(cell.pos());
		if (goal == null || SchematicBlockRules.isFluid(goal)) return false;
		BlockState wanted = client.world.getBlockState(cell.pos()).isReplaceable() ? dryGoal(goal) : goal;
		// States are canonical singletons, so identity comparison catches a phase change.
		if (wanted != cell.state()) return false;
		if (desiredComplete(client.world, cell.pos(), wanted)
				|| !canWorkOn(client.world, cell.pos(), wanted)) return false;
		return materialAvailable(client, cell.pos(), wanted);
	}

	/**
	 * The job a cell currently supports, or null: the dry placement while the cell is
	 * empty, the full goal once its block exists. Pure fluid cells are skipped.
	 */
	private DesiredBlock printerJobAt(MinecraftClient client, BlockPos pos) {
		if (printerClickedAt.containsKey(pos) || printerDeferUntil.containsKey(pos)) return null;
		BlockState goal = desiredStateAt(pos);
		if (goal == null || SchematicBlockRules.isFluid(goal)) return null;
		BlockState current = client.world.getBlockState(pos);
		BlockState wanted = current.isReplaceable() ? dryGoal(goal) : goal;
		if (desiredComplete(client.world, pos, wanted) || !canWorkOn(client.world, pos, wanted)) return null;
		if (current.isReplaceable()) {
			if (!hasSupport(client.world, pos)) return null;
			if (client.player.getBoundingBox().intersects(new Box(pos).shrink(0.05D, 0.05D, 0.05D))) return null;
		}
		return new DesiredBlock(pos.toImmutable(), wanted);
	}

	/**
	 * Picks the next cell and proves its aim: the cell under the player's view first, then
	 * the rest of reach lowest-first, nearest-first. A cell that fails its proof is parked
	 * for {@link #PRINTER_DEFER_TICKS} so it cannot starve the cells ranked behind it.
	 */
	private void printerChooseTarget(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		Vec3d eye = player.getEyePos();
		List<DesiredBlock> ordered = new ArrayList<>();
		Set<BlockPos> lookedAt = new HashSet<>();

		// What the player is looking at, from the decoupled view rather than the body.
		BlockHitResult look = viewCrosshair(client);
		if (look != null) {
			DesiredBlock finish = printerJobAt(client, look.getBlockPos());
			if (finish != null && lookedAt.add(finish.pos())) ordered.add(finish);
			BlockState looked = client.world.getBlockState(look.getBlockPos());
			BlockPos cell = looked.isReplaceable() ? look.getBlockPos()
					: look.getBlockPos().offset(look.getSide());
			DesiredBlock place = printerJobAt(client, cell);
			if (place != null && lookedAt.add(place.pos())) ordered.add(place);
		}

		BlockPos base = BlockPos.ofFloored(eye.x, eye.y, eye.z);
		int radius = (int) Math.ceil(MAX_REACH);
		List<DesiredBlock> area = new ArrayList<>();
		for (int dy = -radius; dy <= radius; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = base.add(dx, dy, dz);
					if (lookedAt.contains(pos)) continue;
					if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > MAX_REACH_SQUARED) continue;
					DesiredBlock job = printerJobAt(client, pos);
					if (job != null) area.add(job);
				}
			}
		}
		area.sort(Comparator.comparingInt((DesiredBlock cell) -> cell.pos().getY())
				.thenComparingDouble(cell -> eye.squaredDistanceTo(cell.center())));
		ordered.addAll(area);
		if (ordered.isEmpty()) return;

		String missingMaterial = null;
		int proofs = 0;
		for (DesiredBlock cell : ordered) {
			if (proofs >= PRINTER_PROOFS_PER_TICK) break;
			if (!materialAvailable(client, cell.pos(), cell.state())) {
				if (missingMaterial == null) missingMaterial = cell.state().getBlock().getName().getString();
				continue;
			}
			proofs++;
			PlacementAim plan = findPlacementAim(client, cell);
			if (plan == null || printerPostureBlocked(client, plan)) {
				printerDeferUntil.put(cell.pos(), player.age + PRINTER_DEFER_TICKS);
				continue;
			}
			printerTarget = cell;
			printerAimPlan = plan;
			printerPhaseTicks = 0;
			return;
		}
		if (missingMaterial != null) status(client, "Need " + missingMaterial, Formatting.YELLOW, 40);
	}

	/** The ray from the player's view, which is decoupled from the body while silent aim is live. */
	private BlockHitResult viewCrosshair(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		boolean silent = SilentAimController.isActive();
		float yaw = silent ? SilentAimController.viewYaw() : player.getYaw();
		float pitch = silent ? SilentAimController.viewPitch() : player.getPitch();
		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(MAX_REACH));
		BlockHitResult hit = client.world.raycast(new RaycastContext(eye, end,
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	private void printerClearTarget(MinecraftClient client, boolean defer) {
		if (defer && printerTarget != null) {
			printerDeferUntil.put(printerTarget.pos(), client.player.age + PRINTER_DEFER_TICKS);
		}
		printerTarget = null;
		printerAimPlan = null;
		printerPhaseTicks = 0;
	}

	/** Hands the camera back to the player. */
	private void printerDisengage() {
		if (!printerEngaged) return;
		printerEngaged = false;
		SilentAimController.instance().release();
	}

	/** Same-tick visible slot switch, with the selection packet sent immediately. */
	private void selectHotbarSlot(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) return;
		client.player.getInventory().setSelectedSlot(slot);
		client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	private void alignAndBreakTemporary(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		phaseTicks++;
		holdPosture(false);
		if (player.getBoundingBox().intersects(new Box(target.pos()).shrink(0.05D, 0.05D, 0.05D))) {
			// Never mine the block the body occupies.
			retryAfter.put(target.pos(), player.age + TARGET_RETRY_TICKS);
			clearTarget();
			releaseControl();
			return;
		}

		if (placementAim == null || (!breakingTemporary && phaseTicks % 8 == 0)) {
			placementAim = findBreakAim(client, target.pos());
			settleTicks = 0;
			if (placementAim == null) {
				if (phaseTicks > 40) {
					retryAfter.put(target.pos(), player.age + 20);
					clearTarget();
					releaseControl();
				}
				return;
			}
		}

		aimGoal = placementAim.aimPoint();
		aimSpeed = 1.70F;
		ownsRotation = true;
		BlockHitResult live = liveBlockHit(client, target.pos());
		if (live == null) {
			settleTicks = 0;
			if (breakingTemporary) cancelTemporaryBreaking(client);
			return;
		}
		if (++settleTicks < 1) return;

		if (player.getInventory().getSelectedSlot() != placementAim.hotbarSlot()) {
			player.getInventory().setSelectedSlot(placementAim.hotbarSlot());
			settleTicks = 0;
			return;
		}

		if (!breakingTemporary) {
			if (!client.interactionManager.attackBlock(target.pos(), live.getSide())) return;
			breakingTemporary = true;
			breakSwingTicks = 0;
		} else {
			client.interactionManager.updateBlockBreakingProgress(target.pos(), live.getSide());
		}
		if (breakSwingTicks++ % 4 == 0) player.swingHand(Hand.MAIN_HAND);
	}

	private PlacementAim findBreakAim(MinecraftClient client, BlockPos pos) {
		int tool = bestToolSlot(client.player, client.world.getBlockState(pos));
		for (Direction side : Direction.values()) {
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(pos, side, sample);
				BlockHitResult actual = raycastTemporarily(client.player, point);
				if (actual != null && actual.getBlockPos().equals(pos)) {
					return new PlacementAim(point, actual, tool, PlacementKind.BREAK, false);
				}
			}
		}
		return null;
	}

	private BlockHitResult liveBlockHit(MinecraftClient client, BlockPos expected) {
		HitResult raw = client.player.raycast(MAX_REACH, 1.0F, false);
		return raw instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(expected) ? hit : null;
	}

	private int bestToolSlot(ClientPlayerEntity player, BlockState state) {
		int best = player.getInventory().getSelectedSlot();
		float bestSpeed = player.getInventory().getStack(best).getMiningSpeedMultiplier(state);
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isEmpty()) continue;
			float speed = stack.getMiningSpeedMultiplier(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = slot;
			}
		}
		return best;
	}

	private void cancelTemporaryBreaking(MinecraftClient client) {
		if (!breakingTemporary) return;
		if (client != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		breakingTemporary = false;
		breakSwingTicks = 0;
	}

	private PlacementAim findPlacementAim(MinecraftClient client, DesiredBlock desired) {
		BlockState current = client.world.getBlockState(desired.pos());
		if (!current.isReplaceable() && current.getBlock() == desired.state().getBlock()
				&& waterloggedMismatch(current, desired.state())) {
			boolean addWater = propertyValue(desired.state(), "waterlogged").equals("true");
			int slot = ensureItemSlot(client, addWater ? Items.WATER_BUCKET : Items.BUCKET);
			if (slot < 0) return null;
			return findDirectUseAim(client, desired.pos(), slot,
					addWater ? PlacementKind.WATERLOG : PlacementKind.UNWATERLOG);
		}
		if (!current.isReplaceable() && current.getBlock() == desired.state().getBlock()
				&& interactionPropertyMismatch(current, desired.state())) {
			return findDirectUseAim(client, desired.pos(), client.player.getInventory().getSelectedSlot(),
					PlacementKind.TOGGLE);
		}
		if (!current.isReplaceable() && current.getBlock() == desired.state().getBlock()
				&& repeatablePlacementMismatch(current, desired.state())) {
			return findDirectPlacementAim(client, desired);
		}

		for (Direction towardSupport : Direction.values()) {
			BlockPos support = desired.pos().offset(towardSupport);
			BlockState supportState = client.world.getBlockState(support);
			if (supportState.isReplaceable() || supportState.getCollisionShape(client.world, support).isEmpty()) continue;
			Direction clickedSide = towardSupport.getOpposite();
			boolean sneak = SchematicBlockRules.mustSneakAgainst(supportState);
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(support, clickedSide, sample);
				PredictedPlacement predicted = evaluateAim(client, point, support, clickedSide, desired);
				if (predicted != null) {
					return new PlacementAim(point, predicted.hit(), predicted.hotbarSlot(),
							PlacementKind.BLOCK, sneak);
				}
			}
		}
		return null;
	}

	private PlacementAim findDirectPlacementAim(MinecraftClient client, DesiredBlock desired) {
		boolean sneak = SchematicBlockRules.mustSneakAgainst(client.world.getBlockState(desired.pos()));
		for (Direction side : Direction.values()) {
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(desired.pos(), side, sample);
				PredictedPlacement predicted = evaluateAim(client, point, desired.pos(), side, desired);
				if (predicted != null) {
					return new PlacementAim(point, predicted.hit(), predicted.hotbarSlot(),
							PlacementKind.BLOCK, sneak);
				}
			}
		}
		return null;
	}

	/** Aim for a right-click that is not a placement, such as a bucket or a repeater tap. Never sneaks. */
	private PlacementAim findDirectUseAim(MinecraftClient client, BlockPos target, int slot, PlacementKind kind) {
		for (Direction side : Direction.values()) {
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(target, side, sample);
				BlockHitResult actual = raycastTemporarily(client.player, point);
				if (actual != null && actual.getBlockPos().equals(target)) {
					return new PlacementAim(point, actual, slot, kind, false);
				}
			}
		}
		return null;
	}

	private BlockHitResult livePlacementHit(MinecraftClient client, PlacementAim planned, DesiredBlock desired) {
		HitResult raw = client.player.raycast(MAX_REACH, 1.0F, false);
		if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
		if (!hit.getBlockPos().equals(planned.hit().getBlockPos()) || hit.getSide() != planned.hit().getSide()) return null;
		if (planned.kind() != PlacementKind.BLOCK) return hit;
		// Re-predicted under the live rotation so a half-turned head waits instead of
		// placing a directional block the wrong way.
		return placementForHit(client, client.player, hit, desired.pos(), desired.state()) != null ? hit : null;
	}

	/**
	 * Evaluates one candidate aim point with the head held where aiming at it would put it.
	 * Vanilla reads the live yaw at click time, so the raycast and the placement prediction
	 * must run under the same rotation.
	 */
	private PredictedPlacement evaluateAim(MinecraftClient client, Vec3d point, BlockPos expectedHit,
			Direction expectedSide, DesiredBlock desired) {
		ClientPlayerEntity player = client.player;
		if (player.getEyePos().squaredDistanceTo(point) > MAX_REACH_SQUARED) return null;
		float yaw = player.getYaw();
		float pitch = player.getPitch();
		float[] rotation = rotationTo(player.getEyePos(), point);
		try {
			player.setYaw(rotation[0]);
			player.setPitch(rotation[1]);
			HitResult raw = player.raycast(MAX_REACH, 1.0F, false);
			if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
			if (!hit.getBlockPos().equals(expectedHit) || hit.getSide() != expectedSide) return null;
			return placementForHit(client, player, hit, desired.pos(), desired.state());
		} finally {
			player.setYaw(yaw);
			player.setPitch(pitch);
		}
	}

	private BlockHitResult raycastTemporarily(ClientPlayerEntity player, Vec3d point) {
		if (player.getEyePos().squaredDistanceTo(point) > MAX_REACH_SQUARED) return null;
		float yaw = player.getYaw();
		float pitch = player.getPitch();
		float[] rotation = rotationTo(player.getEyePos(), point);
		try {
			player.setYaw(rotation[0]);
			player.setPitch(rotation[1]);
			HitResult result = player.raycast(MAX_REACH, 1.0F, false);
			return result instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK ? block : null;
		} finally {
			player.setYaw(yaw);
			player.setPitch(pitch);
		}
	}

	private PredictedPlacement placementForHit(MinecraftClient client, ClientPlayerEntity player, BlockHitResult hit,
			BlockPos expectedPos, BlockState desired) {
		int slot = findMaterialSlot(client, desired);
		if (slot < 0) return null;
		ItemStack stack = player.getInventory().getStack(slot);
		if (!(stack.getItem() instanceof BlockItem blockItem) || blockItem.getBlock() != desired.getBlock()) return null;
		ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
		BlockPos placeAt = context.getBlockPos().toImmutable();
		if (!context.canPlace()) return null;
		BlockState predicted = ((BlockItemInvoker) blockItem).profps$getPlacementState(context);
		BlockState current = client.world.getBlockState(placeAt);
		if (!placeAt.equals(expectedPos) || predicted == null || !placementMatches(desired, predicted, current)
				|| !((BlockItemInvoker) blockItem).profps$canPlace(context, predicted)) return null;
		BlockHitResult copy = new BlockHitResult(hit.getPos(), hit.getSide(), hit.getBlockPos().toImmutable(), hit.isInsideBlock());
		return new PredictedPlacement(placeAt, copy, slot, desired);
	}

	private int findMaterialSlot(MinecraftClient client, BlockState desired) {
		int existing = findBlockSlot(client.player, desired);
		if (existing >= 0) return existing;
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR) return -1;
		int inventorySlot = findInventoryItemSlot(client.player, item);
		if (inventorySlot >= 0) return moveInventoryItemToHotbar(client, inventorySlot);
		if (!client.player.isCreative()) return -1;

		int emptyHotbar = findEmptyHotbarSlot(client.player);
		if (emptyHotbar >= 0) {
			ItemStack stack = new ItemStack(item, 64);
			client.interactionManager.clickCreativeStack(stack, 36 + emptyHotbar);
			client.player.getInventory().setStack(emptyHotbar, stack);
			return emptyHotbar;
		}

		// With a full hotbar, SWAP leases a slot and preserves the displaced stack.
		int emptyInventory = findEmptyInventorySlot(client.player);
		if (emptyInventory >= 0) {
			ItemStack stack = new ItemStack(item, 64);
			client.interactionManager.clickCreativeStack(stack, emptyInventory);
			client.player.getInventory().setStack(emptyInventory, stack);
			return moveInventoryItemToHotbar(client, emptyInventory);
		}
		return -1;
	}

	/** Read-only availability check used while ranking; it must never mutate the hotbar. */
	private boolean materialAvailable(MinecraftClient client, BlockPos pos, BlockState desired) {
		BlockState current = client.world.getBlockState(pos);
		if (!current.isReplaceable() && current.getBlock() == desired.getBlock()
				&& waterloggedMismatch(current, desired)) {
			boolean addWater = propertyValue(desired, "waterlogged").equals("true");
			return itemAvailable(client, addWater ? Items.WATER_BUCKET : Items.BUCKET);
		}
		if (!current.isReplaceable() && current.getBlock() == desired.getBlock()
				&& interactionPropertyMismatch(current, desired)) return true;
		if (findBlockSlot(client.player, desired) >= 0) return true;
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR) return false;
		if (findInventoryItemSlot(client.player, item) >= 0) return true;
		if (!client.player.isCreative()) return false;
		return findEmptyHotbarSlot(client.player) >= 0 || findEmptyInventorySlot(client.player) >= 0;
	}

	private int findBlockSlot(ClientPlayerEntity player, BlockState desired) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
					&& blockItem.getBlock() == desired.getBlock()) return slot;
		}
		return -1;
	}

	private int findItemSlot(ClientPlayerEntity player, Item item) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isOf(item)) return slot;
		}
		return -1;
	}

	private int ensureItemSlot(MinecraftClient client, Item item) {
		int hotbar = findItemSlot(client.player, item);
		if (hotbar >= 0) return hotbar;
		int inventory = findInventoryItemSlot(client.player, item);
		return inventory < 0 ? -1 : moveInventoryItemToHotbar(client, inventory);
	}

	private boolean itemAvailable(MinecraftClient client, Item item) {
		return findItemSlot(client.player, item) >= 0
				|| findInventoryItemSlot(client.player, item) >= 0;
	}

	private int findInventoryItemSlot(ClientPlayerEntity player, Item item) {
		for (int slot = 9; slot < 36; slot++) {
			if (player.getInventory().getStack(slot).isOf(item)) return slot;
		}
		return -1;
	}

	private int findEmptyHotbarSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	private int findEmptyInventorySlot(ClientPlayerEntity player) {
		for (int slot = 9; slot < 36; slot++) {
			if (player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	/** Moves an inventory stack to the hotbar with a vanilla SWAP; a full hotbar leases the selected slot. */
	private int moveInventoryItemToHotbar(MinecraftClient client, int inventorySlot) {
		int hotbar = findEmptyHotbarSlot(client.player);
		if (hotbar < 0) hotbar = client.player.getInventory().getSelectedSlot();
		client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId,
				inventorySlot, hotbar, SlotActionType.SWAP, client.player);
		return hotbar;
	}

	private boolean desiredComplete(ClientWorld world, BlockPos pos, BlockState desired) {
		BlockState current = world.getBlockState(pos);
		if (current.getBlock() != desired.getBlock()) return false;
		for (Property<?> property : desired.getProperties()) {
			if (PLACEMENT_PROPERTIES.contains(property.getName()) && !sameProperty(desired, current, property)) return false;
		}
		return true;
	}

	private boolean placementMatches(BlockState desired, BlockState predicted, BlockState current) {
		if (desired.getBlock() != predicted.getBlock()) return false;
		for (Property<?> property : desired.getProperties()) {
			String name = property.getName();
			if (!PLACEMENT_PROPERTIES.contains(name) || !predictionControlsProperty(desired, name)
					|| sameProperty(desired, predicted, property)) continue;
			if (!isPlacementProgress(current, desired, predicted, name)) return false;
		}
		return true;
	}

	private boolean predictionControlsProperty(BlockState desired, String name) {
		if (name.equals("shape") || INTERACTION_PROPERTIES.contains(name)) return false;
		return !name.equals("type") || (!desired.isOf(Blocks.CHEST) && !desired.isOf(Blocks.TRAPPED_CHEST));
	}

	private boolean isPlacementProgress(BlockState current, BlockState desired, BlockState predicted, String name) {
		if (STACKING_PROPERTIES.contains(name)) {
			int before = integerProperty(current, name, 0);
			int after = integerProperty(predicted, name, -1);
			int goal = integerProperty(desired, name, -1);
			return after > before && after <= goal;
		}
		return name.equals("type") && propertyValue(desired, "type").equals("double")
				&& current.getBlock() != desired.getBlock();
	}

	private boolean waterloggedMismatch(BlockState current, BlockState desired) {
		String currentValue = propertyValue(current, "waterlogged");
		String desiredValue = propertyValue(desired, "waterlogged");
		return !currentValue.isEmpty() && !desiredValue.isEmpty() && !currentValue.equals(desiredValue);
	}

	private boolean interactionPropertyMismatch(BlockState current, BlockState desired) {
		for (String name : INTERACTION_PROPERTIES) {
			String currentValue = propertyValue(current, name);
			String desiredValue = propertyValue(desired, name);
			if (!currentValue.isEmpty() && !desiredValue.isEmpty() && !currentValue.equals(desiredValue)) return true;
		}
		return false;
	}

	private boolean repeatablePlacementMismatch(BlockState current, BlockState desired) {
		if (propertyValue(desired, "type").equals("double")
				&& !propertyValue(current, "type").equals("double")) return true;
		for (String name : STACKING_PROPERTIES) {
			int before = integerProperty(current, name, -1);
			int goal = integerProperty(desired, name, -1);
			if (before >= 0 && goal > before) return true;
		}
		return false;
	}

	private int integerProperty(BlockState state, String name, int fallback) {
		try {
			String value = propertyValue(state, name);
			return value.isEmpty() ? fallback : Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private String propertyValue(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) return String.valueOf(state.get(property));
		}
		return "";
	}

	private <T extends Comparable<T>> boolean sameProperty(BlockState first, BlockState second, Property<T> property) {
		return first.get(property).equals(second.get(property));
	}

	private boolean hasSupport(ClientWorld world, BlockPos target) {
		for (Direction direction : Direction.values()) {
			BlockPos support = target.offset(direction);
			BlockState state = world.getBlockState(support);
			if (!state.isReplaceable() && !state.getCollisionShape(world, support).isEmpty()) return true;
		}
		return false;
	}

	private Vec3d facePoint(BlockPos block, Direction side, int sample) {
		double a = switch (sample) { case 1 -> 0.28D; case 2 -> 0.72D; default -> 0.50D; };
		double b = switch (sample) { case 3 -> 0.28D; case 4 -> 0.72D; default -> 0.50D; };
		double x = side.getAxis() == Direction.Axis.X ? (side == Direction.EAST ? 1.0D : 0.0D) : a;
		double y = side.getAxis() == Direction.Axis.Y ? (side == Direction.UP ? 1.0D : 0.0D) : b;
		double z = side.getAxis() == Direction.Axis.Z ? (side == Direction.SOUTH ? 1.0D : 0.0D)
				: side.getAxis() == Direction.Axis.X ? a : b;
		return new Vec3d(block.getX() + x, block.getY() + y, block.getZ() + z);
	}

	private float[] rotationTo(Vec3d eye, Vec3d target) {
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return new float[]{
				(float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
				MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -89.0F, 89.0F)
		};
	}

	/** True while the player is clicking; movement keys are not checked. */
	private boolean manualInteracting(MinecraftClient client) {
		return client.options.attackKey.isPressed() || client.options.useKey.isPressed()
				|| client.player.isUsingItem();
	}

	private boolean ready(MinecraftClient client) {
		return config.enabled && config.schematicBuildEnabled && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.player.isAlive() && !client.player.isSpectator() && !client.player.hasVehicle()
				&& !client.player.isGliding();
	}

	private long jitterMs(int minimum, int maximum) {
		return (minimum + random.nextInt(maximum - minimum + 1)) * 1_000_000L;
	}

	private boolean sameDesiredBlock(DesiredBlock first, DesiredBlock second) {
		return first != null && second != null && first.pos().equals(second.pos())
				&& first.state().getBlock() == second.state().getBlock();
	}

	/** Decision trace to latest.log; consecutive duplicates collapse so only transitions are logged. */
	private void trace(String message) {
		if (message.equals(lastTrace)) return;
		lastTrace = message;
		ProFPS.LOGGER.info("[AutoBuild] {}", message);
	}

	private String describe(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private void status(MinecraftClient client, String message, Formatting color, int cooldown) {
		if (client.player.age < nextStatusTick) return;
		client.inGameHud.setOverlayMessage(Text.literal("Auto Build ").formatted(Formatting.AQUA, Formatting.BOLD)
				.append(Text.literal("• " + message).formatted(color)), false);
		nextStatusTick = client.player.age + cooldown;
	}

	/** Publishes a held input so the body stays on the cell the stand proof used. */
	private void holdPosture(boolean sneak) {
		controlling = true;
		movementInput = sneak
				? new PlayerInput(false, false, false, false, false, true, false)
				: PlayerInput.DEFAULT;
	}

	/** Hands back the view and the movement keys. */
	private void releaseControl() {
		controlling = false;
		movementInput = PlayerInput.DEFAULT;
		ownsRotation = false;
		aimGoal = null;
	}

	/**
	 * The pathfinder cell the body occupies, rounded the way {@link WorldSpace#passable}
	 * measures: its body box starts at {@code y + 0.01}.
	 */
	private SchematicPathfinder.Node feetNode(ClientPlayerEntity player) {
		return new SchematicPathfinder.Node(MathHelper.floor(player.getX()),
				MathHelper.ceil(player.getBoundingBox().minY - 0.01D), MathHelper.floor(player.getZ()));
	}

	private void clearTarget() {
		navigator.clear();
		standSneak = false;
		target = null;
		workKind = WorkKind.SCHEMATIC;
		placementAim = null;
		phaseTicks = 0;
		settleTicks = 0;
		confirmationTicks = 0;
		breakingTemporary = false;
		breakSwingTicks = 0;
		placementSent = false;
	}

	private void resetAll() {
		navigator.clear();
		releaseControl();
		clearTarget();
		temporaryQueue.clear();
		temporarySupportGoal = null;
		manualPauseTicks = 0;
		lastFrameNanos = 0L;
		// Absolute player-age deadlines; a respawn restarts the age at zero.
		retryAfter.clear();
		failedStands.clear();
		printerClickedAt.clear();
		printerDeferUntil.clear();
		printerTarget = null;
		printerAimPlan = null;
		printerPhaseTicks = 0;
		printerDisengage();
		nextMaintenanceTick = 0;
		nextCleanupAttemptTick = 0;
		nextRouteAttemptTick = 0;
		nextStatusTick = 0;
		layerProgressTick = Integer.MIN_VALUE;
	}

	private long boundsSignature(List<SourceBounds> bounds) {
		long signature = 0xcbf29ce484222325L;
		for (SourceBounds box : bounds) {
			signature = (signature ^ box.hashCode()) * 0x100000001b3L;
		}
		return signature;
	}

	/** The pathfinder's view of the world: which cells a body may stand in, pass through, or must avoid. */
	private static final class WorldSpace implements SchematicPathfinder.Space {
		private final MinecraftClient client;
		private final ClientPlayerEntity player;

		private WorldSpace(MinecraftClient client, ClientPlayerEntity player) {
			this.client = client;
			this.player = player;
		}

		@Override
		public boolean standable(int x, int y, int z) {
			if (!passable(x, y, z)) return false;
			BlockPos below = new BlockPos(x, y - 1, z);
			BlockState support = client.world.getBlockState(below);
			return !support.getCollisionShape(client.world, below).isEmpty()
					&& !support.getFluidState().isIn(FluidTags.LAVA)
					&& !hazardous(x, y, z);
		}

		@Override
		public boolean passable(int x, int y, int z) {
			if (!client.world.isChunkLoaded(x >> 4, z >> 4)) return false;
			Box body = new Box(x + 0.20D, y + 0.01D, z + 0.20D,
					x + 0.80D, y + 1.79D, z + 0.80D);
			return client.world.isSpaceEmpty(player, body);
		}

		@Override
		public boolean hazardous(int x, int y, int z) {
			for (int dy = -1; dy <= 1; dy++) {
				BlockPos pos = new BlockPos(x, y + dy, z);
				BlockState state = client.world.getBlockState(pos);
				if (state.getFluidState().isIn(FluidTags.LAVA)
						|| state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
						|| state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
						|| state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW)) return true;
			}
			return false;
		}
	}

	private enum WorkKind { SCHEMATIC, TEMP_PLACE, TEMP_REMOVE }
	private enum PlacementKind { BLOCK, WATERLOG, UNWATERLOG, TOGGLE, BREAK }
	private record DesiredBlock(BlockPos pos, BlockState state) {
		Vec3d center() { return Vec3d.ofCenter(pos); }
	}
	/** {@code sneak} is set when the clicked block would otherwise be used, not built against. */
	private record PlacementAim(Vec3d aimPoint, BlockHitResult hit, int hotbarSlot,
			PlacementKind kind, boolean sneak) {}
	private record PredictedPlacement(BlockPos placeAt, BlockHitResult hit, int hotbarSlot, BlockState desired) {}
	private record SourceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int sourceIdentity) {}

	/**
	 * Optional Litematica integration over reflection, keeping the mod a soft dependency.
	 * Each lookup accepts several known spellings and the first failure is recorded in
	 * {@link #diagnosis()}.
	 */
	private static final class LitematicaBridge {
		private Method worldGetter;
		private Method placementManagerGetter;
		private Method getAllPlacements;
		private Method placementEnabled;
		private Method placementBox;
		private boolean initialized;
		private String failure = "";
		private int seenPlacements;
		private int enabledPlacements;

		BlockState stateAt(BlockPos pos) {
			if (!initialize() || worldGetter == null) return null;
			try {
				Object schematicWorld = worldGetter.invoke(null);
				if (schematicWorld instanceof BlockView view) return view.getBlockState(pos);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// A placement reload can briefly make the schematic world unavailable.
			}
			return null;
		}

		List<SourceBounds> bounds() {
			seenPlacements = 0;
			enabledPlacements = 0;
			if (!initialize() || placementManagerGetter == null) return List.of();
			try {
				Object manager = placementManagerGetter.invoke(null);
				Object raw = getAllPlacements.invoke(manager);
				if (!(raw instanceof Collection<?> placements)) return List.of();
				List<SourceBounds> out = new ArrayList<>();
				for (Object placement : placements) {
					seenPlacements++;
					if (!Boolean.TRUE.equals(placementEnabled.invoke(placement))) continue;
					enabledPlacements++;
					Object box = placementBox.invoke(placement);
					if (box == null) continue;
					int[] corners = readCorners(box);
					if (corners == null) continue;
					out.add(new SourceBounds(
							Math.min(corners[0], corners[3]), Math.min(corners[1], corners[4]),
							Math.min(corners[2], corners[5]), Math.max(corners[0], corners[3]),
							Math.max(corners[1], corners[4]), Math.max(corners[2], corners[5]),
							System.identityHashCode(placement)));
				}
				return List.copyOf(out);
			} catch (ReflectiveOperationException | RuntimeException exception) {
				note("calling Litematica failed: " + exception);
				return List.of();
			}
		}

		/** Placements Litematica reported, before the enabled filter. */
		int seenPlacements() {
			return seenPlacements;
		}

		/** Placements that were both reported and switched on. */
		int enabledPlacements() {
			return enabledPlacements;
		}

		/** The first reflection lookup that failed, or empty when the binding is sound. */
		String failure() {
			return failure;
		}

		/** Why no Litematica source came back, phrased for the status line. */
		String diagnosis() {
			if (!FabricLoader.getInstance().isModLoaded("litematica")) {
				return "No Remember capture; Litematica is not installed";
			}
			if (!failure.isEmpty()) {
				return "Litematica's API is not recognised — " + failure;
			}
			if (seenPlacements > 0 && enabledPlacements == 0) {
				return "Litematica has " + seenPlacements + " placement(s), none enabled";
			}
			if (seenPlacements == 0) {
				return "No Remember capture; Litematica has no placement loaded";
			}
			return "No Remember capture or enabled Litematica placement";
		}

		/**
		 * Both corners of a placement box as {@code {x1,y1,z1,x2,y2,z2}}. Accepts either a
		 * pair of {@code BlockPos} getters or the flat {@code minX..maxZ} shape.
		 */
		private int[] readCorners(Object box) {
			Class<?> type = box.getClass();
			Method first = findMethod(type, "getPos1", "getCorner1", "getMinPos");
			Method second = findMethod(type, "getPos2", "getCorner2", "getMaxPos");
			try {
				if (first != null && second != null
						&& first.invoke(box) instanceof BlockPos a && second.invoke(box) instanceof BlockPos b) {
					return new int[]{a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()};
				}
				int[] flat = new int[6];
				String[] names = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
				for (int i = 0; i < names.length; i++) {
					Integer value = readInt(box, type, names[i]);
					if (value == null) return null;
					flat[i] = value;
				}
				return flat;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				return null;
			}
		}

		private Integer readInt(Object box, Class<?> type, String name) {
			try {
				Method getter = findMethod(type, name,
						"get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
				if (getter != null && getter.invoke(box) instanceof Integer value) return value;
				return type.getField(name).getInt(box);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				return null;
			}
		}

		private boolean initialize() {
			if (initialized) return worldGetter != null;
			initialized = true;
			if (!FabricLoader.getInstance().isModLoaded("litematica")) return false;
			try {
				Class<?> handler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
				worldGetter = require(findMethod(handler, "getSchematicWorld"),
						"SchematicWorldHandler.getSchematicWorld");
				Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");
				placementManagerGetter = require(findMethod(dataManager, "getSchematicPlacementManager"),
						"DataManager.getSchematicPlacementManager");
				Class<?> manager = Class.forName(
						"fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
				getAllPlacements = require(findMethod(manager,
						"getAllSchematicsPlacements", "getAllSchematicPlacements", "getAllPlacements"),
						"SchematicPlacementManager.getAllSchematicsPlacements");
				Class<?> placement = Class.forName(
						"fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
				placementEnabled = require(findMethod(placement, "isEnabled"), "SchematicPlacement.isEnabled");
				// Litematica ships this misspelled as "getEclosingBox"; try both spellings.
				placementBox = require(findMethod(placement, "getEclosingBox", "getEnclosingBox"),
						"SchematicPlacement.getEclosingBox");
				if (placementBox == null) placementBox = findBoxGetter(placement);
			} catch (ReflectiveOperationException | LinkageError exception) {
				note("missing class: " + exception);
			}
			if (!failure.isEmpty()) worldGetter = null;
			return worldGetter != null;
		}

		private Method require(Method method, String description) {
			if (method == null) note("cannot find " + description);
			return method;
		}

		private void note(String message) {
			if (!failure.isEmpty()) return;
			failure = message;
			ProFPS.LOGGER.warn("[AutoBuild] Litematica integration unavailable — {}", message);
		}

		private static Method findMethod(Class<?> owner, String... names) {
			for (String name : names) {
				try {
					return owner.getMethod(name);
				} catch (NoSuchMethodException ignored) {
					// Try the next spelling.
				}
			}
			return null;
		}

		/**
		 * Backstop search for the enclosing-box getter. Only no-argument methods returning an
		 * object qualify, so the neighbouring {@code shouldRender…} and {@code toggle…} methods
		 * that share the stem are excluded.
		 */
		private static Method findBoxGetter(Class<?> owner) {
			for (Method method : owner.getMethods()) {
				if (method.getParameterCount() != 0 || method.getReturnType().isPrimitive()
						|| method.getReturnType() == void.class) continue;
				if (method.getName().toLowerCase().contains("closingbox")) return method;
			}
			return null;
		}
	}
}
