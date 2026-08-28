package com.profps.client.extras;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.HumanizedAim;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.mixin.BlockItemInvoker;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Autonomous builder: walks the build and places it layer by layer from the bottom up,
 * reading a Remember capture or an enabled Litematica placement.
 *
 * <p>Movement is ordinary published {@link PlayerInput}, applied by {@code InputMixin} on the
 * real input path; the view is steered by the shared mouse-grid humanized aim. Standable cells
 * include the structure itself, which is what makes tall builds work: the lower courses become
 * the floor for the upper ones, the way a player walks a wall top and lays the next row ahead.
 * Layers sweep interior-first and whole-build sweeps repeat until one places nothing, so a cell
 * skipped for a blocked route or a missing material is retried rather than fought over.
 *
 * <p>Placement keeps the manual builder's discipline: actions run in the pre-movement phase
 * under the shared action claim, a slot change is a real packet a tick before the use, every
 * click is verified against vanilla placement prediction on the live crosshair ray, and the
 * pace never outruns a held right click. Any manual movement or click pauses the module.
 */
public final class AutoMoveController {
	private static final double MAX_REACH = 4.5D;
	/** Stand-to-cell eye distance the target search accepts; margin under the reach. */
	private static final double PLACE_RANGE = 3.9D;
	private static final double PLACE_RANGE_SQUARED = PLACE_RANGE * PLACE_RANGE;
	/** Vanilla holds 4 ticks between uses; the floor sits just above it. */
	private static final int PLACE_GAP_MIN_MS = 205;
	private static final int PLACE_GAP_MAX_MS = 285;
	private static final long RECENT_POSITION_NS = 900_000_000L;
	/** Ticks the aim may chase a face before the cell is set aside for this sweep. */
	private static final int AIM_TIMEOUT_TICKS = 45;
	/** Transit watchdog: this little progress over this many ticks is stuck. */
	private static final int STALL_WINDOW_TICKS = 16;
	private static final double STALL_MIN_PROGRESS = 0.55D;
	private static final int MAX_REPLANS_PER_TARGET = 3;
	/** Ticks a set-aside cell waits before the sweep may try it again. */
	private static final int RETRY_TICKS = 400;
	private static final int MANUAL_PAUSE_TICKS = 10;
	private static final int MAX_PATH_NODES = 20_000;
	private static final int SCAN_CELLS_PER_TICK = 4_096;
	private static final int MAX_PENDING_PER_LAYER = 65_536;
	private static final int STAND_SEARCH_RADIUS = 4;
	private static final int STAND_CANDIDATE_LIMIT = 24;

	/** State properties a placement must reproduce for the cell to count as built. */
	private static final Set<String> PLACEMENT_PROPERTIES = Set.of(
			"facing", "horizontal_facing", "axis", "rotation", "half", "type", "shape",
			"hinge", "attachment", "face", "orientation", "layers", "candles", "pickles",
			"eggs", "open", "mode", "delay", "note", "inverted");
	private static final Set<String> STACKING_PROPERTIES = Set.of("layers", "candles", "pickles", "eggs");
	private static final Set<String> INTERACTION_PROPERTIES = Set.of("open", "mode", "delay", "note", "inverted");

	private static AutoMoveController instance;

	private final ProFPSConfig config;
	private final RememberController remember;
	private final Random random = new Random();
	private final HumanizedAim aim = new HumanizedAim();
	private final LitematicaBridge litematica = new LitematicaBridge();

	// Published to InputMixin and the render loop.
	private volatile boolean controlling;
	private volatile boolean ownsRotation;
	private volatile PlayerInput movementInput = PlayerInput.DEFAULT;
	private volatile Vec3d aimGoal;
	private volatile float aimSpeed = 1.0F;
	private long lastFrameNanos;

	// Sources.
	private ClientWorld sourceWorld;
	private long knownRememberRevision = Long.MIN_VALUE;
	private long knownLitematicaSignature = Long.MIN_VALUE;
	private Map<BlockPos, BlockState> remembered = Map.of();
	private List<SourceBounds> schematicBounds = List.of();
	private int sourceCheckTick;

