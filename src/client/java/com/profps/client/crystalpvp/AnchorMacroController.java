package com.profps.client.crystalpvp;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.security.SecureRandom;

/** AutoAnchor: On Bind / On Place, Safe Anchor and item selection. */
public final class AnchorMacroController {
	private static final long SEQUENCE_TIMEOUT_NS = 4_000_000_000L;
	private static final long CONFIRM_GRACE_NS = 150_000_000L;
	/**
	 * Placement gets a longer grace than charge/detonate. Those two only need the client's own
	 * predicted block state to flip, but a placement can be rejected by the server after the client
	 * already drew it, and re-clicking too early is what stacks a second anchor.
	 */
	private static final long PLACE_GRACE_NS = 400_000_000L;
	/** Hard cap on macro placement attempts per sequence. Physical On Place clicks can start over. */
	private static final int MAX_PLACE_ATTEMPTS = 3;
	/** Bound on phase hand-offs collapsed into a single tick. */
	private static final int MAX_STEPS_PER_TICK = 4;
	/** Vanilla {@code RespawnAnchorBlock.MAX_CHARGES}; {@code canCharge} is {@code charges < 4}. */
	private static final int MAX_CHARGES = 4;
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private Phase phase = Phase.IDLE;
	private BlockPos supportPos;
	private Direction supportFace;
	private BlockPos anchorPos;
	private BlockPos shieldPos;
	private int previousSlot = -1;
	private long nextActionNanos;
	private long deadlineNanos;
	private long anchorConfirmStartedNanos;
	private long shieldConfirmStartedNanos;
	private long chargeConfirmStartedNanos;
	private long detonateConfirmStartedNanos;
	private boolean selfInteracting;
	private boolean bindStarted;
	private boolean shieldDone;
	private int placeAttempts;
	/** Client age of the last real or macro block-use; keep uses in separate flying intervals. */
	private int lastUseAge = Integer.MIN_VALUE;
	private String status = "Idle";

	public AnchorMacroController(ProFPSConfig config) {
		this.config = config;
	}

