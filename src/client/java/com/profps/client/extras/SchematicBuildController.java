package com.profps.client.extras;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.HumanizedAim;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.mixin.BlockItemInvoker;
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
 * Humanized layer-by-layer builder for Remember captures and loaded Litematica
 * placements.
 *
 * <p>Each layer is filled deepest interior cell first, ranked by
 * {@link SchematicLayerOrder}. Filling a wide or thick layer nearest-first
 * closes a shell around the player and strands everything behind it, so the
 * order here always leaves a corridor of still-empty cells between every
 * remaining cell and open space. Layers then repeat as whole-schematic sweeps
 * until one sweep places nothing at all, which is what lets a cell missed for a
 * blocked route or a body in the way still get built.
 *
 * <p>Auto Move uses a bounded A* path made only of legal walk, step/jump, safe
 * drop, or creative-flight cells. It publishes ordinary {@link PlayerInput}; it
 * never teleports or writes velocity. View motion is applied every render frame
 * by the same mouse-grid humanized aim engine used by the mining controllers.
 * Every placement still requires a real raycast, a real support face, an
 * available material, vanilla placement prediction, and vanilla
 * {@code interactBlock}.
 *
 * <p>The controller is never allowed to idle while work is pending: when no
 * cell has a proven stand it walks toward the nearest pending cell anyway,
 * because closing distance is what turns unprovable stands into provable ones.
 * When no stand exists at all it creates one out of temporary blocks — a
 * bridge/staircase causeway planned by {@link SchematicSupportPlanner}, or a
 * straight-up pillar jump-placed beneath the body — then builds from it and
 * cleans it up through the same owned-block ledger as face supports. Movement
 * runs at real player pace: sprint on the ground, sprint-flight in the air.
 */
public final class SchematicBuildController {
	private static final boolean AUTO_MOVE_AVAILABLE = true;
	private static final double MAX_REACH = 4.5D;
	private static final double MAX_REACH_SQUARED = MAX_REACH * MAX_REACH;
	private static final double STAND_REACH = 4.30D;
	private static final long RECENT_POSITION_NS = 900_000_000L;
	private static final int SOURCE_SCAN_PER_TICK = 32_768;
	private static final int MAX_PENDING_PER_LAYER = 65_536;
	private static final int MAX_FOOTPRINT_CELLS = 262_144;
	// Ticks a layer may go without placing anything before its remainder is
	// carried to the next sweep. Measured from the last real placement, not from
	// failed attempts: a layer that keeps starting targets and abandoning them is
	// exactly as stuck as one that can start none. The sweeps are what make
	// "never miss a block" terminate.
	private static final int LAYER_STALL_TICKS = 600;
	private static final int MAX_BUILD_PASSES = 64;
	private static final int MAX_LAYER_STEPS_PER_TICK = 64;
	private static final int STAND_RADIUS = 4;
	private static final int STAND_MIN_DY = -3;
	private static final int STAND_MAX_DY = 3;
	// Every in-reach candidate is examined; the accept limit is what keeps the
	// common case cheap, since candidates are ranked before they are proved.
	private static final int STAND_EXAMINE_LIMIT = 512;
	private static final int STAND_ACCEPT_LIMIT = 16;
	private static final int MAX_REPLANS_PER_TARGET = 6;
	private static final int ANCHOR_STAND_PROOFS_PER_PLAN = 384;
	private static final int UNSTICK_TRIGGER_TICKS = 16;
	private static final int UNSTICK_BURST_TICKS = 9;
	// Never-idle navigation: when no pending cell has a provable stand this
	// tick, walk toward the nearest one anyway. Approach starts outside this
	// distance and asks the partial-path search for only modest progress —
	// 8 keeps the search's minimum-progress clamp at its 2-block floor, so a
	// short trip (the "standing two blocks behind the build" case) still gets
	// a route instead of being refused for lack of a long corridor.
	private static final double APPROACH_MIN_DISTANCE = 4.0D;
	private static final double APPROACH_PROGRESS = 8.0D;
	private static final int NAV_CANDIDATES_PER_TICK = 3;
	// Stand scaffolding: temporary blocks placed for the BODY, not the block.
	private static final int SCAFFOLD_STAND_EXAMINE_LIMIT = 40;
	private static final int SCAFFOLD_STAND_PLAN_ATTEMPTS = 8;
	private static final int MAX_PILLAR_HEIGHT = 24;
	private static final int PILLAR_STALL_TICKS = 70;
	private static final int MAX_PATH_NODES = 32_768;
	private static final int SUPPORT_PATH_NODES = 32_768;
	private static final int SUPPORT_HORIZONTAL_HORIZON = 128;
	private static final int SUPPORT_DOWNWARD_HORIZON = 128;
	private static final int MAX_TEMPORARY_BLOCKS_PER_PLAN = 320;
	private static final int MANUAL_PAUSE_TICKS = 10;
	private static final int TARGET_RETRY_TICKS = 35;
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
	private final LitematicaBridge litematica = new LitematicaBridge();

	// Manual-hover mode state (the pre-Auto-Move behavior).
	private HoverTarget hoverTarget;
	private long hoverReadyNanos;
	private long lastPlaceNanos;
	private BlockPos recentPosition;
	private long recentPositionUntil;

	// Published input + frame-driven look goal.
	private volatile boolean controlling;
	private volatile boolean ownsRotation;
	private volatile PlayerInput movementInput = PlayerInput.DEFAULT;
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
	private int passPlacements;
	private int passUnplaced;
	private BlockPos passUnplacedSample;

	// Current target/navigation/placement state.
	private DesiredBlock target;
	private WorkKind workKind = WorkKind.SCHEMATIC;
	private PlacementAim placementAim;
	private List<SchematicPathfinder.Node> path = List.of();
	private int pathIndex;
	private boolean navigationComplete = true;
	private int transitWaitTicks;
	private int phaseTicks;
	private int settleTicks;
	private int confirmationTicks;
	private int manualPauseTicks;
	private int flightActivationPhase;
	private int flightAttemptCooldownUntil;
	private int stuckTicks;
	private int unstickTicks;
	private int unstickStrafe;
	private int replanAttempts;
	private double lastWaypointDistance = Double.POSITIVE_INFINITY;
	private Vec3d lastDrivePosition;
	private final ArrayDeque<DesiredBlock> temporaryQueue = new ArrayDeque<>();
	private final LinkedHashMap<BlockPos, BlockState> ownedTemporaryBlocks = new LinkedHashMap<>();
	private DesiredBlock temporarySupportGoal;
	// Pillar scaffold: jump-place a column beneath the body up to pillarTopY.
	private int pillarColumnX;
	private int pillarColumnZ;
	private int pillarTopY;
	private int pillarStallTicks;
	private int lastPillarFeetY = Integer.MIN_VALUE;
	private BlockState pillarMaterial;
	private int nextCleanupAttemptTick;
	private boolean placementSent;
	private boolean breakingTemporary;
	private int breakSwingTicks;
	private final Map<BlockPos, Integer> retryAfter = new HashMap<>();
	private final Map<BlockPos, Set<SchematicPathfinder.Node>> failedStands = new HashMap<>();
	private int nextStatusTick;
	private boolean wasEnabled;
	private String lastTrace = "";

	public SchematicBuildController(ProFPSConfig config, RememberController remember) {
		this.config = config;
		this.remember = remember;
		instance = this;
	}

	/** True while Auto Move owns the ordinary movement input path. */
	public static boolean isAutoMoving() {
		return instance != null && instance.controlling;
	}

	/** Input applied by {@code InputMixin} after the keyboard has been sampled. */
	public static PlayerInput movementInput() {
		return instance == null ? PlayerInput.DEFAULT : instance.movementInput;
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	/** Render-frame steering: smooth at the display refresh rate, not 20 Hz. */
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

		if (!AUTO_MOVE_AVAILABLE || !config.schematicAutoMove) {
			cancelTemporaryBreaking(client);
			releaseAuto();
			tickManualHover(client);
			wasEnabled = true;
			return;
		}

		resetHover();
		if (!wasEnabled) {
			resetPlanning();
			wasEnabled = true;
		}

		if (manualInput(client)) {
			cancelTemporaryBreaking(client);
			flightActivationPhase = 0;
			manualPauseTicks = MANUAL_PAUSE_TICKS;
			releaseAuto();
			return;
		}
		if (manualPauseTicks > 0) {
			manualPauseTicks--;
			releaseAuto();
			return;
		}

		if (FreecamController.isActive() || TunnelController.isControlling()) {
			cancelTemporaryBreaking(client);
			flightActivationPhase = 0;
			releaseAuto();
			return;
		}

		syncSources(client);
		if (remembered.isEmpty() && schematicBounds.isEmpty()) {
			releaseAuto();
			status(client, "No Remember capture or enabled Litematica placement", Formatting.GRAY, 80);
			return;
		}
		if (buildFinished && ownedTemporaryBlocks.isEmpty() && client.player.age >= nextMaintenanceTick) {
			resetLayerScanner();
			status(client, "Checking the completed build", Formatting.GRAY, 0);
		}
		if (flightActivationPhase > 0) {
			driveCreativeFlightActivation(client);
			return;
		}

		int tick = client.player.age;
		retryAfter.entrySet().removeIf(entry -> tick >= entry.getValue());
		// Scaffold time is neither layer progress nor layer stall. Hold the layer
		// clock still for the length of a support route: letting it run would
		// abandon the cell on the tick its scaffold finished, and crediting its
		// blocks as progress would let scaffolding alone keep a layer alive
		// forever without ever placing a schematic block.
		if (layerProgressTick != Integer.MIN_VALUE
				&& (!temporaryQueue.isEmpty() || workKind == WorkKind.TEMP_PLACE
						|| workKind == WorkKind.PILLAR)) {
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
					// Another player/server changed it. Relinquish ownership; never
					// mine a block Auto Build can no longer prove it placed.
					cancelTemporaryBreaking(client);
					ownedTemporaryBlocks.remove(target.pos());
					nextCleanupAttemptTick = 0;
					if (ownedTemporaryBlocks.isEmpty()) nextMaintenanceTick = client.player.age + 100;
					clearTarget();
				}
			} else if (desiredComplete(client.world, target.pos(), target.state())) {
				if (workKind == WorkKind.TEMP_PLACE) {
					// Only claim cleanup ownership after this controller sent the
					// accepted placement. A matching block that appeared first is
					// usable support, but belongs to the world or another player.
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
				failedStands.remove(target.pos());
				clearTarget();
			}
		}