	// Layer sweep.
	private int minimumLayer;
	private int maximumLayer;
	private int activeLayer = Integer.MIN_VALUE;
	private final LinkedHashMap<BlockPos, BlockState> pending = new LinkedHashMap<>();
	private final Set<Long> layerFootprint = new HashSet<>();
	private Map<Long, Integer> layerDepth = Map.of();
	private List<BlockPos> orderedPending = List.of();
	private boolean layerScanComplete;
	private int scanBoundsIndex;
	private int scanX;
	private int scanZ;
	private boolean scanBoundsInitialized;
	private int rememberedScanIndex;
	private List<Map.Entry<BlockPos, BlockState>> rememberedEntries = List.of();
	private boolean buildFinished;
	private int passPlacements;
	private int passSetAside;

	// Current target.
	private BlockPos targetPos;
	private BlockState targetState;
	private List<SchematicPathfinder.Node> path = List.of();
	private int pathIndex;
	private int aimTicks;
	private int replanAttempts;
	private int faceSample;
	private int stallTicks;
	private Vec3d stallAnchor;
	private final Map<BlockPos, Integer> retryAfter = new HashMap<>();

	// Action pacing.
	private long nextPlaceNanos;
	private long pullSettleNanos;
	private int slotReadyAge;
	private BlockPos recentPosition;
	private long recentPositionUntil;
	private int manualPauseTicks;
	private int nextStatusTick;
	private boolean wasReady;

	public AutoMoveController(ProFPSConfig config, RememberController remember) {
		this.config = config;
		this.remember = remember;
		instance = this;
	}

	/** True while this controller owns the ordinary movement input path. */
	public static boolean isControlling() {
		return instance != null && instance.controlling;
	}