	/** On Place mode begins only after the player's real anchor placement click. */
	public ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player, World world,
			Hand hand, BlockHitResult hit) {
		if (!config.anchorMacro || !world.isClient() || selfInteracting) return ActionResult.PASS;
		if (player == null) return ActionResult.PASS;
		boolean holdingAnchor = player.getStackInHand(hand).isOf(Items.RESPAWN_ANCHOR);
		BlockState clicked = world.getBlockState(hit.getBlockPos());

		// A real placement click must always win in On Place mode. Previously every
		// click was returned as FAIL while an older sequence was still confirming,
		// charging, or waiting to time out. That produced the repeatable 3–4 second
		// period where right-click simply could not place another anchor. Re-arm from
		// this click and let vanilla send it, even if it replaces a stale sequence.
		if (config.anchorMode == 1 && holdingAnchor && !clicked.isOf(Blocks.RESPAWN_ANCHOR)) {
			beginPhysicalPlacement(player, world, hit);
			return ActionResult.PASS;
		}

		// Once a live anchor sequence owns charge/detonation, suppress additional
		// physical interactions. The explicit placement path above is the exception.
		if (phase != Phase.IDLE) return ActionResult.FAIL;
		if (!holdingAnchor) return ActionResult.PASS;
		if (config.anchorMode != 1) return ActionResult.PASS;

		previousSlot = player.getInventory().getSelectedSlot();
		if (clicked.isOf(Blocks.RESPAWN_ANCHOR)) {
			anchorPos = hit.getBlockPos().toImmutable();
			supportPos = null;
			supportFace = null;
			phase = charged(clicked) ? afterChargePhase() : nextChargePhase();
			armDeadline();
			return ActionResult.FAIL;
		}
		return ActionResult.PASS;
	}

	private void beginPhysicalPlacement(net.minecraft.entity.player.PlayerEntity player,
			World world, BlockHitResult hit) {
		previousSlot = player.getInventory().getSelectedSlot();
		supportPos = hit.getBlockPos().toImmutable();
		supportFace = hit.getSide();
		anchorPos = placementTarget(world, hit);
		phase = Phase.WAIT_ANCHOR;
		armDeadline();
		anchorConfirmStartedNanos = System.nanoTime();
		// This callback runs before vanilla sends the player's placement. Reserve the
		// rest of the tick so confirmation cannot append a second use behind it.
		lastUseAge = player.age;
		placeAttempts = 1;
		schedule();
	}

	public void tick(MinecraftClient client) {
		if (!config.anchorMacro) {
			bindStarted = false;
			if (phase != Phase.IDLE) finish(client, "Idle", false);
			return;
		}
		if (!allowed(client)) { finish(client, "Idle", false); return; }
		if (client.world.getRegistryKey().equals(World.NETHER)) {
			finish(client, "Anchors do not explode here", true);
			return;
		}

		if (config.anchorMode == 0 && phase == Phase.IDLE && !bindStarted) {
			bindStarted = true;
			startOnBind(client);
		}
		if (phase == Phase.IDLE) { status = config.anchorMode == 0 ? "Aim and press bind" : "Waiting for anchor"; return; }
		if (System.nanoTime() > deadlineNanos) { finish(client, "Timed out", true); return; }

		// Collapse state-only hand-offs, but never two physical uses: claim(client) also gates on
		// lastUseAge. This lets a confirmation become the next ready phase without producing an
		// impossible place/charge/detonate burst inside one client tick.
		for (int step = 0; step < MAX_STEPS_PER_TICK; step++) {
			long now = System.nanoTime();
			if (phase == Phase.IDLE || now < nextActionNanos) return;
			Phase before = phase;
			switch (phase) {
				case PLACE_ANCHOR -> placeAnchor(client);
				case WAIT_ANCHOR -> waitAnchor(client);
				case PLACE_SHIELD -> placeShield(client);
				case WAIT_SHIELD -> waitShield(client);
				case CHARGE -> charge(client);
				case WAIT_CHARGE -> waitCharge(client);
				case FINISH_CHARGED -> finishCharged(client);
				case DETONATE -> detonate(client);
				case WAIT_DETONATE -> waitDetonate(client);
				case RESTORE -> finish(client, "Idle", true);
				case IDLE -> { }
			}
			if (phase == before) return;   // same phase = it is waiting on something real
		}
	}

	private void startOnBind(MinecraftClient client) {
		HitResult fresh = freshHit(client);
		if (!(fresh instanceof BlockHitResult hit)) { finish(client, "Aim at a block", true); return; }
		releaseHeldUse(client);
		previousSlot = client.player.getInventory().getSelectedSlot();
		BlockState clicked = client.world.getBlockState(hit.getBlockPos());
		if (clicked.isOf(Blocks.RESPAWN_ANCHOR)) {
			anchorPos = hit.getBlockPos().toImmutable();
			phase = charged(clicked) ? afterChargePhase() : nextChargePhase();
		} else {
			supportPos = hit.getBlockPos().toImmutable();
			supportFace = hit.getSide();
			anchorPos = placementTarget(client.world, hit);
			phase = Phase.PLACE_ANCHOR;
		}
		armDeadline();
	}

	private void placeAnchor(MinecraftClient client) {
		if (anchorPos == null) { finish(client, "No anchor space", true); return; }
		// Support is resolved lazily and re-resolved every tick until the deadline. The second
		// anchor of a Double goes back into the cell the first one just vacated, and the blocks
		// around that cell are still settling as the explosion's updates arrive — a single lookup
		// the instant the anchor disappears usually finds nothing and used to abandon the combo.
		// Re-placing in the same cell against a real adjacent face is the whole point: it is an
		// ordinary legal placement, not a fabricated one.
		if (supportPos == null || supportFace == null) {
			Placement resolved = placementFor(client, anchorPos, false);
			if (resolved == null) {
				status = "Looking for support";
				nextActionNanos = System.nanoTime() + 10_000_000L;
				return;
			}
			supportPos = resolved.support();
			supportFace = resolved.face();
		}
		// An anchor already standing where we were going to build one means the previous click DID
		// land and we simply mis-predicted the cell. Adopt it rather than placing a second.
		BlockPos existing = findPlacedAnchor(client);
		if (existing != null) {
			anchorPos = existing;
			phase = Phase.WAIT_ANCHOR;
			anchorConfirmStartedNanos = System.nanoTime();
			nextActionNanos = anchorConfirmStartedNanos;
			return;
		}
		if (!client.world.getBlockState(anchorPos).isReplaceable()) {
			finish(client, "No anchor space", true); return;
		}
		if (placeAttempts >= MAX_PLACE_ATTEMPTS) {
			finish(client, "Anchor placement kept failing", true); return;
		}
		BlockHitResult hit = exactPlacementHit(client, anchorPos);
		if (hit == null) return;
		// The player may still be aiming into the same placement cell through a
		// different legal face than the one that began the sequence. Keep the live
		// face authoritative so a tiny crosshair movement cannot strand a retry.
		supportPos = hit.getBlockPos().toImmutable();
		supportFace = hit.getSide();
		int slot = findHotbarSlot(client, Items.RESPAWN_ANCHOR);
		if (slot < 0) { finish(client, "No respawn anchor", true); return; }
		if (!claim(client)) return;
		selectHotbarSlot(client, slot);
		if (!useBlock(client, hit)) { retry("Anchor refused"); return; }
		placeAttempts++;
		phase = Phase.WAIT_ANCHOR;
		anchorConfirmStartedNanos = System.nanoTime();
		schedule();
	}

	private void waitAnchor(MinecraftClient client) {
		// The click may have landed one cell from where we predicted. Adopting it here is what
		// stops the retry below from stacking a second anchor on the first.
		if (!anchorPresent(client)) {
			BlockPos placed = findPlacedAnchor(client);
			if (placed != null) anchorPos = placed;
		}
		if (anchorPresent(client)) {
			// Leave the anchor stack immediately after confirmation. Besides removing a
			// needless delayed swap, this closes the window in which held right-click can
			// stack another anchor before the charge phase begins.
			int glowstone = findHotbarSlot(client, Items.GLOWSTONE);
			if (glowstone < 0) { finish(client, "No glowstone", true); return; }
			selectHotbarSlot(client, glowstone);
			phase = nextChargePhase();
			nextActionNanos = System.nanoTime();
			status = phase.label;
			return;
		}
		status = "Confirming anchor";
		long now = System.nanoTime();
		if (anchorConfirmStartedNanos == 0L) anchorConfirmStartedNanos = now;
		if (now - anchorConfirmStartedNanos >= PLACE_GRACE_NS) {
			// Nothing appeared anywhere our click could have put it. Re-run the placement, but
			// placeAnchor re-checks for an existing anchor and caps the attempts.
			phase = Phase.PLACE_ANCHOR;
			nextActionNanos = now;
			status = "Retrying anchor";
			return;
		}
		nextActionNanos = now + 10_000_000L;
	}

	private Phase nextChargePhase() {
		return !shieldDone ? Phase.PLACE_SHIELD : Phase.CHARGE;
	}

	private Phase afterChargePhase() {
		return config.anchorDetonate ? Phase.DETONATE : Phase.FINISH_CHARGED;
	}

	private void placeShield(MinecraftClient client) {
		if (!anchorPresent(client)) { finish(client, "Anchor gone", true); return; }
		if (shieldPos == null) shieldPos = findShieldPosition(client);
		if (shieldPos == null) {
			// Safe Anchor is best-effort: with no legal cover, go straight on to
			// the charge in this tick rather than scheduling another one. The
			// scheduled hop is what put a visible pause in front of every blast
			// that could not be shielded.
			shieldDone = true;
			phase = Phase.CHARGE;
			charge(client);
			return;
		}
		Placement placement = placementFor(client, shieldPos, true);
		if (placement == null) {
			shieldDone = true;
			phase = Phase.CHARGE;
			charge(client);
			return;
		}
		BlockHitResult hit = exactPlacementHit(client, shieldPos);
		if (hit == null) {
			// Cover is strictly opportunistic now. Never rotate or replace the player's
			// crosshair to reach it; if their real crosshair is elsewhere, continue.
			shieldDone = true;
			phase = Phase.CHARGE;
			charge(client);
			return;
		}
		int slot = findHotbarSlot(client, Items.GLOWSTONE);
		if (slot < 0 || countHotbarItem(client, Items.GLOWSTONE) < 2) {
			// One glowstone can charge, but Safe Anchor needs a second one for cover.
			shieldDone = true;
			phase = Phase.CHARGE;
			schedule();
			return;
		}
		if (!claim(client)) return;
		selectHotbarSlot(client, slot);
		if (!useBlock(client, hit)) { retry("Shield refused"); return; }
		shieldConfirmStartedNanos = System.nanoTime();
		phase = Phase.WAIT_SHIELD;
		schedule();
	}

	private void waitShield(MinecraftClient client) {
		if (!anchorPresent(client)) { finish(client, "Anchor gone", true); return; }
		if (shieldPos != null && client.world.getBlockState(shieldPos).isOf(Blocks.GLOWSTONE)) {
			shieldDone = true;
			phase = Phase.CHARGE;
			nextActionNanos = System.nanoTime();
			status = "Charging anchor";
			return;
		}
		long now = System.nanoTime();
		if (shieldConfirmStartedNanos == 0L) shieldConfirmStartedNanos = now;
		if (now - shieldConfirmStartedNanos >= CONFIRM_GRACE_NS) {
			phase = Phase.PLACE_SHIELD;
			nextActionNanos = now;
			status = "Retrying shield";
			return;
		}
		status = "Confirming shield";
		nextActionNanos = now + 10_000_000L;
	}

	private void charge(MinecraftClient client) {
		if (!anchorPresent(client)) { finish(client, "Anchor gone", true); return; }
		if (!needsCharge(client)) {
			// Hand off through the state machine rather than calling detonate() inline: a
			// re-entrant call left the phase reading CHARGE while detonation was already running.
			phase = config.anchorDetonate ? Phase.DETONATE : Phase.FINISH_CHARGED;
			nextActionNanos = System.nanoTime();
			status = phase.label;
			return;
		}
		BlockHitResult hit = exactAnchorHit(client);
		if (hit == null) return;
		int slot = findHotbarSlot(client, Items.GLOWSTONE);
		boolean offhandGlowstone = client.player.getOffHandStack().isOf(Items.GLOWSTONE);
		if (slot < 0 && !offhandGlowstone) { finish(client, "No glowstone", true); return; }
		if (!claim(client)) return;
		// Hotbar glowstone can run out while topping the anchor up for the offhand rule. Charging
		// from the offhand then drains the very stack that was blocking detonation, so the sequence
		// resolves itself instead of dead-ending on "No glowstone".
		Hand chargeHand = slot >= 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
		if (chargeHand == Hand.MAIN_HAND) selectHotbarSlot(client, slot);
		if (!useBlock(client, hit)) { retry("Charge refused"); return; }
		// Charge and detonation use the same anchor face. Preserve the already-stable
		// crosshair state so confirmation can proceed on the next eligible tick.
		chargeConfirmStartedNanos = System.nanoTime();
		phase = Phase.WAIT_CHARGE;
		schedule();
	}

	private void waitCharge(MinecraftClient client) {
		if (!anchorPresent(client)) { finish(client, "Anchor gone", true); return; }
		if (!needsCharge(client)) {
			chargeConfirmStartedNanos = 0L;
			phase = config.anchorDetonate ? Phase.DETONATE : Phase.FINISH_CHARGED;
			nextActionNanos = System.nanoTime();
			status = phase.label;
			return;
		}
		// Still charging because the offhand rule demands a full anchor — say so, because from the
		// outside this looks identical to a stuck sequence.
		if (anchorCharged(client)) {
			chargeConfirmStartedNanos = 0L;
			phase = Phase.CHARGE;
			nextActionNanos = System.nanoTime();
			status = "Topping up (glowstone in offhand)";
			return;
		}
		long now = System.nanoTime();
		if (chargeConfirmStartedNanos == 0L) chargeConfirmStartedNanos = now;
		if (now - chargeConfirmStartedNanos >= CONFIRM_GRACE_NS) {
			phase = Phase.CHARGE;
			nextActionNanos = now;
			chargeConfirmStartedNanos = 0L;
			status = "Retrying charge";
			return;
		}
		status = "Confirming charge";
		nextActionNanos = now + 1_000_000L;
	}

	private void finishCharged(MinecraftClient client) {
		if (!anchorPresent(client)) { finish(client, "Anchor gone", true); return; }
		if (!anchorCharged(client)) { phase = Phase.CHARGE; schedule(); return; }
		int slot = finishItemSlot(client);
		if (slot >= 0) selectHotbarSlot(client, slot);
		finish(client, "Charged — detonation skipped", config.anchorMode == 0, false);
	}

	private void detonate(MinecraftClient client) {
		if (!anchorPresent(client)) {
			phase = Phase.WAIT_DETONATE;
			waitDetonate(client);
			return;
		}
		if (needsCharge(client)) { phase = Phase.CHARGE; schedule(); return; }
		float damage = ExplosionDamageService.anchorDamage(client.world, client.player, Vec3d.ofCenter(anchorPos));
		// Safe Anchor is cover, not a veto. It used to refuse a blast it judged lethal, which meant
		// standing too close to fit glowstone between you and the anchor silently cancelled the
		// whole macro. It now always detonates; the damage estimate only colours the status.
		if (damage >= client.player.getHealth() + client.player.getAbsorptionAmount() && !hasTotem(client)) {
			status = "Detonating uncovered";
		}
		BlockHitResult hit = exactAnchorHit(client);
		if (hit == null) return;
		int slot = explosionSlot(client);
		if (slot < 0 && !config.anchorExplosionItemWhitelist)
			slot = findHotbarSlot(client, Items.RESPAWN_ANCHOR);
		if (slot < 0) { finish(client, "No main-hand explosion item", true); return; }
		if (!claim(client)) return;
		selectHotbarSlot(client, slot);
		if (!useBlock(client, hit)) { retry("Detonation refused"); return; }
		detonateConfirmStartedNanos = System.nanoTime();
		phase = Phase.WAIT_DETONATE;
		nextActionNanos = detonateConfirmStartedNanos;
		status = "Confirming detonation";
	}

	private void waitDetonate(MinecraftClient client) {
		if (!anchorPresent(client)) {
			detonateConfirmStartedNanos = 0L;
			phase = Phase.RESTORE;
			schedule();
			return;
		}
		long now = System.nanoTime();
		if (detonateConfirmStartedNanos == 0L) detonateConfirmStartedNanos = now;
		if (now - detonateConfirmStartedNanos >= CONFIRM_GRACE_NS) {
			phase = Phase.DETONATE;
			nextActionNanos = now;
			detonateConfirmStartedNanos = 0L;
			status = "Retrying detonation";
			return;
		}
		status = "Confirming detonation";
		nextActionNanos = now + 1_000_000L;
	}

	private BlockHitResult exactAnchorHit(MinecraftClient client) {
		if (!anchorPresent(client)) return null;
		HitResult fresh = freshHit(client);
		if (!(fresh instanceof BlockHitResult hit) || !hit.getBlockPos().equals(anchorPos)
				|| !withinReach(client, hit.getPos())) {
			status = "Keep crosshair on anchor";
			return null;
		}
		return hit;
	}

	/**
	 * Returns the player's current, real block ray when clicking it would place in
	 * {@code placeAt}. Checking the resulting cell is intentionally more robust
	 * than pinning a support block and face from an earlier tick: several visible
	 * faces can legally lead to the same placement cell.
	 */
	private BlockHitResult exactPlacementHit(MinecraftClient client, BlockPos placeAt) {
		HitResult fresh = freshHit(client);
		if (!(fresh instanceof BlockHitResult hit)
				|| !placementTarget(client.world, hit).equals(placeAt)
				|| !withinReach(client, hit.getPos())) {
			status = "Keep crosshair on target";
			return null;
		}
		return hit;
	}

	/** Re-raycast at action time; the cached client target can be one render behind. */
	private HitResult freshHit(MinecraftClient client) {
		return client.player.getCrosshairTarget(1.0F,
				client.getCameraEntity() == null ? client.player : client.getCameraEntity());
	}

	/**
	 * A cell to put cover in, between the player and the anchor.
	 *
	 * <p>The old search gave up outright whenever the anchor was closer than 1.5
	 * blocks, and then swept a line starting 0.8 blocks out. In an actual anchor
	 * fight the anchor is placed right beside you, so the distance test alone
	 * rejected essentially every real use — Safe Anchor looked enabled and never
	 * placed a single glowstone. Cover at that range is not "somewhere along a
	 * long ray" either; it is the cell you would slap a block into, right next to
	 * your own body on the side the blast is coming from.
	 *
	 * <p>So the cells beside the player are tried first, at feet and head height,
	 * and only then the line out toward the anchor for the longer-range case.
	 */
	private BlockPos findShieldPosition(MinecraftClient client) {
		Vec3d from = new Vec3d(client.player.getX(), client.player.getY() + 0.5D, client.player.getZ());
		Vec3d to = Vec3d.ofCenter(anchorPos);
		Vec3d delta = to.subtract(from);
		double distance = delta.length();
		if (distance < 0.1D) return null;
		Vec3d direction = delta.normalize();
		java.util.Set<BlockPos> checked = new java.util.HashSet<>();

		// Point-blank cover: the cell adjacent to the body facing the blast, at
		// both the feet and the head, which is the only cover that exists when
		// the anchor is a block or two away.
		BlockPos feet = client.player.getBlockPos();
		Direction toward = Direction.getFacing(direction.x, 0.0D, direction.z);
		for (BlockPos pos : new BlockPos[]{feet.offset(toward), feet.offset(toward).up(),
				feet.up().offset(toward)}) {
			if (!checked.add(pos) || pos.equals(anchorPos)
					|| !client.world.getBlockState(pos).isReplaceable()) continue;
			if (new net.minecraft.util.math.Box(pos).intersects(client.player.getBoundingBox())) continue;
			if (placementFor(client, pos, true) != null) return pos.toImmutable();
		}

		for (double offset = 0.6D; offset < Math.max(0.9D, distance - 0.35D); offset += 0.25D) {
			BlockPos base = BlockPos.ofFloored(from.add(direction.multiply(offset)));
			// Try the direct blast line first, then nearby supported cells. Head-height
			// anchors often have no legal support at the exact sampled block even though
			// an adjacent glowstone position still provides useful cover.
			for (BlockPos pos : new BlockPos[]{base, base.down(), base.north(), base.south(),
					base.west(), base.east(), base.north().down(), base.south().down(),
					base.west().down(), base.east().down()}) {
				if (!checked.add(pos) || pos.equals(anchorPos)
						|| !client.world.getBlockState(pos).isReplaceable()) continue;
				if (new net.minecraft.util.math.Box(pos).intersects(client.player.getBoundingBox())) continue;
				// Deliberately only a reach/support test. The real crosshair is
				// checked only after a candidate is chosen; the search itself never
				// changes or substitutes the player's view.
				if (placementFor(client, pos, true) != null) return pos.toImmutable();
			}
		}
		return null;
	}

	private Placement placementFor(MinecraftClient client, BlockPos placeAt, boolean avoidAnchorSupport) {
		for (Direction face : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
			BlockPos support = placeAt.offset(face.getOpposite());
			if (avoidAnchorSupport && support.equals(anchorPos)) continue;
			if (client.world.getBlockState(support).getCollisionShape(client.world, support).isEmpty()) continue;
			Vec3d point = facePoint(support, face);
			if (withinReach(client, point))
				return new Placement(support.toImmutable(), face);
		}
		return null;
	}

	/**
	 * Where the anchor will actually land, mirroring {@code ItemPlacementContext.getBlockPos()}:
	 * a replaceable clicked block is REPLACED, anything else is built against.
	 *
	 * <p>Assuming the offset unconditionally is what broke this module. Clicking grass, a snow
	 * layer or water made the sequence watch an empty cell, so it never saw its own anchor, and the
	 * confirmation retry then placed a second one — which is exactly the "anchors on top of
	 * anchors" and "it just misses" behaviour.
	 */
	private BlockPos placementTarget(World world, BlockHitResult hit) {
		BlockPos clicked = hit.getBlockPos();
		return world.getBlockState(clicked).isReplaceable()
				? clicked.toImmutable()
				: clicked.offset(hit.getSide()).toImmutable();
	}

	/**
	 * An anchor the sequence can adopt after a placement whose landing cell we guessed wrong, or
	 * that the server nudged. Only looks where our own click could plausibly have put one.
	 */
	private BlockPos findPlacedAnchor(MinecraftClient client) {
		if (anchorPos == null) return null;
		BlockPos[] candidates = supportPos == null
				? new BlockPos[]{anchorPos}
				: new BlockPos[]{anchorPos, supportPos,
						supportFace == null ? supportPos : supportPos.offset(supportFace)};
		for (BlockPos pos : candidates) {
			if (pos == null) continue;
			if (client.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)
					&& withinReach(client, Vec3d.ofCenter(pos))) return pos.toImmutable();
		}
		return null;
	}

	private Vec3d facePoint(BlockPos block, Direction face) {
		return Vec3d.ofCenter(block).add(Vec3d.of(face.getVector()).multiply(0.5D));
	}

	private double interactionRange(MinecraftClient client) {
		return Math.max(1.0D, client.player.getBlockInteractionRange());
	}

	private boolean withinReach(MinecraftClient client, Vec3d point) {
		double reach = interactionRange(client) + 0.1D;
		return client.player.getEyePos().squaredDistanceTo(point) <= reach * reach;
	}

	private boolean anchorPresent(MinecraftClient client) {
		return anchorPos != null && client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);
	}

	/**
	 * Whether the anchor still needs glowstone before a click can detonate it.
	 *
	 * <p>Vanilla {@code RespawnAnchorBlock.onUseWithItem} reads:
	 * <pre>
	 *   if (isChargeItem(main) &amp;&amp; canCharge(state))                       -> charge
	 *   if (hand == MAIN_HAND &amp;&amp; isChargeItem(offhand) &amp;&amp; canCharge(state)) -> PASS
	 *   else                                                            -> explode
	 * </pre>
	 * so with glowstone in the OFFHAND a main-hand click never explodes — it defers to the offhand,
	 * which quietly adds another charge. That is the "it doesn't explode" report, and no amount of
	 * retrying fixes it. The way out is to make {@code canCharge} false by filling the anchor to
	 * {@link #MAX_CHARGES}; once it is full (or the glowstone runs out and the offhand empties) the
	 * very next click detonates.
	 */
	private boolean needsCharge(MinecraftClient client) {
		if (!anchorPresent(client)) return false;
		return needsChargeFor(charges(client.world.getBlockState(anchorPos)),
				client.player.getOffHandStack().isOf(Items.GLOWSTONE));
	}

	/** The rule above, in primitives, so it can be pinned by a test. */
	static boolean needsChargeFor(int charges, boolean offhandIsGlowstone) {
		if (charges <= 0) return true;
		if (charges >= MAX_CHARGES) return false;
		return offhandIsGlowstone;
	}

	private int charges(BlockState state) {
		return state.contains(RespawnAnchorBlock.CHARGES) ? state.get(RespawnAnchorBlock.CHARGES) : 0;
	}

	private boolean anchorCharged(MinecraftClient client) {
		return anchorPresent(client) && charged(client.world.getBlockState(anchorPos));
	}

	private boolean charged(BlockState state) {
		return state.contains(RespawnAnchorBlock.CHARGES) && state.get(RespawnAnchorBlock.CHARGES) > 0;
	}

	private boolean useBlock(MinecraftClient client, BlockHitResult hit) {
		MinecraftClientInvoker vanilla = (MinecraftClientInvoker) client;
		if (client.interactionManager.isBreakingBlock()) return false;
		// The sequence owns block use at this point, so bypass only the local repeat-key
		// cooldown for this scheduled action. Use the exact fresh ray that passed the
		// checks above; client.crosshairTarget can otherwise still describe the previous
		// render frame and make vanilla silently click the wrong block. doItemUse restores
		// vanilla's cooldown afterward; held physical input remains suppressed by onUseBlock.
		vanilla.profps$setItemUseCooldown(0);
		client.crosshairTarget = hit;
		selfInteracting = true;
		try {
			vanilla.invokeDoItemUse();
		} finally {
			selfInteracting = false;
		}
		lastUseAge = client.player.age;
		return true;
	}

	private boolean claim(MinecraftClient client) {
		// A physical interaction can happen at most once in a client tick. Timing above
		// that floor is randomized by schedule() and controlled by Anchor Speed.
		return useSeparatedByTick(lastUseAge, client.player.age)
				&& CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_ANCHOR);
	}

	/** True only while Anchor Macro owns the block-use sequence. */
	public boolean isSequencing() {
		return phase != Phase.IDLE;
	}

	static boolean useSeparatedByTick(int lastAge, int currentAge) {
		return lastAge == Integer.MIN_VALUE || currentAge > lastAge;
	}

	private int explosionSlot(MinecraftClient client) {
		if (config.anchorExplosionItemWhitelist) {
			for (int slot = 0; slot < 9; slot++) {
				String id = Registries.ITEM.getId(client.player.getInventory().getStack(slot).getItem()).toString();
				if (config.anchorExplosionItems.contains(id) && safeExplosionStack(client, slot)) return slot;
			}
			return -1;
		}
		// Prefer items that cannot place against the anchor. The caller considers the
		// offhand next and uses an anchor stack only as the final confirmed-charge fallback.
		if (previousSlot >= 0 && previousSlot < 9 && safeExplosionStack(client, previousSlot)) return previousSlot;
		int totem = findHotbarSlot(client, Items.TOTEM_OF_UNDYING);
		if (totem >= 0) return totem;
		for (int slot = 0; slot < 9; slot++) {
			if (client.player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		for (int slot = 0; slot < 9; slot++) {
			if (safeExplosionStack(client, slot)) return slot;
		}
		return -1;
	}

	private boolean safeExplosionStack(MinecraftClient client, int slot) {
		var stack = client.player.getInventory().getStack(slot);
		return !stack.isEmpty() && !stack.isOf(Items.GLOWSTONE)
				&& !stack.isOf(Items.RESPAWN_ANCHOR) && !(stack.getItem() instanceof BlockItem);
	}

	/** Selects a non-anchor main-hand item when detonation is disabled. */
	private int finishItemSlot(MinecraftClient client) {
		if (config.anchorExplosionItemWhitelist) {
			for (int slot = 0; slot < 9; slot++) {
				Item item = client.player.getInventory().getStack(slot).getItem();
				String id = Registries.ITEM.getId(item).toString();
				if (config.anchorExplosionItems.contains(id)
						&& item != Items.GLOWSTONE && item != Items.RESPAWN_ANCHOR) return slot;
			}
		}
		int totem = findHotbarSlot(client, Items.TOTEM_OF_UNDYING);
		if (totem >= 0) return totem;
		if (previousSlot >= 0 && previousSlot < 9) {
			Item previous = client.player.getInventory().getStack(previousSlot).getItem();
			if (previous != Items.GLOWSTONE && previous != Items.RESPAWN_ANCHOR) return previousSlot;
		}
		for (int slot = 0; slot < 9; slot++) {
			Item item = client.player.getInventory().getStack(slot).getItem();
			if (item != Items.GLOWSTONE && item != Items.RESPAWN_ANCHOR) return slot;
		}
		return -1;
	}

	private boolean hasTotem(MinecraftClient client) {
		if (client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return true;
		return findHotbarSlot(client, Items.TOTEM_OF_UNDYING) >= 0;
	}

	private int findHotbarSlot(MinecraftClient client, Item item) {
		for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getStack(slot).isOf(item)) return slot;
		return -1;
	}

	private int countHotbarItem(MinecraftClient client, Item item) {
		int count = 0;
		for (int slot = 0; slot < 9; slot++) {
			if (client.player.getInventory().getStack(slot).isOf(item))
				count += client.player.getInventory().getStack(slot).getCount();
		}
		return count;
	}

	private void selectHotbarSlot(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) return;
		client.player.getInventory().setSelectedSlot(slot);
	}

	private void schedule() {
		int min = actionDelayMinMsForSpeed(config.anchorSpeed);
		int max = actionDelayMaxMsForSpeed(config.anchorSpeed);
		int delay = min + (max <= min ? 0 : rng.nextInt(max - min + 1));
		nextActionNanos = System.nanoTime() + delay * 1_000_000L;
		status = phase.label;
	}

	/** Randomized action gaps; the one-use-per-tick gate remains authoritative at every level. */
	static int actionDelayMinMsForSpeed(int speed) {
		return switch (MathHelper.clamp(speed, 1, 10)) {
			case 10 -> 0;
			case 9 -> 4;
			case 8 -> 10;
			case 7 -> 22;
			case 6 -> 35;
			case 5 -> 55;
			case 4 -> 80;
			case 3 -> 115;
			case 2 -> 155;
			default -> 205;
		};
	}

	/** A small bounded jitter keeps repeated sequences from landing on one exact interval. */
	static int actionDelayMaxMsForSpeed(int speed) {
		return switch (MathHelper.clamp(speed, 1, 10)) {
			case 10 -> 52;
			case 9 -> 70;
			case 8 -> 82;
			case 7 -> 95;
			case 6 -> 115;
			case 5 -> 140;
			case 4 -> 175;
			case 3 -> 215;
			case 2 -> 255;
			default -> 320;
		};
	}

	private void retry(String retryStatus) {
		status = retryStatus;
		nextActionNanos = System.nanoTime() + (110L + rng.nextInt(81)) * 1_000_000L;
	}

	private void armDeadline() {
		deadlineNanos = System.nanoTime() + SEQUENCE_TIMEOUT_NS;
		nextActionNanos = System.nanoTime();
		shieldDone = false;
		placeAttempts = 0;
		shieldPos = null;
		anchorConfirmStartedNanos = 0L;
		shieldConfirmStartedNanos = 0L;
		chargeConfirmStartedNanos = 0L;
		detonateConfirmStartedNanos = 0L;
		status = phase.label;
	}

	private void renewDeadline() {
		deadlineNanos = System.nanoTime() + SEQUENCE_TIMEOUT_NS;
	}

	private void finish(MinecraftClient client, String finalStatus, boolean disableBind) {
		finish(client, finalStatus, disableBind, true);
	}

	private void finish(MinecraftClient client, String finalStatus, boolean disableBind, boolean restorePrevious) {
		if (restorePrevious && client != null && client.player != null && client.interactionManager != null
				&& previousSlot >= 0 && previousSlot < 9) selectHotbarSlot(client, previousSlot);
		phase = Phase.IDLE;
		supportPos = null;
		supportFace = null;
		anchorPos = null;
		shieldPos = null;
		previousSlot = -1;
		nextActionNanos = 0L;
		deadlineNanos = 0L;
		anchorConfirmStartedNanos = 0L;
		shieldConfirmStartedNanos = 0L;
		chargeConfirmStartedNanos = 0L;
		detonateConfirmStartedNanos = 0L;
		shieldDone = false;
		placeAttempts = 0;
		status = finalStatus;
		if (disableBind && config.anchorMode == 0) config.anchorMacro = false;
	}

	public String status(MinecraftClient client) {
		if (!config.anchorMacro && phase == Phase.IDLE) return "Off";
		return status;
	}

	/**
	 * Deliberately does not refuse while an item is being used.
	 *
	 * <p>It used to, and that is why spamming right-click could stop the anchor
	 * being placed at all: any held use — a shield, a totem, food, a spear charge,
	 * or just the tail of your own click — made the whole macro stand down for as
	 * long as it lasted. The macro places through {@code interactionManager}
	 * directly, so an in-progress use never actually blocked the placement; it
	 * only blocked us from trying. Any real use is ended once at the start of a
	 * sequence instead, which is what a player does by letting go.
	 */
	private boolean allowed(MinecraftClient client) {
		return client != null && client.player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && client.isWindowFocused() && client.player.isAlive()
				&& client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}

	/** Lets go of whatever the player is holding down so the first click is not swallowed. */
	private void releaseHeldUse(MinecraftClient client) {
		if (client.player != null && client.player.isUsingItem()) {
			client.interactionManager.stopUsingItem(client.player);
		}
	}

	private enum Phase {
		IDLE("Idle"), PLACE_ANCHOR("Placing anchor"), WAIT_ANCHOR("Confirming anchor"),
		PLACE_SHIELD("Placing shield"), WAIT_SHIELD("Confirming shield"),
		CHARGE("Charging anchor"), WAIT_CHARGE("Confirming charge"),
		FINISH_CHARGED("Finishing safely"),
		DETONATE("Detonating anchor"), WAIT_DETONATE("Confirming detonation"), RESTORE("Restoring");
		private final String label;
		Phase(String label) { this.label = label; }
	}

	private record Placement(BlockPos support, Direction face) {}
}