		if (confirmationTicks > 0) {
			confirmationTicks--;
			releaseMovementOnly();
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

		if (target == null) {
			if (!prepareTarget(client)) {
				releaseAuto();
				return;
			}
		}

		if (target == null) return;
		if (pathIndex < path.size()) {
			drivePath(client);
			return;
		}
		if (!navigationComplete) {
			continueTransit(client);
			return;
		}

		if (workKind == WorkKind.TEMP_REMOVE) alignAndBreakTemporary(client);
		else if (workKind == WorkKind.PILLAR) drivePillar(client);
		else alignAndPlace(client);
	}

	// ── Layer planning ─────────────────────────────────────────────────────────

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
		List<SourceBounds> bounds = litematica.bounds();
		long signature = boundsSignature(bounds);
		if (rememberRevision == knownRememberRevision && signature == knownLitematicaSignature) return;

		knownRememberRevision = rememberRevision;
		knownLitematicaSignature = signature;
		remembered = remember.desiredStatesSnapshot();
		rememberedEntries = new ArrayList<>(remembered.entrySet());
		schematicBounds = bounds;
		trace("sources changed: " + remembered.size() + " remembered cell(s), "
				+ bounds.size() + " litematica placement(s)");
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

	/**
	 * Begins one bottom-to-top sweep. Sweeps repeat until a whole one places
	 * nothing, so a cell skipped for a blocked route, a missing material, or a
	 * body that was standing in the way is always retried later.
	 */
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