	/** Input applied by {@code InputMixin} after the keyboard has been sampled. */
	public static PlayerInput movementInput() {
		return instance == null ? PlayerInput.DEFAULT : instance.movementInput;
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	/** Render-frame steering, smooth at the display rate rather than 20 Hz. */
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

	/** Runs in the pre-movement dispatch so every use packet precedes the tick's movement. */
	public void tickPreMovement(MinecraftClient client) {
		if (!ready(client)) {
			resetAll();
			wasReady = false;
			return;
		}
		ClientPlayerEntity player = client.player;

		// The player's own hands always win; a touched key pauses the module briefly.
		if (manualInput(client)) {
			manualPauseTicks = MANUAL_PAUSE_TICKS;
			release();
			return;
		}
		if (manualPauseTicks > 0) {
			manualPauseTicks--;
			release();
			return;
		}
		if (FreecamController.isActive() || TunnelController.isControlling()) {
			release();
			return;
		}

		syncSources(client);
		if (remembered.isEmpty() && schematicBounds.isEmpty()) {
			release();
			status(client, "No Remember capture or enabled Litematica placement", Formatting.GRAY, 80);
			return;
		}
		// Re-toggling the module is how a finished or stopped build is asked to try again.
		if (!wasReady) {
			wasReady = true;
			resetSweep();
		}
		if (buildFinished) {
			release();
			return;
		}

		int tick = player.age;
		retryAfter.entrySet().removeIf(entry -> tick >= entry.getValue());

		if (!layerScanComplete) {
			scanLayer(client);
			release();
			return;
		}

		if (targetPos != null && desiredComplete(client.world, targetPos, targetState)) {
			pending.remove(targetPos);
			passPlacements++;
			clearTarget();
		}
		if (targetPos == null) {
			int choice = chooseTarget(client);
			if (choice == CHOICE_RETRY) {
				release();
				return;
			}
			if (choice == CHOICE_LAYER_DONE) {
				advanceSweep(client);
				release();
				return;
			}
		}

		if (withinPlaceRange(player, targetPos)) {
			drivePlacement(client, player);
		} else {
			driveTransit(client, player);
		}
	}

	// ── Sources ────────────────────────────────────────────────────────────────

	private void syncSources(MinecraftClient client) {
		if (sourceWorld != client.world) {
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
		resetSweep();
	}

	private void resetPlanning() {
		knownRememberRevision = Long.MIN_VALUE;
		knownLitematicaSignature = Long.MIN_VALUE;
		sourceCheckTick = 0;
		remembered = Map.of();
		rememberedEntries = List.of();
		schematicBounds = List.of();
		resetSweep();
	}

	/** Starts a bottom-to-top sweep over every layer the sources cover. */
	private void resetSweep() {
		buildFinished = false;
		retryAfter.clear();
		passPlacements = 0;
		passSetAside = 0;
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
		activeLayer = minimumLayer == Integer.MAX_VALUE ? Integer.MIN_VALUE : minimumLayer;
		startLayerScan();
		clearTarget();
	}

	private void startLayerScan() {
		pending.clear();
		layerFootprint.clear();
		layerDepth = Map.of();
		orderedPending = List.of();
		layerScanComplete = false;
		scanBoundsIndex = 0;
		scanX = 0;
		scanZ = 0;
		scanBoundsInitialized = false;
		rememberedScanIndex = 0;
	}

	/**
	 * Collects the active layer's cells a budget at a time: pending cells still to place, and
	 * the full footprint that the interior-first ordering ranks against.
	 */
	private void scanLayer(MinecraftClient client) {
		if (activeLayer == Integer.MIN_VALUE) {
			layerScanComplete = true;
			orderedPending = List.of();
			return;
		}
		int budget = SCAN_CELLS_PER_TICK;

		while (rememberedScanIndex < rememberedEntries.size() && budget > 0) {
			Map.Entry<BlockPos, BlockState> entry = rememberedEntries.get(rememberedScanIndex++);
			budget--;
			considerCell(client, entry.getKey(), entry.getValue());
		}
		if (rememberedScanIndex < rememberedEntries.size()) return;

		while (scanBoundsIndex < schematicBounds.size() && budget > 0) {
			SourceBounds bounds = schematicBounds.get(scanBoundsIndex);
			if (activeLayer < bounds.minY() || activeLayer > bounds.maxY()) {
				scanBoundsIndex++;
				scanBoundsInitialized = false;
				continue;
			}
			if (!scanBoundsInitialized) {
				scanX = bounds.minX();
				scanZ = bounds.minZ();
				scanBoundsInitialized = true;
			}
			while (budget > 0) {
				BlockPos pos = new BlockPos(scanX, activeLayer, scanZ);
				BlockState desired = litematica.stateAt(pos);
				budget--;
				if (desired != null && !desired.isAir()) considerCell(client, pos, desired);
				if (++scanX > bounds.maxX()) {
					scanX = bounds.minX();
					if (++scanZ > bounds.maxZ()) {
						scanBoundsIndex++;
						scanBoundsInitialized = false;
						break;
					}
				}
			}
		}
		if (scanBoundsIndex < schematicBounds.size()) return;

		layerDepth = SchematicLayerOrder.depths(layerFootprint);
		List<BlockPos> ordered = new ArrayList<>(pending.keySet());
		// Deepest interior first, so a wide or thick layer never seals a shell around the
		// player and strands the middle.
		ordered.sort((a, b) -> {
			int depthA = layerDepth.getOrDefault(SchematicLayerOrder.key(a.getX(), a.getZ()), 1);
			int depthB = layerDepth.getOrDefault(SchematicLayerOrder.key(b.getX(), b.getZ()), 1);
			if (depthA != depthB) return Integer.compare(depthB, depthA);
			int x = Integer.compare(a.getX(), b.getX());
			return x != 0 ? x : Integer.compare(a.getZ(), b.getZ());
		});
		orderedPending = ordered;
		layerScanComplete = true;
	}

	private void considerCell(MinecraftClient client, BlockPos pos, BlockState desired) {
		if (pos.getY() != activeLayer || desired.isAir()) return;
		if (!desired.getFluidState().isEmpty() && desired.getBlock() == Blocks.WATER) return;
		layerFootprint.add(SchematicLayerOrder.key(pos.getX(), pos.getZ()));
		if (pending.size() >= MAX_PENDING_PER_LAYER) return;
		if (desiredComplete(client.world, pos, desired)) return;
		BlockState current = client.world.getBlockState(pos);
		// Never mines: a wrong block already in the cell is left alone and reported.
		if (!current.isReplaceable()) return;
		pending.put(pos.toImmutable(), desired);
	}

	/** Layer done or empty: move up a layer, or wrap the sweep at the top. */
	private void advanceSweep(MinecraftClient client) {
		if (activeLayer == Integer.MIN_VALUE) {
			buildFinished = true;
			return;
		}
		if (activeLayer < maximumLayer) {
			activeLayer++;
			startLayerScan();
			return;
		}
		// Sweep finished. Placing nothing across a whole sweep is the stop condition.
		if (passPlacements == 0) {
			buildFinished = true;
			int remaining = Math.max(pending.size(), passSetAside);
			status(client, remaining == 0 ? "Build complete"
					: "Build stopped: " + remaining + " cell(s) unreachable, blocked, or missing materials",
					remaining == 0 ? Formatting.GREEN : Formatting.YELLOW, 200);
			return;
		}
		passPlacements = 0;
		passSetAside = 0;
		// A fresh sweep gives every set-aside cell a fresh chance.
		retryAfter.clear();
		activeLayer = minimumLayer;
		startLayerScan();
	}

	// ── Target selection ───────────────────────────────────────────────────────

	private static final int CHOICE_SET = 0;
	private static final int CHOICE_RETRY = 1;
	private static final int CHOICE_LAYER_DONE = 2;
	/** Path plans allowed per tick; A* is the expensive step and must not spike a frame. */
	private static final int PLANS_PER_TICK = 2;

	/**
	 * Picks the next cell: any in-reach pending cell first so one stand keeps placing, then
	 * the deepest-interior cell an approach can be planned to.
	 */
	private int chooseTarget(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (orderedPending.isEmpty() && pending.isEmpty()) return CHOICE_LAYER_DONE;

		// Drop entries satisfied or invalidated since the scan.
		List<BlockPos> live = new ArrayList<>(orderedPending.size());
		for (BlockPos pos : orderedPending) {
			BlockState desired = pending.get(pos);
			if (desired == null) continue;
			if (desiredComplete(client.world, pos, desired)) {
				pending.remove(pos);
				continue;
			}
			if (!client.world.getBlockState(pos).isReplaceable()) {
				pending.remove(pos);
				passSetAside++;
				continue;
			}
			live.add(pos);
		}
		orderedPending = live;
		if (live.isEmpty()) return CHOICE_LAYER_DONE;

		int tick = player.age;
		int plansThisTick = 0;
		for (BlockPos pos : live) {
			if (retryAfter.containsKey(pos)) continue;
			BlockState desired = pending.get(pos);
			if (!materialObtainable(client, desired)) {
				setAside(pos, tick);
				continue;
			}
			if (supportFace(client, pos) == null) continue;

			if (withinPlaceRange(player, pos)) {
				beginTarget(player, pos);
				return CHOICE_SET;
			}
			if (plansThisTick >= PLANS_PER_TICK) return CHOICE_RETRY;
			plansThisTick++;
			beginTarget(player, pos);
			if (planPath(client, pos)) return CHOICE_SET;
			setAside(pos, tick);
			clearTarget();
		}
		// Everything left is set aside, unsupported, or blocked; the next sweep retries it.
		return CHOICE_LAYER_DONE;
	}

	private void beginTarget(ClientPlayerEntity player, BlockPos pos) {
		targetPos = pos;
		targetState = pending.get(pos);
		faceSample = random.nextInt(5);
		aimTicks = 0;
		replanAttempts = 0;
		stallTicks = 0;
		stallAnchor = player.getEntityPos();
		path = List.of();
		pathIndex = 0;
	}

	private void setAside(BlockPos pos, int tick) {
		retryAfter.put(pos.toImmutable(), tick + RETRY_TICKS);
		passSetAside++;
	}

	/** Walkable nodes whose eye position reaches the cell; the structure itself counts. */
	private List<SchematicPathfinder.Node> standCandidates(MinecraftClient client, BlockPos cell) {
		WorldSpace space = new WorldSpace(client, client.player);
		Vec3d center = Vec3d.ofCenter(cell);
		List<SchematicPathfinder.Node> out = new ArrayList<>();
		for (int dy = -2; dy <= STAND_SEARCH_RADIUS && out.size() < STAND_CANDIDATE_LIMIT; dy++) {
			for (int dx = -STAND_SEARCH_RADIUS; dx <= STAND_SEARCH_RADIUS && out.size() < STAND_CANDIDATE_LIMIT; dx++) {
				for (int dz = -STAND_SEARCH_RADIUS; dz <= STAND_SEARCH_RADIUS && out.size() < STAND_CANDIDATE_LIMIT; dz++) {
					int x = cell.getX() + dx;
					int y = cell.getY() + dy;
					int z = cell.getZ() + dz;
					if (dx == 0 && dz == 0 && y == cell.getY()) continue;
					if (!space.standable(x, y, z)) continue;
					Vec3d eye = new Vec3d(x + 0.5D, y + 1.62D, z + 0.5D);
					if (eye.squaredDistanceTo(center) > PLACE_RANGE_SQUARED) continue;
					out.add(new SchematicPathfinder.Node(x, y, z));
				}
			}
		}
		return out;
	}

	private boolean planPath(MinecraftClient client, BlockPos cell) {
		List<SchematicPathfinder.Node> stands = standCandidates(client, cell);
		if (stands.isEmpty()) return false;
		WorldSpace space = new WorldSpace(client, client.player);
		SchematicPathfinder.Node feet = feetNode(client.player);
		List<SchematicPathfinder.Node> found =
				SchematicPathfinder.groundPathToAny(feet, stands, space, MAX_PATH_NODES);
		if (found.isEmpty()) {
			// Partial progress still helps: closing distance turns unprovable stands into
			// provable ones on the replan.
			found = SchematicPathfinder.groundPathTowardAny(feet, stands, space, MAX_PATH_NODES, 8.0D);
		}
		if (found.isEmpty()) return false;
		path = found;
		pathIndex = 0;
		return true;
	}

	// ── Transit ────────────────────────────────────────────────────────────────

	private void driveTransit(MinecraftClient client, ClientPlayerEntity player) {
		if (pathIndex >= path.size()) {
			if (!replanOrSetAside(client, player)) return;
		}
		if (targetPos == null || pathIndex >= path.size()) {
			release();
			return;
		}
		SchematicPathfinder.Node node = path.get(pathIndex);
		Vec3d nodeCenter = new Vec3d(node.x() + 0.5D, node.y(), node.z() + 0.5D);
		double horizontal = horizontalDistanceSq(player.getEntityPos(), nodeCenter);
		int feetY = MathHelper.floor(player.getBoundingBox().minY + 0.5D);
		if (horizontal < 0.12D && Math.abs(feetY - node.y()) <= 0) {
			pathIndex++;
			stallTicks = 0;
			stallAnchor = player.getEntityPos();
			return;
		}

		// The stall watchdog replans rather than letting a body wedge grind forever.
		if (++stallTicks >= STALL_WINDOW_TICKS) {
			stallTicks = 0;
			double progress = stallAnchor == null ? 1.0D
					: player.getEntityPos().distanceTo(stallAnchor);
			stallAnchor = player.getEntityPos();
			if (progress < STALL_MIN_PROGRESS && !replanOrSetAside(client, player)) return;
		}

		// Look mostly where the walk is going, a couple of nodes ahead at eye height.
		SchematicPathfinder.Node lookNode = path.get(Math.min(pathIndex + 2, path.size() - 1));
		aimGoal = new Vec3d(lookNode.x() + 0.5D, lookNode.y() + 1.45D, lookNode.z() + 0.5D);
		aimSpeed = 0.8F;
		ownsRotation = true;

		boolean jump = node.y() > feetY;
		boolean sprint = pathIndex + 4 < path.size() && flatAhead(feetY, 3) && !jump;
		movementInput = keysToward(player, nodeCenter, jump, sprint);
		controlling = true;
	}

	private boolean flatAhead(int feetY, int nodes) {
		for (int i = pathIndex; i < Math.min(pathIndex + nodes, path.size()); i++) {
			if (path.get(i).y() != feetY) return false;
		}
		return true;
	}

	private boolean replanOrSetAside(MinecraftClient client, ClientPlayerEntity player) {
		if (targetPos == null) return false;
		if (withinPlaceRange(player, targetPos)) return true;
		if (++replanAttempts <= MAX_REPLANS_PER_TARGET && planPath(client, targetPos)) return true;
		setAside(targetPos, player.age);
		clearTarget();
		release();
		return false;
	}

	/** Real key combination toward a world direction, resolved against the current yaw. */
	private PlayerInput keysToward(ClientPlayerEntity player, Vec3d destination, boolean jump, boolean sprint) {
		Vec3d delta = destination.subtract(player.getEntityPos());
		Vec3d desired = new Vec3d(delta.x, 0.0D, delta.z);
		if (desired.lengthSquared() < 1.0E-6D) {
			return new PlayerInput(false, false, false, false, jump, false, false);
		}
		desired = desired.normalize();
		double yawRadians = Math.toRadians(player.getYaw());
		Vec3d forward = new Vec3d(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
		Vec3d left = new Vec3d(Math.cos(yawRadians), 0.0D, Math.sin(yawRadians));
		double bestDot = -Double.MAX_VALUE;
		int bestForward = 0;
		int bestLeft = 0;
		for (int f = -1; f <= 1; f++) {
			for (int l = -1; l <= 1; l++) {
				if (f == 0 && l == 0) continue;
				Vec3d candidate = forward.multiply(f).add(left.multiply(l)).normalize();
				double dot = candidate.dotProduct(desired);
				if (dot > bestDot) {
					bestDot = dot;
					bestForward = f;
					bestLeft = l;
				}
			}
		}
		return new PlayerInput(bestForward > 0, bestForward < 0, bestLeft > 0, bestLeft < 0,
				jump, false, sprint && bestForward > 0);
	}

	// ── Placement ──────────────────────────────────────────────────────────────

	private void drivePlacement(MinecraftClient client, ClientPlayerEntity player) {
		movementInput = PlayerInput.DEFAULT;
		controlling = true;

		FaceChoice face = supportFace(client, targetPos);
		if (face == null) {
			setAside(targetPos, player.age);
			clearTarget();
			release();
			return;
		}
		aimGoal = face.point();
		aimSpeed = 1.0F;
		ownsRotation = true;

		if (++aimTicks > AIM_TIMEOUT_TICKS) {
			// The face never lined up, usually the player's own body or the structure in
			// the ray. Another sample or another sweep gets it later.
			setAside(targetPos, player.age);
			clearTarget();
			release();
			return;
		}

		long now = System.nanoTime();
		if (now < nextPlaceNanos || now < pullSettleNanos) return;

		// Material on the hotbar, as a claimed action with a settle.
		int slot = findBlockSlot(player, targetState);
		if (slot < 0) {
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MOVE)) return;
			if (!pullMaterial(client, targetState)) {
				setAside(targetPos, player.age);
				clearTarget();
				release();
				return;
			}
			pullSettleNanos = now + jitterMs(180, 320);
			return;
		}

		// A real slot change a tick before the click.
		if (player.getInventory().getSelectedSlot() != slot) {
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MOVE)) return;
			selectSlot(client, player, slot);
			slotReadyAge = player.age + 1;
			return;
		}
		if (player.age < slotReadyAge) return;

