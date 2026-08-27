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
	/** Longer than {@link #CONFIRM_GRACE_NS}: the server can reject a placement the client already drew. */
	private static final long PLACE_GRACE_NS = 400_000_000L;
	/** Hard cap on macro placement attempts per sequence. */
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
	/** Client age of the last real or macro block-use; uses stay in separate ticks. */
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

		// A real placement click re-arms the sequence even if one is already running.
		if (config.anchorMode == 1 && holdingAnchor && !clicked.isOf(Blocks.RESPAWN_ANCHOR)) {
			beginPhysicalPlacement(player, world, hit);
			return ActionResult.PASS;
		}

		// A live sequence owns charge and detonation, so suppress other physical interactions.
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
		// Runs before vanilla sends the placement, so reserve the rest of the tick.
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

		// Collapses state-only hand-offs; claim() still gates physical uses to one per tick.
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
			if (phase == before) return;   // unchanged phase means it is still waiting
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
		// Support is re-resolved every tick until the deadline; blocks near a fresh explosion settle late.
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
		// An anchor already in the target cell means the previous click landed; adopt it.
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
		// Keep the live face authoritative; several faces can lead to the same placement cell.
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
		// The click may have landed one cell off; adopt it so the retry does not stack a second anchor.
		if (!anchorPresent(client)) {
			BlockPos placed = findPlacedAnchor(client);
			if (placed != null) anchorPos = placed;
		}
		if (anchorPresent(client)) {
			// Leave the anchor stack immediately so held right-click cannot stack another.
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
			// Cover is best-effort: with no legal cell, charge in this same tick.
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
			// The crosshair is never moved to reach cover.
			shieldDone = true;
			phase = Phase.CHARGE;
			charge(client);
			return;
		}
		int slot = findHotbarSlot(client, Items.GLOWSTONE);
		if (slot < 0 || countHotbarItem(client, Items.GLOWSTONE) < 2) {
			// Cover needs a second glowstone beyond the one the charge consumes.
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
			// Hand off through the state machine; calling detonate() inline is re-entrant.
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
		// Falling back to the offhand drains the stack that blocks detonation under the offhand rule.
		Hand chargeHand = slot >= 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
		if (chargeHand == Hand.MAIN_HAND) selectHotbarSlot(client, slot);
		if (!useBlock(client, hit)) { retry("Charge refused"); return; }
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
		// Charged but still topping up: the offhand rule requires a full anchor before detonation.
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
		// A lethal estimate only changes the status; detonation always proceeds.
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

	/** The player's live block ray, but only if clicking it would place in {@code placeAt}. */
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
	 * A cell to place cover in, between the player and the anchor.
	 * Cells beside the player are tried first, then the line out toward the anchor.
	 */
	private BlockPos findShieldPosition(MinecraftClient client) {
		Vec3d from = new Vec3d(client.player.getX(), client.player.getY() + 0.5D, client.player.getZ());
		Vec3d to = Vec3d.ofCenter(anchorPos);
		Vec3d delta = to.subtract(from);
		double distance = delta.length();
		if (distance < 0.1D) return null;
		Vec3d direction = delta.normalize();
		java.util.Set<BlockPos> checked = new java.util.HashSet<>();

		// Point-blank cover: cells adjacent to the body facing the blast, at feet and head height.
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
			// Direct blast line first, then nearby cells that may still have legal support.
			for (BlockPos pos : new BlockPos[]{base, base.down(), base.north(), base.south(),
					base.west(), base.east(), base.north().down(), base.south().down(),
					base.west().down(), base.east().down()}) {
				if (!checked.add(pos) || pos.equals(anchorPos)
						|| !client.world.getBlockState(pos).isReplaceable()) continue;
				if (new net.minecraft.util.math.Box(pos).intersects(client.player.getBoundingBox())) continue;
				// Reach and support test only; the crosshair is checked after a candidate is chosen.
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
	 * Where the placement will land, mirroring {@code ItemPlacementContext.getBlockPos()}:
	 * a replaceable clicked block is replaced, anything else is built against.
	 */
	private BlockPos placementTarget(World world, BlockHitResult hit) {
		BlockPos clicked = hit.getBlockPos();
		return world.getBlockState(clicked).isReplaceable()
				? clicked.toImmutable()
				: clicked.offset(hit.getSide()).toImmutable();
	}

	/** An anchor in one of the cells this sequence's own click could have placed it in, or null. */
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
	 * With glowstone in the offhand, {@code RespawnAnchorBlock.onUseWithItem} charges instead of
	 * exploding while {@code canCharge} holds, so the anchor must be filled to {@link #MAX_CHARGES}.
	 */
	private boolean needsCharge(MinecraftClient client) {
		if (!anchorPresent(client)) return false;
		return needsChargeFor(charges(client.world.getBlockState(anchorPos)),
				client.player.getOffHandStack().isOf(Items.GLOWSTONE));
	}

	/** The {@link #needsCharge} rule expressed over primitives. */
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
		// Clear the repeat-key cooldown and pin the fresh ray: client.crosshairTarget can be
		// one render frame behind, which would make vanilla click the wrong block.
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
		// At most one physical interaction per client tick.
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
		// Prefer items that cannot place a block against the anchor.
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

	/** Minimum action gap in milliseconds for a speed setting. */
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

	/** Maximum action gap in milliseconds for a speed setting. */
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

	/** Does not check for an in-progress item use; that is released once at sequence start. */
	private boolean allowed(MinecraftClient client) {
		return client != null && client.player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && client.isWindowFocused() && client.player.isAlive()
				&& client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}

	/** Stops any in-progress item use so the first click is not swallowed. */
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