		// Layers, depth bands, and sweeps step forward in a loop rather than by
		// recursion: a tall schematic can skip hundreds of empty layers at once.
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
			// Interior-first ordering is only meaningful once the whole layer
			// footprint is known, so nothing is placed part-way through a scan.
			if (!layerScanComplete) return false;
		}
		if (!layerDepthReady) {
			layerDepth = layerFootprint.size() >= MAX_FOOTPRINT_CELLS
					? Map.of() : SchematicLayerOrder.depths(layerFootprint);
			layerDepthReady = true;
			// A layer too wide to hold at once filled its first window in raster
			// order. Now that depth is known, refill it deepest band first.
			if (layerOverflowed && layerDepthFloor == Integer.MIN_VALUE && !layerDepth.isEmpty()) {
				rescanCurrentLayer();
				return null;
			}
		}

		// Stamp on entry, and re-stamp if the age ran backwards past a respawn.
		int now = client.player.age;
		if (layerProgressTick == Integer.MIN_VALUE || layerProgressTick > now) layerProgressTick = now;
		if (!pending.isEmpty() && now - layerProgressTick > LAYER_STALL_TICKS) return advanceLayer(client);

		// Several candidates per tick: one cell with a broken stand proof must
		// never cost a whole tick of stillness while its neighbors are workable.
		boolean scaffoldTried = false;
		for (int attempt = 0; attempt < NAV_CANDIDATES_PER_TICK; attempt++) {
			DesiredBlock next = prioritizedSupportGoal(client);
			if (next == null) next = choosePendingTarget(client);
			if (next == null) break;

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

			NavigationPlan plan = navigationPlan(client, next);
			if (plan != null) {
				beginWork(next, WorkKind.SCHEMATIC, plan);
				return true;
			}
			if (flightActivationPhase > 0) return false;
			trace("no route to " + describe(next.pos()) + " (attempt " + (attempt + 1) + ")");
			// No walkable stand exists anywhere in reach of this cell. Make one:
			// a temporary causeway or pillar the body can occupy is exactly as
			// legitimate as the face supports planned for unsupported cells.
			// One planning burst per tick keeps the worst case off the frame time.
			if (!scaffoldTried) {
				scaffoldTried = true;
				if (enqueueStandScaffold(client, next)) return true;
			}
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
			// Standing still never fixes a failed stand proof; closing distance
			// often does. Walk at the nearest pending cell whenever we are not
			// already beside it, and re-prove everything from closer range.
			if (approachPending(client)) return true;
			status(client, "Layer " + activeLayer + ": " + pending.size() + " cell(s) waiting for a route",
					Formatting.GRAY, 40);
			return false;
		}

		// The pending window filled up before the layer was fully swept, so the
		// remainder of this same layer is picked up now rather than skipped.
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

		// A sweep that still placed something proves the world changed under the
		// previous one, so another sweep can reach more. Only a sweep that moves
		// nothing at all can honestly be called finished.
		if (passPlacements > 0 && passIndex + 1 < MAX_BUILD_PASSES) {
			passIndex++;
			startBuildPass();
			status(client, "Verify sweep " + (passIndex + 1) + " over every layer", Formatting.AQUA, 0);
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
				BlockState desired = litematica.stateAt(pos);
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
		// Completed cells still belong to the footprint: to a builder walking the
		// layer they are walls to route around exactly like the ones still to
		// place, so the interior-first depth has to see them.
		if (layerFootprint.size() < MAX_FOOTPRINT_CELLS) {
			layerFootprint.add(SchematicLayerOrder.key(pos.getX(), pos.getZ()));
		}
		if (desiredComplete(world, pos, desired) || pendingKeys.contains(pos)) return;
		if (depthOf(pos) < layerDepthFloor || pending.size() >= MAX_PENDING_PER_LAYER) {
			layerOverflowed = true;
			return;
		}
		pendingKeys.add(pos);
		pending.put(pos, desired);
	}

	/** How far inside the layer footprint a cell sits; 1 is the outer shell. */
	private int depthOf(BlockPos pos) {
		return layerDepth.getOrDefault(SchematicLayerOrder.key(pos.getX(), pos.getZ()), 1);
	}

	/**
	 * Picks the next cell to place. Depth is a strict primary key: the deepest
	 * cell of the layer always goes first, so every cell still to come keeps a
	 * corridor of empty cells out to open space and can never be sealed in.
	 * Reach and distance only break ties inside one depth ring, which keeps the
	 * builder placing a whole ring from one stand before it walks again.
	 */
	private DesiredBlock choosePendingTarget(MinecraftClient client) {
		Vec3d eye = client.player.getEyePos();
		Vec3d feet = client.player.getEntityPos();
		Map.Entry<BlockPos, BlockState> best = null;
		int bestDepth = Integer.MIN_VALUE;
		boolean bestInReach = false;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (Map.Entry<BlockPos, BlockState> entry : pending.entrySet()) {
			BlockPos pos = entry.getKey();
			int depth = depthOf(pos);
			if (depth < bestDepth || retryAfter.containsKey(pos)
					|| !materialAvailable(client, pos, entry.getValue())
					|| !canWorkOn(client.world, pos, entry.getValue())) continue;

			Vec3d center = Vec3d.ofCenter(pos);
			boolean inReach = eye.squaredDistanceTo(center) <= MAX_REACH_SQUARED;
			double distance = feet.squaredDistanceTo(center);
			if (depth == bestDepth && !(inReach && !bestInReach)
					&& !(inReach == bestInReach && distance < bestDistance)) continue;

			best = entry;
			bestDepth = depth;
			bestInReach = inReach;
			bestDistance = distance;
		}
		return best == null ? null : new DesiredBlock(best.getKey(), best.getValue());
	}

	/**
	 * Deepest band of this layer that still fits the pending window, used only
	 * when a layer is too wide to hold at once. Banding by depth keeps the
	 * interior-first order intact across the windows.
	 */
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
			// One band always makes progress, even if that band alone overflows
			// the window: the leftovers come back on the next rescan.
			if (running > MAX_PENDING_PER_LAYER) return Math.min(depth + 1, exclusiveCeiling - 1);
		}
		return Integer.MIN_VALUE;
	}

	private boolean canWorkOn(ClientWorld world, BlockPos pos, BlockState desired) {
		BlockState current = world.getBlockState(pos);
		// Unsupported air is still valid work: the temporary-support planner can
		// turn it into an ordinary, face-backed placement before navigation begins.
		if (current.isReplaceable()) return true;
		return current.getBlock() == desired.getBlock()
				&& (waterloggedMismatch(current, desired) || interactionPropertyMismatch(current, desired)
				|| repeatablePlacementMismatch(current, desired));
	}

	// ── Temporary support planning / cleanup ─────────────────────────────────

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
			// This is the exact backing face for ladders, wall torches/signs,
			// and similar states that expose facing without a face property.
			if (facing != null && facing.getAxis().isHorizontal()) order.add(facing.getOpposite());
			order.addAll(List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
					Direction.WEST, Direction.EAST, Direction.UP));
		}

		// Vanilla survival rules precisely identify which candidate can actually
		// hold a facing-only attachment. Free-standing blocks (stairs, furnaces,
		// slabs, etc.) retain the broader ordered list.
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
				// This action was never sent, so the pre-existing matching block
				// is not added to the cleanup ownership ledger.
				temporaryQueue.removeFirst();
				continue;
			}
			if (!current.isReplaceable() && !current.getCollisionShape(client.world, next.pos()).isEmpty()) {
				// A world-owned solid appeared on the route. It can act as the
				// same physical link, but is never added to our cleanup ledger.
				temporaryQueue.removeFirst();
				continue;
			}
			if (!current.isReplaceable() || !hasSupport(client.world, next.pos())) {
				retryAfter.put(next.pos(), client.player.age + TEMPORARY_CELL_RETRY_TICKS);
				deferTemporarySupport(client);
				return false;
			}
			NavigationPlan plan = navigationPlan(client, next);
			if (plan != null) {
				beginWork(next, WorkKind.TEMP_PLACE, plan);
				return true;
			}
			if (flightActivationPhase > 0) return false;
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
				// Deliberately relinquish the ledger and leave the block: removing
				// it could destroy a completed attachment or a source that changed.
				ownedTemporaryBlocks.remove(pos);
				continue;
			}
			NavigationPlan plan = navigationPlan(client, new DesiredBlock(pos, entry.getValue()));
			if (plan == null) {
				if (flightActivationPhase > 0) return false;
				// Standing on the very block (a pillar top): break beneath the
				// feet and ride the column down, exactly like a player would.
				if (client.player.isOnGround() && client.player.getBlockPos().down().equals(pos)
						&& safeToBreakUnderFeet(client, pos)) {
					nextCleanupAttemptTick = 0;
					beginWork(new DesiredBlock(pos, entry.getValue()), WorkKind.TEMP_REMOVE,
							new NavigationPlan(List.of(feetNode(client.player)), true));
					status(client, "Descending the temporary pillar", Formatting.GRAY, 20);
					return true;
				}
				retryAfter.put(pos, client.player.age + TARGET_RETRY_TICKS);
				continue;
			}
			nextCleanupAttemptTick = 0;
			beginWork(new DesiredBlock(pos, entry.getValue()), WorkKind.TEMP_REMOVE, plan);
			status(client, "Cleaning temporary supports (" + ownedTemporaryBlocks.size() + " left)",
					Formatting.GRAY, 20);
			return true;
		}
		// No tracked block currently has a safe reachable stand.
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

	// ── Never-idle approach + stand scaffolding ───────────────────────────────

	/**
	 * Publishes a bounded partial route toward the nearest pending cell outside
	 * its retry window. Failed stand proofs are usually distance problems in
	 * disguise — occluded rays, unloaded cells, horizon cutoffs — so the honest
	 * response to "nothing is provable" is to close distance and re-prove, hop
	 * by hop, not to stand still for a retry window.
	 */
	private boolean approachPending(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		Vec3d feet = player.getEntityPos();
		Map.Entry<BlockPos, BlockState> nearest = null;
		double nearestSq = Double.POSITIVE_INFINITY;
		// Any cell of the layer is a fine approach magnet; a bounded sample
		// keeps this off the frame time for very wide pending windows.
		int examined = 0;
		for (Map.Entry<BlockPos, BlockState> entry : pending.entrySet()) {
			if (examined++ >= 768) break;
			if (retryAfter.containsKey(entry.getKey())
					|| !materialAvailable(client, entry.getKey(), entry.getValue())) continue;
			double sq = feet.squaredDistanceTo(Vec3d.ofCenter(entry.getKey()));
			if (sq < nearestSq) {
				nearestSq = sq;
				nearest = entry;
			}
		}
		if (nearest == null || nearestSq < APPROACH_MIN_DISTANCE * APPROACH_MIN_DISTANCE) return false;

		boolean flight = player.getAbilities().flying;
		SchematicPathfinder.Node start = feetNode(player);
		WorldSpace space = new WorldSpace(client, player);
		List<SchematicPathfinder.Node> goals = theoreticalPlacementStands(nearest.getKey());
		List<SchematicPathfinder.Node> progress = flight
				? SchematicPathfinder.flightPathTowardAny(start, goals, space, MAX_PATH_NODES / 4, APPROACH_PROGRESS)
				: SchematicPathfinder.groundPathTowardAny(start, goals, space, MAX_PATH_NODES / 4, APPROACH_PROGRESS);
		if (progress.size() <= 1) return false;
		trace("approach hop toward " + describe(nearest.getKey()) + " via " + progress.getLast());
		beginWork(new DesiredBlock(nearest.getKey(), nearest.getValue()), WorkKind.SCHEMATIC,
				new NavigationPlan(progress, false));
		status(client, "Moving to layer " + activeLayer, Formatting.AQUA, 20);
		return true;
	}

	/**
	 * Creates a place to stand when no walkable stand exists for {@code desired}.
	 * First choice is a bridge/staircase causeway planned back to reachable
	 * terrain by {@link SchematicSupportPlanner} — the reversed plan is walkable
	 * by construction, and it serves both missing-floor stands and standable
	 * islands that merely lack a route. When no causeway fits, a straight
	 * jump-placed pillar needs only one clear column. Every block goes through
	 * the owned-temporary ledger, so cleanup is identical to face supports.
	 */
	private boolean enqueueStandScaffold(MinecraftClient client, DesiredBlock desired) {
		if (!config.schematicTemporaryBlocks) return false;
		BlockState material = chooseTemporaryMaterial(client);
		if (material == null) return false;
		int available = client.player.isCreative() ? MAX_TEMPORARY_BLOCKS_PER_PLAN
				: Math.min(MAX_TEMPORARY_BLOCKS_PER_PLAN, expendableBlockCount(client, material));
		if (available <= 0) return false;

		List<StandCandidate> stands = scaffoldStandCandidates(client, desired.pos());
		for (StandCandidate candidate : stands) {
			SchematicPathfinder.Node stand = candidate.node();
			BlockPos floor = new BlockPos(stand.x(), stand.y() - 1, stand.z());
			Set<BlockPos> body = Set.of(new BlockPos(stand.x(), stand.y(), stand.z()),
					new BlockPos(stand.x(), stand.y() + 1, stand.z()));
			boolean floorMissing = client.world.getBlockState(floor).isReplaceable();
			// Never squat a cell the schematic itself wants: cleanup deliberately
			// abandons owned blocks on desired cells, so a temp floor there would
			// become a permanent wrong block.
			if (floorMissing && desiredStateAt(floor) != null) continue;
			List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.plan(
					supportCell(floor), new TemporarySupportSpace(client, desired.pos(), body),
					SUPPORT_PATH_NODES / 4, SUPPORT_HORIZONTAL_HORIZON, SUPPORT_DOWNWARD_HORIZON,
					Math.max(0, available - (floorMissing ? 1 : 0)));
			if (plan.isEmpty()) continue;

			temporaryQueue.clear();
			for (SchematicSupportPlanner.Cell cell : plan) {
				BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
				if (client.world.getBlockState(pos).isReplaceable()) {
					temporaryQueue.addLast(new DesiredBlock(pos, material));
				}
			}
			if (floorMissing) temporaryQueue.addLast(new DesiredBlock(floor, material));
			if (temporaryQueue.isEmpty()) continue;
			temporarySupportGoal = desired;
			trace("causeway " + temporaryQueue.size() + " block(s) toward stand " + stand
					+ " for " + describe(desired.pos()));
			status(client, "Building a " + temporaryQueue.size() + "-block route to a stand", Formatting.AQUA, 0);
			if (prepareTemporaryTarget(client)) return true;
			// The failed attempt deferred the goal cell; lift that so the next
			// candidate stand still gets its try at full priority.
			retryAfter.remove(desired.pos());
			temporaryQueue.clear();
			temporarySupportGoal = null;
		}

		return planPillar(client, desired, stands, material, available);
	}

	/**
	 * Theoretical stands worth scaffolding toward, cheapest first. The body
	 * cells must already be open and the hypothetical eye must have a real ray
	 * to the work; the floor (or the route to it) is what gets created.
	 */
	private List<StandCandidate> scaffoldStandCandidates(MinecraftClient client, BlockPos target) {
		SchematicPathfinder.Node feet = feetNode(client.player);
		double eyeHeight = client.player.getStandingEyeHeight();
		Vec3d center = Vec3d.ofCenter(target);
		Set<SchematicPathfinder.Node> rejected = failedStands.getOrDefault(target, Set.of());
		List<StandCandidate> ranked = new ArrayList<>();
		for (SchematicPathfinder.Node node : theoreticalPlacementStands(target)) {
			if (rejected.contains(node)) continue;
			Vec3d eye = new Vec3d(node.x() + 0.5D, node.y() + eyeHeight, node.z() + 0.5D);
			if (eye.squaredDistanceTo(center) > STAND_REACH * STAND_REACH) continue;
			double cost = Math.sqrt(node.squaredDistanceTo(feet));
			// Stands at or below the work are cheaper to build to and never
			// occlude the still-empty layer above it.
			if (node.y() > target.getY()) cost += (node.y() - target.getY()) * 1.5D;
			ranked.add(new StandCandidate(node, eye, cost));
		}
		ranked.sort(Comparator.comparingDouble(StandCandidate::cost));

		List<StandCandidate> out = new ArrayList<>();
		WorldSpace space = new WorldSpace(client, client.player);
		int examined = 0;
		for (StandCandidate candidate : ranked) {
			if (examined++ >= SCAFFOLD_STAND_EXAMINE_LIMIT
					|| out.size() >= SCAFFOLD_STAND_PLAN_ATTEMPTS) break;
			SchematicPathfinder.Node node = candidate.node();
			if (!space.passable(node.x(), node.y(), node.z())
					|| space.hazardous(node.x(), node.y(), node.z())
					|| !standCanSeeWork(client, candidate.eye(), target)) continue;
			out.add(candidate);
		}
		return out;
	}

	/**
	 * Straight-up pillar to a stand cell: walk to the ground beneath its column,
	 * then jump-place the column beneath the body. Needs no horizontal room at
	 * all, which is exactly the case the causeway planner cannot serve.
	 */
	private boolean planPillar(MinecraftClient client, DesiredBlock desired,
			List<StandCandidate> stands, BlockState material, int available) {
		if (client.player.getAbilities().flying) return false;
		WorldSpace space = new WorldSpace(client, client.player);
		for (StandCandidate candidate : stands) {
			SchematicPathfinder.Node stand = candidate.node();
			int base = Integer.MIN_VALUE;
			boolean columnClean = true;
			for (int y = stand.y() - 1; y >= stand.y() - MAX_PILLAR_HEIGHT; y--) {
				// Every cell from the base up gets filled, so none of them may be
				// a cell the schematic itself wants: cleanup deliberately abandons
				// owned blocks on desired cells, and a temp block there would
				// outlive the build as a permanent wrong block.
				if (desiredStateAt(new BlockPos(stand.x(), y, stand.z())) != null) {
					columnClean = false;
					break;
				}
				if (space.standable(stand.x(), y, stand.z())) {
					base = y;
					break;
				}
				if (!space.passable(stand.x(), y, stand.z())) break;
			}
			if (!columnClean) continue;
			int height = stand.y() - base;
			if (base == Integer.MIN_VALUE || height <= 0 || height > available
					|| space.hazardous(stand.x(), base, stand.z())) continue;

			SchematicPathfinder.Node baseNode = new SchematicPathfinder.Node(stand.x(), base, stand.z());
			SchematicPathfinder.Node start = feetNode(client.player);
			List<SchematicPathfinder.Node> path = start.equals(baseNode) ? List.of(start)
					: SchematicPathfinder.groundPathToAny(start, List.of(baseNode), space, MAX_PATH_NODES / 2);
			if (path.isEmpty()) continue;

			pillarColumnX = stand.x();
			pillarColumnZ = stand.z();
			pillarTopY = stand.y();
			pillarMaterial = material;
			temporarySupportGoal = desired;
			trace("pillar " + height + " block(s) at " + stand.x() + "," + stand.z()
					+ " up to y=" + stand.y() + " for " + describe(desired.pos()));
			beginWork(desired, WorkKind.PILLAR, new NavigationPlan(path, true));
			status(client, "Pillaring " + height + " block(s) up to a stand", Formatting.AQUA, 0);
			return true;
		}
		return false;
	}

	/** Executes one pillar tick: centre over the column, jump, place, repeat. */
	private void drivePillar(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		releaseMovementOnly();
		controlling = true;
		phaseTicks++;

		int feetY = MathHelper.floor(player.getBoundingBox().minY + 1.0E-3D);
		if (feetY != lastPillarFeetY) {
			lastPillarFeetY = feetY;
			pillarStallTicks = 0;
		} else if (++pillarStallTicks > PILLAR_STALL_TICKS) {
			abandonTarget(client);
			return;
		}
		if (feetY >= pillarTopY && player.isOnGround()) {
			// The stand exists now. Normal selection re-proves the route (a
			// one-node path onto the pillar top) and places from up here; the
			// support goal keeps this cell first in line.
			clearTarget();
			return;
		}

		// Stay centred over the column; a drifted jump lands beside the pillar.
		Vec3d centre = new Vec3d(pillarColumnX + 0.5D, player.getY(), pillarColumnZ + 0.5D);
		if (horizontalDistanceSq(player.getEntityPos(), centre) > 0.04D && player.isOnGround()) {
			driveToPoint(player, centre);
			return;
		}

		BlockPos support = null;
		for (int y = Math.min(feetY, pillarTopY) - 1; y >= pillarTopY - MAX_PILLAR_HEIGHT - 1; y--) {
			BlockPos pos = new BlockPos(pillarColumnX, y, pillarColumnZ);
			BlockState state = client.world.getBlockState(pos);
			if (!state.isReplaceable() && !state.getCollisionShape(client.world, pos).isEmpty()) {
				support = pos;
				break;
			}
		}
		if (support == null) {
			abandonTarget(client);
			return;
		}

		aimGoal = facePoint(support, Direction.UP, 0);
		aimSpeed = 1.9F;
		ownsRotation = true;
		// Press jump only while grounded: releasing in the air clears vanilla's
		// held-jump cooldown, so every landing takes off again immediately.
		movementInput = new PlayerInput(false, false, false, false, player.isOnGround(), false, false);

		BlockPos placeCell = support.up();
		if (placeCell.getY() >= pillarTopY) return; // column finished; landing
		int slot = findMaterialSlot(client, pillarMaterial);
		if (slot < 0) {
			abandonTarget(client);
			return;
		}
		if (player.getInventory().getSelectedSlot() != slot) {
			player.getInventory().setSelectedSlot(slot);
			return;
		}
		// The body must be fully above the cell being filled, or vanilla refuses.
		if (player.getBoundingBox().minY < placeCell.getY() + 1.0D - 1.0E-3D) return;

		HitResult raw = player.raycast(MAX_REACH, 1.0F, false);
		if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
				|| !hit.getBlockPos().equals(support) || hit.getSide() != Direction.UP) return;
		long now = System.nanoTime();
		if (now < lastPlaceNanos + jitterMs(28, 46)) return;
		if (placementForHit(client, player, hit, placeCell, pillarMaterial) == null) return;
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		if (!result.isAccepted()) return;
		player.swingHand(Hand.MAIN_HAND);
		lastPlaceNanos = now;
		ownedTemporaryBlocks.put(placeCell.toImmutable(), pillarMaterial);
	}

	/** True when removing this block drops the body at most three onto safe ground. */
	private boolean safeToBreakUnderFeet(MinecraftClient client, BlockPos removed) {
		for (int drop = 1; drop <= 3; drop++) {
			BlockPos below = removed.down(drop);
			BlockState state = client.world.getBlockState(below);
			if (!state.getFluidState().isEmpty()) return false;
			if (!state.isReplaceable() && !state.getCollisionShape(client.world, below).isEmpty()) {
				return !new WorldSpace(client, client.player)
						.hazardous(removed.getX(), below.getY() + 1, removed.getZ());
			}
		}
		return false;
	}

	private void beginWork(DesiredBlock next, WorkKind kind, NavigationPlan plan) {
		trace("work " + kind + " -> " + describe(next.pos()) + " ("
				+ next.state().getBlock().getName().getString() + ") path=" + plan.path().size()
				+ (plan.complete() ? "" : " partial")
				+ (plan.path().isEmpty() ? "" : " stand=" + plan.path().getLast()));
		target = next;
		workKind = kind;
		path = plan.path();
		pathIndex = Math.min(1, path.size());
		navigationComplete = plan.complete();
		transitWaitTicks = plan.complete() ? 0 : 4;
		phaseTicks = 0;
		settleTicks = 0;
		placementAim = null;
		stuckTicks = 0;
		lastWaypointDistance = Double.POSITIVE_INFINITY;
		lastDrivePosition = null;
		breakingTemporary = false;
		breakSwingTicks = 0;
		placementSent = false;
		unstickTicks = 0;
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

	/**
	 * Read-only world overlay used to ask vanilla whether a completed block
	 * survives after one owned support is removed. This is more precise than
	 * retaining scaffolding beside every slab, stair, fence, or pane.
	 */
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
		private final Map<BlockPos, Boolean> anchorCache = new HashMap<>();
		private int standProofBudget = ANCHOR_STAND_PROOFS_PER_PLAN;

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
			BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
			boolean solidFace = false;
			for (Direction direction : Direction.values()) {
				BlockPos neighbor = pos.offset(direction);
				BlockState state = client.world.getBlockState(neighbor);
				if (!state.isReplaceable() && !state.getCollisionShape(client.world, neighbor).isEmpty()) {
					solidFace = true;
					break;
				}
			}
			if (!solidFace) return false;

			// A face high on an inaccessible wall is not a useful anchor in
			// survival, so require a local reach-valid body position too. That
			// proof costs raycasts and the planner asks per expanded cell, so it
			// is cached and capped. Past the cap the cheap answer stands: the full
			// A* still proves the stand before anything is placed, so an
			// optimistic anchor costs a retry, never a wrong action.
			Boolean cached = anchorCache.get(pos);
			if (cached != null) return cached;
			if (standProofBudget <= 0) return true;
			standProofBudget--;
			boolean reachable = !placementStands(client, pos,
					client.player.getAbilities().flying, 1, 48).isEmpty();
			anchorCache.put(pos, reachable);
			return reachable;
		}
	}

	// ── Navigation ─────────────────────────────────────────────────────────────

	private void continueTransit(MinecraftClient client) {
		releaseAuto();
		if (transitWaitTicks-- > 0) return;

		DesiredBlock retryTarget = target;
		WorkKind retryKind = workKind;
		NavigationPlan continuation = navigationPlan(client, retryTarget);
		if (continuation != null) {
			beginWork(retryTarget, retryKind, continuation);
			return;
		}
		if (flightActivationPhase > 0) return;

		if (retryKind == WorkKind.TEMP_PLACE) {
			retryAfter.put(retryTarget.pos(), client.player.age + TEMPORARY_CELL_RETRY_TICKS);
			deferTemporarySupport(client);
		} else {
			retryAfter.put(retryTarget.pos(), client.player.age + TARGET_RETRY_TICKS);
			if (sameDesiredBlock(temporarySupportGoal, retryTarget)) temporarySupportGoal = null;
		}
		clearTarget();
	}

	private NavigationPlan navigationPlan(MinecraftClient client, DesiredBlock desired) {
		ClientPlayerEntity player = client.player;
		boolean flight = player.getAbilities().flying;
		SchematicPathfinder.Node start = feetNode(player);
		WorldSpace space = new WorldSpace(client, player);
		List<SchematicPathfinder.Node> stands = placementStands(client, desired.pos(), flight);
		Set<SchematicPathfinder.Node> rejected = failedStands.getOrDefault(desired.pos(), Set.of());
		stands.removeIf(rejected::contains);
		stands.sort(Comparator.comparingDouble(node -> node.squaredDistanceTo(start)));
		List<SchematicPathfinder.Node> candidate = flight
				? SchematicPathfinder.flightPathToAny(start, stands, space, MAX_PATH_NODES)
				: SchematicPathfinder.groundPathToAny(start, stands, space, MAX_PATH_NODES);
		if (!candidate.isEmpty()) {
			// Walking up a build this tall means scaffolding every stand. When the
			// server already grants flight, take it now rather than after the
			// ground route has failed and burned a retry window on every cell.
			if (!flight && desired.pos().getY() - start.y() >= 3 && player.getAbilities().allowFlying
					&& player.age >= flightAttemptCooldownUntil) {
				flightActivationPhase = 1;
				trace("entering creative flight for " + describe(desired.pos()) + " (rising layers)");
				status(client, "Rising layers; entering creative flight", Formatting.AQUA, 0);
				return null;
			}
			return new NavigationPlan(candidate, true);
		}

		// A placement may sit beyond the current loaded-chunk edge or the
		// per-search horizon. Follow a bounded route toward its theoretical
		// stands, pause for chunks to load, then run the exact visibility/path
		// proof again. Repeating these segments removes the old global radius.
		List<SchematicPathfinder.Node> distantGoals = theoreticalPlacementStands(desired.pos());
		distantGoals.removeIf(rejected::contains);
		List<SchematicPathfinder.Node> progress = flight
				? SchematicPathfinder.flightPathTowardAny(start, distantGoals, space,
						MAX_PATH_NODES / 2, 128.0D)
				: SchematicPathfinder.groundPathTowardAny(start, distantGoals, space,
						MAX_PATH_NODES / 2, 96.0D);
		if (progress.size() > 1) return new NavigationPlan(progress, false);

		// Creative flight is entered through the same double-jump input a player
		// uses. It is attempted only when ordinary ground navigation cannot reach
		// any legal stand; abilities/velocity are never written directly.
		if (!flight && player.getAbilities().allowFlying && player.age >= flightAttemptCooldownUntil) {
			flightActivationPhase = 1;
			trace("entering creative flight for " + describe(desired.pos()) + " (ground route blocked)");
			status(client, "Ground route blocked; entering creative flight", Formatting.AQUA, 0);
		}
		return null;
	}

	private List<SchematicPathfinder.Node> theoreticalPlacementStands(BlockPos target) {
		List<SchematicPathfinder.Node> out = new ArrayList<>();
		for (int dy = STAND_MIN_DY; dy <= STAND_MAX_DY; dy++) {
			int y = target.getY() + dy;
			for (int dz = -STAND_RADIUS; dz <= STAND_RADIUS; dz++) {
				for (int dx = -STAND_RADIUS; dx <= STAND_RADIUS; dx++) {
					if (dx == 0 && dz == 0 && dy < 1) continue;
					if (Math.abs(dx) + Math.abs(dz) > STAND_RADIUS + 2) continue;
					out.add(new SchematicPathfinder.Node(target.getX() + dx, y, target.getZ() + dz));
				}
			}
		}
		return out;
	}

	private void driveCreativeFlightActivation(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player.getAbilities().flying) {
			flightActivationPhase = 0;
			releaseAuto();
			status(client, "Creative flight ready", Formatting.GREEN, 0);
			return;
		}
		if (!player.getAbilities().allowFlying || flightActivationPhase > 12) {
			flightActivationPhase = 0;
			flightAttemptCooldownUntil = player.age + 100;
			releaseAuto();
			return;
		}

		boolean jumpPulse = flightActivationPhase == 1 || flightActivationPhase == 3;
		controlling = true;
		ownsRotation = false;
		aimGoal = null;
		movementInput = new PlayerInput(false, false, false, false, jumpPulse, false, false);
		flightActivationPhase++;
	}

	private List<SchematicPathfinder.Node> placementStands(MinecraftClient client, BlockPos target, boolean flight) {
		return placementStands(client, target, flight, STAND_ACCEPT_LIMIT, STAND_EXAMINE_LIMIT);
	}

	/**
	 * Body positions that can legally place {@code target}, best first.
	 *
	 * <p>Candidates are ranked before the expensive clearance and line-of-sight
	 * proofs so the cheap limits keep the strongest options: a stand directly
	 * over the cell wins outright, because a straight-down click at the support
	 * face below cannot be occluded by the rest of the layer, and that is the
	 * one position a wide interior always has available while the layer above is
	 * still empty. Stands overlooking the cell come next, then the nearest ones.
	 */
	private List<SchematicPathfinder.Node> placementStands(MinecraftClient client, BlockPos target,
			boolean flight, int acceptLimit, int examineLimit) {
		SchematicPathfinder.Node feet = feetNode(client.player);
		double eyeHeight = client.player.getStandingEyeHeight();
		Vec3d center = Vec3d.ofCenter(target);
		List<StandCandidate> candidates = new ArrayList<>();

		for (int dy = STAND_MIN_DY; dy <= STAND_MAX_DY; dy++) {
			int y = target.getY() + dy;
			for (int dz = -STAND_RADIUS; dz <= STAND_RADIUS; dz++) {
				for (int dx = -STAND_RADIUS; dx <= STAND_RADIUS; dx++) {
					// The target's own column only works from clear of the cell.
					boolean overhead = dx == 0 && dz == 0;
					if (overhead && dy < 1) continue;
					int x = target.getX() + dx;
					int z = target.getZ() + dz;
					Vec3d eye = new Vec3d(x + 0.5D, y + eyeHeight, z + 0.5D);
					if (eye.squaredDistanceTo(center) > STAND_REACH * STAND_REACH) continue;
					SchematicPathfinder.Node node = new SchematicPathfinder.Node(x, y, z);
					double cost = Math.sqrt(node.squaredDistanceTo(feet));
					if (overhead) cost -= 3.0D;
					else if (dy >= 1) cost -= 1.0D;
					candidates.add(new StandCandidate(node, eye, cost));
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(StandCandidate::cost));

		List<SchematicPathfinder.Node> out = new ArrayList<>();
		WorldSpace space = new WorldSpace(client, client.player);
		int examined = 0;
		for (StandCandidate candidate : candidates) {
			if (examined++ >= examineLimit) break;
			SchematicPathfinder.Node node = candidate.node();
			boolean valid = flight ? space.passable(node.x(), node.y(), node.z())
					: space.standable(node.x(), node.y(), node.z());
			if (!valid || !standCanSeeWork(client, candidate.eye(), target)) continue;
			out.add(node);
			if (out.size() >= acceptLimit) break;
		}
		return out;
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

	private void drivePath(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (pathIndex >= path.size()) return;
		Vec3d position = player.getEntityPos();
		Vec3d previous = lastDrivePosition == null ? position : lastDrivePosition;
		lastDrivePosition = position;
		boolean flying = player.getAbilities().flying;

		// Swept arrival: sprint-flight covers more than a whole cell per tick,
		// so requiring a sampled position inside the arrival circle would skip
		// straight over a waypoint and read as "never arrived" while the body
		// sails past the build. Testing the tick's whole travel segment (and
		// advancing through every waypoint it passed) makes speed harmless.
		int advanced = 0;
		while (pathIndex < path.size() && advanced < 3
				&& reachedWaypoint(previous, position, path.get(pathIndex),
						flying, pathIndex == path.size() - 1)) {
			pathIndex++;
			advanced++;
			stuckTicks = 0;
			unstickTicks = 0;
			lastWaypointDistance = Double.POSITIVE_INFINITY;
		}
		if (pathIndex >= path.size()) {
			releaseMovementOnly();
			return;
		}
		SchematicPathfinder.Node waypoint = path.get(pathIndex);
		Vec3d point = new Vec3d(waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D);
		double horizontalSq = horizontalDistanceSq(position, point);
		double vertical = point.y - player.getY();

		double distance = Math.sqrt(horizontalSq + vertical * vertical);
		if (distance + 0.025D < lastWaypointDistance) {
			lastWaypointDistance = distance;
			stuckTicks = 0;
		} else if (++stuckTicks == UNSTICK_TRIGGER_TICKS) {
			// A lip, a fence post, or a corner the path did not model. Try to
			// shake free before throwing away a route that is otherwise good.
			unstickTicks = UNSTICK_BURST_TICKS;
			unstickStrafe = random.nextBoolean() ? 1 : -1;
		} else if (stuckTicks > 48) {
			if (!replanTarget(client, false)) abandonTarget(client);
			return;
		}

		Vec3d steering = pathLookaheadPoint(player, point);
		Vec3d travelLook = new Vec3d(steering.x,
				player.getEyeY() + MathHelper.clamp(steering.y - player.getY(), -0.75D, 0.75D), steering.z);
		aimGoal = travelLook;
		aimSpeed = 1.65F;
		ownsRotation = true;
		controlling = true;

		double desiredYaw = Math.toDegrees(Math.atan2(point.z - player.getZ(), point.x - player.getX())) - 90.0D;
		double yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		double absoluteYaw = Math.abs(yawError);
		boolean forward = absoluteYaw < 67.5D;
		boolean backward = absoluteYaw > 112.5D;
		boolean left = yawError < -22.5D && yawError > -157.5D;
		boolean right = yawError > 22.5D && yawError < 157.5D;
		boolean jump = flying ? vertical > 0.30D : vertical > 0.65D && player.isOnGround();
		boolean sneak = flying && vertical < -0.30D;
		// Full pace, always: sprint on the ground and sprint-fly in the air. A
		// builder that ambles between stands is slower than the player it
		// replaces, which defeats the point of moving for them. Vanilla still
		// arbitrates the flag (hunger, sneaking, water), so publishing it every
		// tick can never produce an illegal state. The one exception is the end
		// of a flight: a sprint-flying body coasts about six blocks after input
		// stops, so sprint is released early and the last stretch is flown at
		// normal speed, with an active counter-input to kill the residue.
		double remaining = remainingPathDistance(position);
		boolean sprint = forward && !backward && !player.isTouchingWater()
				&& (!flying || remaining > 7.0D);
		if (flying && remaining < 2.2D && player.getVelocity().horizontalLength() > 0.35D) {
			aimGoal = travelLook;
			ownsRotation = true;
			controlling = true;
			movementInput = brakeInput(player);
			return;
		}

		if (unstickTicks > 0) {
			// Hold the heading, add a hop and a sidestep, and drop sprint so the
			// body can round whatever the route walked into.
			unstickTicks--;
			movementInput = new PlayerInput(forward, backward, unstickStrafe < 0, unstickStrafe > 0,
					jump || !flying, sneak, false);
			return;
		}
		movementInput = new PlayerInput(forward, backward, left, right, jump, sneak, sprint);
	}

	/**
	 * Re-routes the current target. The attempt counter survives
	 * {@link #beginWork} on purpose: a body that cannot physically move would
	 * otherwise re-plan the identical route every stuck window forever, because
	 * the planner is deterministic for an unmoved player. Returns false only
	 * when the target should be given up on.
	 */
	private boolean replanTarget(MinecraftClient client, boolean mustRelocate) {
		if (target == null) return false;
		// A pending flight entry is progress of its own. Wait for it rather than
		// spending an attempt, or the target's retry window, on it.
		if (flightActivationPhase > 0) return true;
		if (replanAttempts >= MAX_REPLANS_PER_TARGET) return false;
		replanAttempts++;
		NavigationPlan plan = navigationPlan(client, target);
		if (plan == null) return flightActivationPhase > 0;
		// A single-node plan means the body already stands on a legal stand. That
		// is a fine answer for a route that got stuck, and a useless one when the
		// body is the thing blocking the cell.
		if (mustRelocate && plan.path().size() <= 1) return false;
		beginWork(target, workKind, plan);
		return true;
	}

	/** Gives up on the current target, blaming the stand it could not work from. */
	private void abandonTarget(MinecraftClient client) {
		if (target != null) {
			trace("abandon " + workKind + " " + describe(target.pos())
					+ " stuck=" + stuckTicks + " phase=" + phaseTicks);
		}
		if (target != null) {
			if (workKind == WorkKind.TEMP_PLACE) {
				retryAfter.put(target.pos(), client.player.age + TEMPORARY_CELL_RETRY_TICKS);
				deferTemporarySupport(client);
			} else {
				if (workKind == WorkKind.PILLAR) {
					// Blame the stand the pillar was meant to create, so the next
					// scaffold attempt picks a different column.
					failedStands.computeIfAbsent(target.pos(), ignored -> new HashSet<>())
							.add(new SchematicPathfinder.Node(pillarColumnX, pillarTopY, pillarColumnZ));
				} else if (!path.isEmpty()) {
					failedStands.computeIfAbsent(target.pos(), ignored -> new HashSet<>()).add(path.getLast());
				}
				retryAfter.put(target.pos(), client.player.age + TARGET_RETRY_TICKS);
				if (sameDesiredBlock(temporarySupportGoal, target)) temporarySupportGoal = null;
			}
		}
		clearTarget();
		releaseAuto();
	}

	/**
	 * Publishes ordinary movement toward one point without touching the path
	 * cursor. Used to nudge a body back onto its stand, where the correction is
	 * smaller than any waypoint tolerance.
	 */
	private void driveToPoint(ClientPlayerEntity player, Vec3d point) {
		double vertical = point.y - player.getY();
		aimGoal = new Vec3d(point.x, player.getEyeY(), point.z);
		aimSpeed = 1.30F;
		ownsRotation = true;
		controlling = true;

		double desiredYaw = Math.toDegrees(Math.atan2(point.z - player.getZ(), point.x - player.getX())) - 90.0D;
		double yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		double absoluteYaw = Math.abs(yawError);
		boolean flying = player.getAbilities().flying;
		movementInput = new PlayerInput(absoluteYaw < 67.5D, absoluteYaw > 112.5D,
				yawError < -22.5D && yawError > -157.5D, yawError > 22.5D && yawError < 157.5D,
				flying ? vertical > 0.30D : vertical > 0.65D && player.isOnGround(),
				flying && vertical < -0.30D, false);
	}

	/** The player-sized volume a body occupies while standing on {@code node}. */
	private Box bodyBox(SchematicPathfinder.Node node) {
		return new Box(node.x() + 0.20D, node.y(), node.z() + 0.20D,
				node.x() + 0.80D, node.y() + 1.80D, node.z() + 0.80D);
	}

	private Vec3d pathLookaheadPoint(ClientPlayerEntity player, Vec3d fallback) {
		double budget = player.getAbilities().flying ? 6.0D : 2.8D;
		Vec3d previous = player.getEntityPos();
		Vec3d lookahead = fallback;
		for (int i = pathIndex; i < path.size(); i++) {
			SchematicPathfinder.Node node = path.get(i);
			Vec3d point = new Vec3d(node.x() + 0.5D, node.y(), node.z() + 0.5D);
			double segment = previous.distanceTo(point);
			if (segment > budget) {
				double amount = budget / Math.max(1.0E-6D, segment);
				return previous.lerp(point, amount);
			}
			lookahead = point;
			budget -= segment;
			previous = point;
			if (budget <= 0.0D) break;
		}
		return lookahead;
	}

	private double remainingPathDistance(Vec3d playerPosition) {
		double distance = 0.0D;
		Vec3d previous = playerPosition;
		for (int i = pathIndex; i < path.size(); i++) {
			SchematicPathfinder.Node node = path.get(i);
			Vec3d next = new Vec3d(node.x() + 0.5D, node.y(), node.z() + 0.5D);
			distance += previous.distanceTo(next);
			previous = next;
		}
		return distance;
	}

	/**
	 * Arrival test over the tick's whole travel segment. Intermediate flying
	 * waypoints accept a wider pass — the lookahead steering rounds them off —
	 * while the final node keeps the tight band the placement phase needs.
	 */
	private boolean reachedWaypoint(Vec3d previous, Vec3d position,
			SchematicPathfinder.Node waypoint, boolean flying, boolean finalNode) {
		Vec3d point = new Vec3d(waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D);
		Vec3d closest = closestOnSegment(previous, position, point);
		double horizontalTolerance = flying && !finalNode ? 0.5625D : 0.20D;
		double verticalTolerance = flying ? (finalNode ? 0.38D : 0.80D) : 0.60D;
		return horizontalDistanceSq(closest, point) < horizontalTolerance
				&& Math.abs(point.y - closest.y) < verticalTolerance;
	}

	private Vec3d closestOnSegment(Vec3d start, Vec3d end, Vec3d target) {
		Vec3d segment = end.subtract(start);
		double lengthSq = segment.lengthSquared();
		if (lengthSq < 1.0E-9D) return end;
		double t = MathHelper.clamp(target.subtract(start).dotProduct(segment) / lengthSq, 0.0D, 1.0D);
		return start.add(segment.multiply(t));
	}

	/**
	 * Key combination opposing the current horizontal velocity, the way a
	 * player taps the reverse key to kill flight momentum before a stop.
	 */
	private PlayerInput brakeInput(ClientPlayerEntity player) {
		Vec3d velocity = player.getVelocity();
		Vec3d desired = new Vec3d(-velocity.x, 0.0D, -velocity.z);
		if (desired.lengthSquared() < 1.0E-6D) return PlayerInput.DEFAULT;
		desired = desired.normalize();
		double yaw = Math.toRadians(player.getYaw());
		Vec3d forwardVec = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw));
		Vec3d leftVec = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw));
		double forwardDot = desired.dotProduct(forwardVec);
		double leftDot = desired.dotProduct(leftVec);
		return new PlayerInput(forwardDot > 0.2D, forwardDot < -0.2D,
				leftDot > 0.2D, leftDot < -0.2D, false, false, false);
	}

	// ── Aim, orientation prediction, placement, waterlogging ──────────────────

	private void alignAndPlace(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		releaseMovementOnly();
		controlling = true;
		phaseTicks++;

		Box cell = new Box(target.pos()).shrink(0.05D, 0.05D, 0.05D);
		if (player.getBoundingBox().intersects(cell)) {
			// The body overlaps the cell it has to fill, so vanilla will refuse
			// the placement. Drifting off a legal stand is by far the common
			// cause, so recentre on it first and only hunt for a different stand
			// when this one cannot clear the cell at all.
			SchematicPathfinder.Node stand = path.isEmpty() ? feetNode(player) : path.getLast();
			// Recentring cannot go through drivePath: the drift that overlaps the
			// cell is smaller than its arrival tolerance, so it would report the
			// stand reached and publish no input at all, forever.
			if (phaseTicks < 40 && !bodyBox(stand).intersects(cell)) {
				driveToPoint(player, new Vec3d(stand.x() + 0.5D, stand.y(), stand.z() + 0.5D));
			} else if (!replanTarget(client, true)) {
				abandonTarget(client);
			}
			return;
		}

			if (player.getAbilities().flying) {
			SchematicPathfinder.Node stand = path.isEmpty() ? feetNode(player) : path.getLast();
			Vec3d center = new Vec3d(stand.x() + 0.5D, stand.y(), stand.z() + 0.5D);
			double horizontal = (center.x - player.getX()) * (center.x - player.getX())
					+ (center.z - player.getZ()) * (center.z - player.getZ());
			// This band has to be no tighter than drivePath's arrival test, or a
			// hover that has settled between the two reads as "off the stand" to
			// one and "already arrived" to the other, and nothing ever moves.
			if (horizontal > 0.20D || Math.abs(center.y - player.getY()) > 0.38D) {
				pathIndex = Math.max(0, path.size() - 1);
				drivePath(client);
					return;
				}
			} else if (!player.isOnGround()) {
				// Mid-air between stands: pre-aim at the work and place on
				// landing. Sliding momentum on the ground is no reason to wait —
				// the live raycast below is the real gate, and a body drifting
				// into the cell is caught by the overlap check above.
				aimGoal = placementAim != null ? placementAim.aimPoint() : target.center();
				aimSpeed = 1.60F;
				ownsRotation = true;
				return;
			}

		if (placementAim == null || phaseTicks % 12 == 0) {
			placementAim = findPlacementAim(client, target);
			settleTicks = 0;
			if (placementAim == null) {
				// Placing while moving means the body can slide a little past
				// its stand; step back onto it before concluding the stand is
				// blind. The threshold is far below the waypoint tolerance, so
				// only a real drift triggers it.
				SchematicPathfinder.Node stand = path.isEmpty() ? feetNode(player) : path.getLast();
				Vec3d standCentre = new Vec3d(stand.x() + 0.5D, stand.y(), stand.z() + 0.5D);
				if (phaseTicks <= 28 && !player.getAbilities().flying
						&& horizontalDistanceSq(player.getEntityPos(), standCentre) > 0.015D) {
					driveToPoint(player, standCentre);
					return;
				}
				if (phaseTicks > 28) {
					if (workKind == WorkKind.TEMP_PLACE) {
						retryAfter.put(target.pos(), player.age + TEMPORARY_CELL_RETRY_TICKS);
						deferTemporarySupport(client);
						clearTarget();
						releaseAuto();
						return;
					}
					if (workKind == WorkKind.SCHEMATIC && config.schematicTemporaryBlocks
							&& client.world.getBlockState(target.pos()).isReplaceable()
							&& enqueueTemporarySupport(client, target)) {
						clearTarget();
						return;
					}
					if (!path.isEmpty()) {
						failedStands.computeIfAbsent(target.pos(), ignored -> new HashSet<>()).add(path.getLast());
					}
					retryAfter.put(target.pos(), player.age + TARGET_RETRY_TICKS);
					clearTarget();
					releaseAuto();
				}
				return;
			}
		}

		aimGoal = placementAim.aimPoint();
		aimSpeed = 1.85F;
		ownsRotation = true;
		BlockHitResult liveHit = livePlacementHit(client, placementAim);
		if (liveHit == null) {
			settleTicks = 0;
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

	private void alignAndBreakTemporary(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		releaseMovementOnly();
		controlling = true;
		phaseTicks++;
		if (player.getBoundingBox().intersects(new Box(target.pos()).shrink(0.05D, 0.05D, 0.05D))) {
			NavigationPlan escape = navigationPlan(client, target);
			if (escape != null && escape.path().size() > 1) {
				beginWork(target, WorkKind.TEMP_REMOVE, escape);
			}
			return;
		}

		if (placementAim == null || (!breakingTemporary && phaseTicks % 8 == 0)) {
			placementAim = findBreakAim(client, target.pos());
			settleTicks = 0;
			if (placementAim == null) {
				if (phaseTicks > 40) {
					retryAfter.put(target.pos(), player.age + 20);
					clearTarget();
					releaseAuto();
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
					return new PlacementAim(point, actual, tool, PlacementKind.BREAK);
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
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(support, clickedSide, sample);
				BlockHitResult actual = raycastTemporarily(client.player, point);
				if (actual == null || !actual.getBlockPos().equals(support) || actual.getSide() != clickedSide) continue;
				HoverTarget predicted = placementForHit(client, client.player, actual, desired.pos(), desired.state());
				if (predicted != null) {
					return new PlacementAim(point, predicted.hit(), predicted.hotbarSlot(), PlacementKind.BLOCK);
				}
			}
		}
		return null;
	}

	private PlacementAim findDirectPlacementAim(MinecraftClient client, DesiredBlock desired) {
		for (Direction side : Direction.values()) {
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(desired.pos(), side, sample);
				BlockHitResult actual = raycastTemporarily(client.player, point);
				if (actual == null || !actual.getBlockPos().equals(desired.pos()) || actual.getSide() != side) continue;
				HoverTarget predicted = placementForHit(client, client.player, actual, desired.pos(), desired.state());
				if (predicted != null) {
					return new PlacementAim(point, predicted.hit(), predicted.hotbarSlot(), PlacementKind.BLOCK);
				}
			}
		}
		return null;
	}

	private PlacementAim findDirectUseAim(MinecraftClient client, BlockPos target, int slot, PlacementKind kind) {
		for (Direction side : Direction.values()) {
			for (int sample = 0; sample < 5; sample++) {
				Vec3d point = facePoint(target, side, sample);
				BlockHitResult actual = raycastTemporarily(client.player, point);
				if (actual != null && actual.getBlockPos().equals(target)) {
					return new PlacementAim(point, actual, slot, kind);
				}
			}
		}
		return null;
	}

	private BlockHitResult livePlacementHit(MinecraftClient client, PlacementAim planned) {
		HitResult raw = client.player.raycast(MAX_REACH, 1.0F, false);
		if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
		if (!hit.getBlockPos().equals(planned.hit().getBlockPos()) || hit.getSide() != planned.hit().getSide()) return null;
		if (planned.kind() != PlacementKind.BLOCK) return hit;
		return placementForHit(client, client.player, hit, target.pos(), target.state()) != null ? hit : null;
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

	private HoverTarget placementForHit(MinecraftClient client, ClientPlayerEntity player, BlockHitResult hit,
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
		return new HoverTarget(placeAt, copy, slot, desired);
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

		// A full hotbar can still lease a material created in an empty main
		// inventory slot; SWAP preserves the displaced selected-slot stack.
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

	/**
	 * Uses the vanilla player-handler SWAP action. With a full hotbar, the current
	 * slot is leased: its stack moves into the source inventory slot, so nothing
	 * is destroyed and another material can lease the same slot later.
	 */
	private int moveInventoryItemToHotbar(MinecraftClient client, int inventorySlot) {
		int hotbar = findEmptyHotbarSlot(client.player);
		if (hotbar < 0) hotbar = client.player.getInventory().getSelectedSlot();
		client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId,
				inventorySlot, hotbar, SlotActionType.SWAP, client.player);
		return hotbar;
	}

	// ── Original manual hover mode ─────────────────────────────────────────────

	private void tickManualHover(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (client.options.attackKey.isPressed() || client.options.useKey.isPressed() || player.isUsingItem()) {
			resetHover();
			return;
		}

		long now = System.nanoTime();
		HoverTarget target = targetUnderCrosshair(client, player, now);
		if (target == null) {
			resetHover();
			return;
		}
		if (!sameTarget(target, hoverTarget)) {
			hoverTarget = target;
			hoverReadyNanos = now;
		}
		hoverTarget = target;

		long minimumInterval = lastPlaceNanos + (isMoving(player) ? jitterMs(32, 48) : jitterMs(55, 95));
		if (now < hoverReadyNanos || now < minimumInterval) return;
		if (player.getInventory().getSelectedSlot() != target.hotbarSlot()) {
			player.getInventory().setSelectedSlot(target.hotbarSlot());
		}

		HoverTarget live = targetUnderCrosshair(client, player, now);
		if (!sameTarget(target, live)) {
			resetHover();
			return;
		}

		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, live.hit());
		if (!result.isAccepted()) {
			hoverReadyNanos = now + jitterMs(25, 65);
			return;
		}
		player.swingHand(Hand.MAIN_HAND);
		lastPlaceNanos = now;
		recentPosition = live.placeAt();
		recentPositionUntil = now + RECENT_POSITION_NS;
		resetHover();
	}

	private HoverTarget targetUnderCrosshair(MinecraftClient client, ClientPlayerEntity player, long now) {
		HitResult raw = player.raycast(MAX_REACH, 1.0F, false);
		if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
		if (player.getEyePos().squaredDistanceTo(hit.getPos()) > MAX_REACH_SQUARED) return null;
		BlockState supportState = client.world.getBlockState(hit.getBlockPos());
		BlockPos placeAt = (supportState.isReplaceable() ? hit.getBlockPos()
				: hit.getBlockPos().offset(hit.getSide())).toImmutable();
		if (recentPosition != null && recentPosition.equals(placeAt) && now < recentPositionUntil) return null;
		BlockState desired = remember.desiredStateAt(placeAt);
		if (desired == null || desired.isAir()) desired = litematica.stateAt(placeAt);
		if (desired == null || desired.isAir()) return null;
		return placementForHit(client, player, hit, placeAt, desired);
	}

	// ── State/property/geometry helpers ────────────────────────────────────────

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

	private SchematicPathfinder.Node feetNode(ClientPlayerEntity player) {
		return new SchematicPathfinder.Node(MathHelper.floor(player.getX()),
				MathHelper.floor(player.getBoundingBox().minY + 0.50D), MathHelper.floor(player.getZ()));
	}

	private double horizontalDistanceSq(Vec3d a, Vec3d b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return dx * dx + dz * dz;
	}

	private boolean manualInput(MinecraftClient client) {
		return client.options.forwardKey.isPressed() || client.options.backKey.isPressed()
				|| client.options.leftKey.isPressed() || client.options.rightKey.isPressed()
				|| client.options.jumpKey.isPressed() || client.options.sneakKey.isPressed()
				|| client.options.attackKey.isPressed() || client.options.useKey.isPressed();
	}

	private boolean ready(MinecraftClient client) {
		return config.enabled && config.schematicBuildEnabled && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.player.isAlive() && !client.player.isSpectator() && !client.player.hasVehicle()
				&& !client.player.isGliding();
	}

	private boolean isMoving(ClientPlayerEntity player) {
		return player.getVelocity().horizontalLengthSquared() > 0.0016D;
	}

	private long jitterMs(int minimum, int maximum) {
		return (minimum + random.nextInt(maximum - minimum + 1)) * 1_000_000L;
	}

	private boolean sameTarget(HoverTarget first, HoverTarget second) {
		return first != null && second != null
				&& first.placeAt().equals(second.placeAt())
				&& first.hit().getBlockPos().equals(second.hit().getBlockPos())
				&& first.hit().getSide() == second.hit().getSide()
				&& first.hotbarSlot() == second.hotbarSlot()
				&& first.desired().equals(second.desired());
	}

	private boolean sameDesiredBlock(DesiredBlock first, DesiredBlock second) {
		return first != null && second != null && first.pos().equals(second.pos())
				&& first.state().getBlock() == second.state().getBlock();
	}

	/**
	 * Decision trace written to latest.log. Consecutive duplicates collapse, so
	 * the log records transitions, not ticks; the point is that a failed field
	 * test can be reconstructed from the log after the fact.
	 */
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

	private void releaseMovementOnly() {
		movementInput = PlayerInput.DEFAULT;
	}

	private void releaseAuto() {
		controlling = false;
		ownsRotation = false;
		movementInput = PlayerInput.DEFAULT;
		aimGoal = null;
	}

	private void clearTarget() {
		target = null;
		workKind = WorkKind.SCHEMATIC;
		placementAim = null;
		path = List.of();
		pathIndex = 0;
		navigationComplete = true;
		transitWaitTicks = 0;
		phaseTicks = 0;
		settleTicks = 0;
		confirmationTicks = 0;
		stuckTicks = 0;
		unstickTicks = 0;
		replanAttempts = 0;
		lastWaypointDistance = Double.POSITIVE_INFINITY;
		lastDrivePosition = null;
		breakingTemporary = false;
		breakSwingTicks = 0;
		placementSent = false;
		pillarStallTicks = 0;
		lastPillarFeetY = Integer.MIN_VALUE;
	}

	private void resetHover() {
		hoverTarget = null;
		hoverReadyNanos = 0L;
	}

	private void resetAll() {
		resetHover();
		releaseAuto();
		clearTarget();
		temporaryQueue.clear();
		temporarySupportGoal = null;
		manualPauseTicks = 0;
		flightActivationPhase = 0;
		flightAttemptCooldownUntil = 0;
		lastFrameNanos = 0L;
		// These are absolute player-age deadlines, and a respawn restarts the age
		// at zero. Leaving them would freeze the affected cells and the status
		// line for the whole of the old age.
		retryAfter.clear();
		failedStands.clear();
		nextMaintenanceTick = 0;
		nextCleanupAttemptTick = 0;
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

	// ── World pathing view ─────────────────────────────────────────────────────

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

	private enum WorkKind { SCHEMATIC, TEMP_PLACE, TEMP_REMOVE, PILLAR }
	private enum PlacementKind { BLOCK, WATERLOG, UNWATERLOG, TOGGLE, BREAK }
	private record DesiredBlock(BlockPos pos, BlockState state) {
		Vec3d center() { return Vec3d.ofCenter(pos); }
	}
	private record NavigationPlan(List<SchematicPathfinder.Node> path, boolean complete) {}
	private record StandCandidate(SchematicPathfinder.Node node, Vec3d eye, double cost) {}
	private record PlacementAim(Vec3d aimPoint, BlockHitResult hit, int hotbarSlot, PlacementKind kind) {}
	private record HoverTarget(BlockPos placeAt, BlockHitResult hit, int hotbarSlot, BlockState desired) {}
	private record SourceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int sourceIdentity) {}

	/** Optional integration: absent or changing Litematica simply yields no source. */
	private static final class LitematicaBridge {
		private Method worldGetter;
		private Method placementManagerGetter;
		private Method getAllPlacements;
		private Method placementEnabled;
		private Method placementBox;
		private Method boxPos1;
		private Method boxPos2;
		private boolean initialized;

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
			if (!initialize() || placementManagerGetter == null) return List.of();
			try {
				Object manager = placementManagerGetter.invoke(null);
				Object raw = getAllPlacements.invoke(manager);
				if (!(raw instanceof Collection<?> placements)) return List.of();
				List<SourceBounds> out = new ArrayList<>();
				for (Object placement : placements) {
					if (!Boolean.TRUE.equals(placementEnabled.invoke(placement))) continue;
					Object box = placementBox.invoke(placement);
					if (box == null) continue;
					Object first = boxPos1.invoke(box);
					Object second = boxPos2.invoke(box);
					if (!(first instanceof BlockPos a) || !(second instanceof BlockPos b)) continue;
					out.add(new SourceBounds(
							Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
							Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()),
							System.identityHashCode(placement)));
				}
				return List.copyOf(out);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				return List.of();
			}
		}

		private boolean initialize() {
			if (initialized) return worldGetter != null;
			initialized = true;
			if (!FabricLoader.getInstance().isModLoaded("litematica")) return false;
			try {
				Class<?> handler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
				worldGetter = handler.getMethod("getSchematicWorld");
				Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");
				placementManagerGetter = dataManager.getMethod("getSchematicPlacementManager");
				Class<?> manager = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
				getAllPlacements = manager.getMethod("getAllSchematicsPlacements");
				Class<?> placement = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
				placementEnabled = placement.getMethod("isEnabled");
				placementBox = placement.getMethod("getEclosingBox");
				Class<?> selectionBox = Class.forName("fi.dy.masa.litematica.selection.Box");
				boxPos1 = selectionBox.getMethod("getPos1");
				boxPos2 = selectionBox.getMethod("getPos2");
			} catch (ReflectiveOperationException | LinkageError ignored) {
				worldGetter = null;
			}
			return worldGetter != null;
		}
	}
}