		// The live crosshair ray is the authority: it has to name the support face, and the
		// click has to predict into the exact desired cell and state.
		HitResult raw = player.raycast(MAX_REACH, 1.0F, false);
		if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		if (!hit.getBlockPos().equals(face.support()) || hit.getSide() != face.side()) return;
		if (recentPosition != null && recentPosition.equals(targetPos) && now < recentPositionUntil) return;

		ItemStack stack = player.getInventory().getStack(slot);
		if (!(stack.getItem() instanceof BlockItem blockItem)
				|| blockItem.getBlock() != targetState.getBlock()) return;
		ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
		if (!context.getBlockPos().equals(targetPos) || !context.canPlace()) return;
		BlockState predicted = ((BlockItemInvoker) blockItem).profps$getPlacementState(context);
		BlockState current = client.world.getBlockState(targetPos);
		if (predicted == null || !placementMatches(targetState, predicted, current)
				|| !((BlockItemInvoker) blockItem).profps$canPlace(context, predicted)) {
			// Wrong resulting rotation from this stand; try another face sample first, then
			// another stand on a later sweep.
			faceSample = (faceSample + 1) % 5;
			return;
		}

		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MOVE)) return;
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		if (!result.isAccepted()) {
			nextPlaceNanos = now + jitterMs(120, 260);
			return;
		}
		player.swingHand(Hand.MAIN_HAND);
		long gap = jitterMs(PLACE_GAP_MIN_MS, PLACE_GAP_MAX_MS);
		if (random.nextInt(100) < 7) gap += jitterMs(140, 420);
		nextPlaceNanos = now + gap;
		recentPosition = targetPos.toImmutable();
		recentPositionUntil = now + RECENT_POSITION_NS;
		pending.remove(targetPos);
		passPlacements++;
		clearTarget();
	}

	private boolean withinPlaceRange(ClientPlayerEntity player, BlockPos cell) {
		return cell != null
				&& player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cell)) <= PLACE_RANGE_SQUARED;
	}

	/** The solid neighbor face the click goes against, jittered off dead centre. */
	private FaceChoice supportFace(MinecraftClient client, BlockPos cell) {
		ClientPlayerEntity player = client.player;
		Vec3d eye = player.getEyePos();
		FaceChoice best = null;
		double bestDot = -Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos support = cell.offset(direction);
			BlockState state = client.world.getBlockState(support);
			if (state.isReplaceable() || state.getCollisionShape(client.world, support).isEmpty()) continue;
			Direction side = direction.getOpposite();
			Vec3d point = facePoint(support, side, faceSample);
			if (point.squaredDistanceTo(eye) > MAX_REACH * MAX_REACH) continue;
			Vec3d toFace = point.subtract(eye);
			if (toFace.lengthSquared() < 1.0E-6D) continue;
			// The face has to face the eye, or the ray lands on the far side.
			if (toFace.normalize().dotProduct(Vec3d.of(side.getVector())) >= 0.0D) continue;
			double alignment = -toFace.normalize().dotProduct(Vec3d.of(side.getVector()));
			if (alignment > bestDot) {
				bestDot = alignment;
				best = new FaceChoice(support.toImmutable(), side, point);
			}
		}
		return best;
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

	// ── Materials ──────────────────────────────────────────────────────────────

	private int findBlockSlot(ClientPlayerEntity player, BlockState desired) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
					&& blockItem.getBlock() == desired.getBlock()) return slot;
		}
		return -1;
	}

	/** Read-only availability check used while ranking; it never mutates the inventory. */
	private boolean materialObtainable(MinecraftClient client, BlockState desired) {
		if (desired == null) return false;
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR) return false;
		if (findBlockSlot(client.player, desired) >= 0) return true;
		if (findInventoryItemSlot(client.player, item) >= 0) return true;
		if (!client.player.isCreative()) return false;
		return findEmptyHotbarSlot(client.player) >= 0 || findEmptyInventorySlot(client.player) >= 0;
	}

	/** Moves the material onto the hotbar, from the main inventory or a creative lease. */
	private boolean pullMaterial(MinecraftClient client, BlockState desired) {
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR) return false;
		int inventorySlot = findInventoryItemSlot(client.player, item);
		if (inventorySlot >= 0) return moveInventoryItemToHotbar(client, inventorySlot) >= 0;
		if (!client.player.isCreative()) return false;

		int emptyHotbar = findEmptyHotbarSlot(client.player);
		if (emptyHotbar >= 0) {
			ItemStack stack = new ItemStack(item, 64);
			client.interactionManager.clickCreativeStack(stack, 36 + emptyHotbar);
			client.player.getInventory().setStack(emptyHotbar, stack);
			return true;
		}
		int emptyInventory = findEmptyInventorySlot(client.player);
		if (emptyInventory >= 0) {
			ItemStack stack = new ItemStack(item, 64);
			client.interactionManager.clickCreativeStack(stack, emptyInventory);
			client.player.getInventory().setStack(emptyInventory, stack);
			return moveInventoryItemToHotbar(client, emptyInventory) >= 0;
		}
		return false;
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

	/** Vanilla player-handler SWAP; with a full hotbar the current slot is leased, not lost. */
	private int moveInventoryItemToHotbar(MinecraftClient client, int inventorySlot) {
		int hotbar = findEmptyHotbarSlot(client.player);
		if (hotbar < 0) hotbar = client.player.getInventory().getSelectedSlot();
		client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId,
				inventorySlot, hotbar, SlotActionType.SWAP, client.player);
		return hotbar;
	}

	/** Sets the slot locally and sends the packet, keeping vanilla's slot sync in step. */
	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	// ── State property matching ────────────────────────────────────────────────

	private boolean desiredComplete(ClientWorld world, BlockPos pos, BlockState desired) {
		BlockState current = world.getBlockState(pos);
		if (current.getBlock() != desired.getBlock()) return false;
		for (Property<?> property : desired.getProperties()) {
			if (PLACEMENT_PROPERTIES.contains(property.getName())
					&& !sameProperty(desired, current, property)) return false;
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

	// ── Plumbing ───────────────────────────────────────────────────────────────

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
		return config.enabled && config.autoMoveEnabled && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.player.isAlive() && !client.player.isSpectator() && !client.player.hasVehicle()
				&& !client.player.isGliding() && !client.player.isTouchingWater();
	}

	private long jitterMs(int minimum, int maximum) {
		return (minimum + random.nextInt(maximum - minimum + 1)) * 1_000_000L;
	}

	private void status(MinecraftClient client, String message, Formatting color, int cooldown) {
		if (client.player.age < nextStatusTick) return;
		client.inGameHud.setOverlayMessage(Text.literal("Auto Move Builder ").formatted(Formatting.AQUA, Formatting.BOLD)
				.append(Text.literal("• " + message).formatted(color)), false);
		nextStatusTick = client.player.age + cooldown;
	}

	private void release() {
		controlling = false;
		ownsRotation = false;
		movementInput = PlayerInput.DEFAULT;
		aimGoal = null;
	}

	private void clearTarget() {
		targetPos = null;
		targetState = null;
		path = List.of();
		pathIndex = 0;
		aimTicks = 0;
		replanAttempts = 0;
		stallTicks = 0;
		stallAnchor = null;
	}

	private void resetAll() {
		release();
		clearTarget();
		manualPauseTicks = 0;
		lastFrameNanos = 0L;
		// Absolute player-age deadlines: a respawn restarts the age at zero.
		retryAfter.clear();
		nextStatusTick = 0;
		slotReadyAge = 0;
	}

	private long boundsSignature(List<SourceBounds> bounds) {
		long signature = 0xcbf29ce484222325L;
		for (SourceBounds box : bounds) {
			signature = (signature ^ box.hashCode()) * 0x100000001b3L;
		}
		return signature;
	}

	private record FaceChoice(BlockPos support, Direction side, Vec3d point) {}
	private record SourceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int sourceIdentity) {}

	/** Player-sized walkability over the real world, so placed structure is a floor. */
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

	/** Optional integration: absent or changing Litematica simply yields no source. */
	private final class LitematicaBridge {
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
