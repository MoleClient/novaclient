package com.profps.client.crystalpvp;

import com.profps.client.aim.MouseGcd;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
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

/**
 * AutoAnchor: On Bind / On Place, Safe Anchor, air place, and item selection.
 *
 * <p>Air place is what lets the anchor go back into the crater. A detonation
 * takes the surrounding blocks with it, so the cell it left behind usually has
 * no neighbouring face to build against at all — and an ordinary placement needs
 * one. Clicking the empty cell itself instead is what puts the next anchor
 * exactly where the last one blew up: air is replaceable, so vanilla's placement
 * context resolves to the clicked cell rather than offsetting off a face, and a
 * respawn anchor requires no support of its own.
 */
public final class AnchorMacroController {
	private static final long SEQUENCE_TIMEOUT_NS = 4_000_000_000L;
	private static final long CONFIRM_GRACE_NS = 150_000_000L;
	/**
	 * Placement gets a longer grace than charge/detonate. Those two only need the client's own
	 * predicted block state to flip, but a placement can be rejected by the server after the client
	 * already drew it, and re-clicking too early is what stacks a second anchor.
	 */
	private static final long PLACE_GRACE_NS = 400_000_000L;
	/** Hard cap on physical placement clicks per sequence. Without it a bad target loops until timeout. */
	private static final int MAX_PLACE_ATTEMPTS = 2;
	/** Ticks Safe Anchor may spend trying to aim at cover before it gives up and detonates anyway. */
	private static final int MAX_SHIELD_TICKS = 6;
	/** Bound on phase hand-offs collapsed into a single tick. */
	private static final int MAX_STEPS_PER_TICK = 4;
	/** Vanilla {@code RespawnAnchorBlock.MAX_CHARGES}; {@code canCharge} is {@code charges < 4}. */
	private static final int MAX_CHARGES = 4;
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();
	private static AnchorMacroController instance;

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
	private int aimReadyAge = -1;
	private boolean selfInteracting;
	private boolean bindStarted;
	private boolean shieldDone;
	private int placeAttempts;
	private int shieldAttempts;
	private float silentYaw = Float.NaN;
	private float silentPitch = Float.NaN;
	private float cameraYaw;
	private float cameraPitch;
	private int silentPacketAge = -1;
	private boolean silentApplied;
	private String status = "Idle";

	public AnchorMacroController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/** Called around vanilla's movement packet construction to provide Vape-style silent aim. */
	public static void beforeMovementPacket(ClientPlayerEntity player) {
		AnchorMacroController self = instance;
		if (self == null || player == null || !self.config.anchorMacro || !self.config.anchorAimAssist
				|| !self.config.anchorSilentAim || self.phase == Phase.IDLE
				|| !Float.isFinite(self.silentYaw) || !Float.isFinite(self.silentPitch)) return;
		self.cameraYaw = player.getYaw();
		self.cameraPitch = player.getPitch();
		player.setYaw(self.silentYaw);
		player.setPitch(self.silentPitch);
		self.silentPacketAge = player.age;
		self.silentApplied = true;
	}

	public static void afterMovementPacket(ClientPlayerEntity player) {
		AnchorMacroController self = instance;
		if (self == null || player == null || !self.silentApplied) return;
		player.setYaw(self.cameraYaw);
		player.setPitch(self.cameraPitch);
		player.headYaw = player.getYaw();
		self.silentApplied = false;
	}

	/** On Place mode begins only after the player's real anchor placement click. */
	public ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player, World world,
			Hand hand, BlockHitResult hit) {
		if (!config.anchorMacro || !world.isClient() || selfInteracting) return ActionResult.PASS;
		if (player == null || !player.getStackInHand(hand).isOf(Items.RESPAWN_ANCHOR)) return ActionResult.PASS;
		BlockState clicked = world.getBlockState(hit.getBlockPos());
		// Once a sequence owns the interaction, suppress every additional physical
		// anchor-item click until it completes. The original On Place click has already
		// been returned to vanilla; any later one can only create an unintended second
		// placement during confirmation, swapping, or detonation.
		if (phase != Phase.IDLE) return ActionResult.FAIL;
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
		supportPos = hit.getBlockPos().toImmutable();
		supportFace = hit.getSide();
		anchorPos = placementTarget(world, hit);
		phase = Phase.WAIT_ANCHOR;
		armDeadline();
		anchorConfirmStartedNanos = System.nanoTime();
		return ActionResult.PASS;
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

		// Run consecutive phases within one tick while they are due. A phase that hands off with
		// "act immediately" used to still wait for the NEXT tick, because the switch had already
		// run — and place -> confirm -> charge -> confirm -> detonate is four such hand-offs, so
		// the glowstone and the blast were arriving a full 200ms later than intended. Anything that
		// genuinely has to wait (aim settling, server confirmation) pushes nextActionNanos out and
		// breaks the loop on its own, so this only removes dead time.
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
		if (!(fresh instanceof BlockHitResult hit)) {
			// Nothing under the crosshair. That is the crater: the blast removed
			// the anchor and everything around it, so there is no block left to
			// aim at even though the cell is exactly where the next anchor
			// belongs. Refusing here is what made air place look dead — the
			// placement code below was never reached at all.
			BlockPos airCell = airPlaceTarget(client);
			if (airCell == null) { finish(client, "Aim at a block", true); return; }
			releaseHeldUse(client);
			previousSlot = client.player.getInventory().getSelectedSlot();
			anchorPos = airCell;
			supportPos = airCell;
			supportFace = Direction.UP;
			phase = Phase.PLACE_ANCHOR;
			armDeadline();
			return;
		}
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

	/**
	 * The empty cell the crosshair is pointing into, for placing with nothing to
	 * click. Walks outward and keeps the furthest cell still in reach, so aiming
	 * into a crater targets the crater rather than the air just off your nose.
	 */
	private BlockPos airPlaceTarget(MinecraftClient client) {
		if (!config.anchorAirPlace) return null;
		ClientPlayerEntity player = client.player;
		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0F);
		BlockPos best = null;
		for (double distance = 1.0D; distance <= 5.0D; distance += 0.25D) {
			Vec3d point = eye.add(look.multiply(distance));
			if (!withinReach(client, point)) break;
			BlockPos cell = BlockPos.ofFloored(point);
			if (!client.world.getBlockState(cell).isReplaceable()) break; // a real block; stop here
			if (new net.minecraft.util.math.Box(cell).intersects(player.getBoundingBox())) continue;
			best = cell.toImmutable();
		}
		return best;
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
			aimReadyAge = -1;
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
		Vec3d point = facePoint(supportPos, supportFace);
		BlockHitResult hit = exactBlockHit(client, supportPos, supportFace, point);
		if (hit == null) return;
		int slot = findHotbarSlot(client, Items.RESPAWN_ANCHOR);
		if (slot < 0) { finish(client, "No respawn anchor", true); return; }
		if (!claim()) return;
		selectHotbarSlot(client, slot);
		if (!useBlock(client, Hand.MAIN_HAND, hit)) { retry("Anchor refused"); return; }
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
			aimReadyAge = -1;
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
			aimReadyAge = -1;
			status = "Retrying anchor";
			return;
		}
		nextActionNanos = now + 10_000_000L;
	}

	private Phase nextChargePhase() {
		return config.anchorSafe && !shieldDone ? Phase.PLACE_SHIELD : Phase.CHARGE;
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
		BlockHitResult hit = exactBlockHit(client, placement.support(), placement.face(), placement.point());
		if (hit == null) {
			// The chosen cover stopped being clickable between choosing it and
			// now. Look once for another and carry on in this same tick — cover
			// is a bonus, and making the blast wait on it is worse than going
			// without it.
			shieldPos = shieldAttempts++ < MAX_SHIELD_TICKS ? findShieldPosition(client) : null;
			if (shieldPos != null) {
				placeShield(client);
				return;
			}
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
		if (!claim()) return;
		selectHotbarSlot(client, slot);
		if (!useBlock(client, Hand.MAIN_HAND, hit)) { retry("Shield refused"); return; }
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
			aimReadyAge = -1;
			status = "Charging anchor";
			return;
		}
		long now = System.nanoTime();
		if (shieldConfirmStartedNanos == 0L) shieldConfirmStartedNanos = now;
		if (now - shieldConfirmStartedNanos >= CONFIRM_GRACE_NS) {
			phase = Phase.PLACE_SHIELD;
			nextActionNanos = now;
			aimReadyAge = -1;
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
		if (!claim()) return;
		// Hotbar glowstone can run out while topping the anchor up for the offhand rule. Charging
		// from the offhand then drains the very stack that was blocking detonation, so the sequence
		// resolves itself instead of dead-ending on "No glowstone".
		Hand chargeHand = slot >= 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
		if (chargeHand == Hand.MAIN_HAND) selectHotbarSlot(client, slot);
		if (!useBlock(client, chargeHand, hit)) { retry("Charge refused"); return; }
		// Charge and detonation use the same anchor face. Preserve the already-settled
		// aim so confirmation can detonate on the very next client tick.
		aimReadyAge = client.player.age - 1;
		chargeConfirmStartedNanos = System.nanoTime();
		phase = Phase.WAIT_CHARGE;
		nextActionNanos = chargeConfirmStartedNanos;
		status = "Confirming charge";
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
			aimReadyAge = -1;
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
		if (config.anchorStopWhenNoTotem && !hasTotem(client)) {
			finish(client, "No totem", true); return;
		}
		// Safe Anchor is cover, not a veto. It used to refuse a blast it judged lethal, which meant
		// standing too close to fit glowstone between you and the anchor silently cancelled the
		// whole macro. It now always detonates; the damage estimate only colours the status.
		if (config.anchorSafe && damage >= client.player.getHealth() + client.player.getAbsorptionAmount()
				&& !hasTotem(client)) {
			status = "Detonating uncovered";
		}
		BlockHitResult hit = exactAnchorHit(client);
		if (hit == null) return;
		int slot = explosionSlot(client);
		Hand explosionHand = Hand.MAIN_HAND;
		if (slot < 0 && offhandExplosionAllowed(client)) explosionHand = Hand.OFF_HAND;
		else if (slot < 0 && !config.anchorExplosionItemWhitelist)
			slot = findHotbarSlot(client, Items.RESPAWN_ANCHOR);
		if (slot < 0 && explosionHand == Hand.MAIN_HAND) { finish(client, "No explosion item", true); return; }
		if (!claim()) return;
		if (explosionHand == Hand.MAIN_HAND) selectHotbarSlot(client, slot);
		if (!useBlock(client, explosionHand, hit)) { retry("Detonation refused"); return; }
		detonateConfirmStartedNanos = System.nanoTime();
		phase = Phase.WAIT_DETONATE;
		nextActionNanos = detonateConfirmStartedNanos;
		status = "Confirming detonation";
	}

	private void waitDetonate(MinecraftClient client) {
		if (!anchorPresent(client)) {
			detonateConfirmStartedNanos = 0L;
			{
				phase = Phase.RESTORE;
				schedule();
			}
			return;
		}
		long now = System.nanoTime();
		if (detonateConfirmStartedNanos == 0L) detonateConfirmStartedNanos = now;
		if (now - detonateConfirmStartedNanos >= CONFIRM_GRACE_NS) {
			phase = Phase.DETONATE;
			nextActionNanos = now;
			aimReadyAge = -1;
			detonateConfirmStartedNanos = 0L;
			status = "Retrying detonation";
			return;
		}
		status = "Confirming detonation";
		nextActionNanos = now + 1_000_000L;
	}

	private BlockHitResult exactAnchorHit(MinecraftClient client) {
		if (!anchorPresent(client)) return null;
		Vec3d point = bestAnchorAimPoint(client);
		if (point == null) {
			aimReadyAge = -1;
			status = "No visible anchor face";
			return null;
		}
		if (config.anchorAimAssist) turnToward(client.player, point);
		HitResult fresh = config.anchorAimAssist && config.anchorSilentAim ? silentBlockHit(client) : freshHit(client);
		if (!(fresh instanceof BlockHitResult hit) || !hit.getBlockPos().equals(anchorPos)
				|| !withinReach(client, hit.getPos())) {
			aimReadyAge = -1;
			status = config.anchorAimAssist ? "Aiming" : "Keep aim on anchor";
			return null;
		}
		if (!settled(client.player)) return null;
		return hit;
	}

	private BlockHitResult exactBlockHit(MinecraftClient client, BlockPos block, Direction face, Vec3d point) {
		if (config.anchorAimAssist) turnToward(client.player, point);
		if (config.anchorAirPlace) {
			// Air place: build the click from the geometry rather than waiting for
			// the crosshair to agree with it. The support and the face are real
			// and in reach — the only thing being skipped is the client's own
			// line-of-sight confirmation, which is what stopped placements the
			// server would have accepted: a face behind an entity, around a
			// corner, or simply not centred yet. It also drops the settle tick,
			// because there is no longer an aim to settle.
			return withinReach(client, point) ? new BlockHitResult(point, face, block.toImmutable(), false) : null;
		}
		HitResult fresh = config.anchorAimAssist && config.anchorSilentAim ? silentBlockHit(client) : freshHit(client);
		if (!(fresh instanceof BlockHitResult hit) || !hit.getBlockPos().equals(block) || hit.getSide() != face
				|| !withinReach(client, hit.getPos())) {
			aimReadyAge = -1;
			status = config.anchorAimAssist ? "Aiming" : "Keep aim on target";
			return null;
		}
		if (!settled(client.player)) return null;
		return hit;
	}

	private boolean settled(ClientPlayerEntity player) {
		if (aimReadyAge < 0) { aimReadyAge = player.age; return false; }
		if (config.anchorAimAssist && config.anchorSilentAim && silentPacketAge <= aimReadyAge) return false;
		return player.age > aimReadyAge;
	}

	private void turnToward(ClientPlayerEntity player, Vec3d point) {
		Vec3d d = point.subtract(player.getEyePos());
		float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0D);
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		if (config.anchorSilentAim) {
			silentYaw = yaw;
			silentPitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
			return;
		}
		float speed = MathHelper.clamp(config.anchorAimSpeedTenths / 10.0F, 1.0F, 15.0F);
		player.setYaw(player.getYaw() + mouse.yaw(MathHelper.clamp(MathHelper.wrapDegrees(yaw - player.getYaw()), -speed, speed)));
		player.setPitch(MathHelper.clamp(player.getPitch()
				+ mouse.pitch(MathHelper.clamp(pitch - player.getPitch(), -speed, speed)), -90.0F, 90.0F));
		player.headYaw = player.getYaw();
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
		Vec3d to = bestAnchorAimPoint(client);
		if (to == null) to = Vec3d.ofCenter(anchorPos);
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
				// Deliberately only a reach/support test. Proving the exact click
				// here would mean calling the aim path, which turns the player
				// toward every candidate it tries — the search would spin you
				// around and reset the aim it had already settled. An unusable
				// cell is cheap now that the failure path retries in the same tick.
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
				return new Placement(support.toImmutable(), face, point);
		}
		// Nothing borders the cell. That is the normal state of a crater the
		// instant after a detonation — the blast took the neighbours with it —
		// and it is exactly when the anchor needs to go back in the same hole.
		// Clicking the empty cell itself is what does that: air is replaceable,
		// so vanilla's placement context resolves to the clicked cell rather
		// than offsetting off a face, and the anchor lands precisely there. A
		// respawn anchor needs no support of its own, so nothing else is
		// required for the placement to be legal.
		if (config.anchorAirPlace && client.world.getBlockState(placeAt).isReplaceable()) {
			Vec3d point = Vec3d.ofCenter(placeAt);
			if (withinReach(client, point)) {
				return new Placement(placeAt.toImmutable(), Direction.UP, point);
			}
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

	/**
	 * Finds the closest-angle anchor surface that vanilla's current block ray can actually see.
	 * Center-only aiming fails at ordinary three-block spacing and after Safe Anchor adds cover.
	 */
	private Vec3d bestAnchorAimPoint(MinecraftClient client) {
		if (anchorPos == null) return null;
		double x = anchorPos.getX();
		double y = anchorPos.getY();
		double z = anchorPos.getZ();
		// Keep samples just inside the anchor outline. Exact block-boundary points can
		// be attributed to the support/adjacent block by vanilla's raycast, especially
		// when the anchor is mounted at eye height.
		double low = 0.01D;
		double high = 0.99D;
		Vec3d[] candidates = {
				new Vec3d(x + 0.5D, y + high, z + 0.5D),
				new Vec3d(x + 0.5D, y + low, z + 0.5D),
				new Vec3d(x + low, y + 0.5D, z + 0.5D),
				new Vec3d(x + high, y + 0.5D, z + 0.5D),
				new Vec3d(x + 0.5D, y + 0.5D, z + low),
				new Vec3d(x + 0.5D, y + 0.5D, z + high),
				new Vec3d(x + 0.25D, y + high, z + 0.5D),
				new Vec3d(x + 0.75D, y + high, z + 0.5D),
				new Vec3d(x + 0.25D, y + low, z + 0.5D),
				new Vec3d(x + 0.75D, y + low, z + 0.5D),
				new Vec3d(x + low, y + 0.25D, z + 0.5D),
				new Vec3d(x + high, y + 0.75D, z + 0.5D),
				new Vec3d(x + 0.5D, y + 0.25D, z + low),
				new Vec3d(x + 0.5D, y + 0.75D, z + high)
		};
		Vec3d eye = client.player.getEyePos();
		double reach = interactionRange(client);
		Vec3d best = null;
		double bestScore = Double.MAX_VALUE;
		for (Vec3d candidate : candidates) {
			Vec3d delta = candidate.subtract(eye);
			double distance = delta.length();
			if (distance < 1.0E-4D || distance > reach + 0.1D) continue;
			Vec3d end = eye.add(delta.normalize().multiply(reach));
			HitResult ray = client.world.raycast(new net.minecraft.world.RaycastContext(eye, end,
					net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
					net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player));
			if (!(ray instanceof BlockHitResult block) || !block.getBlockPos().equals(anchorPos)) continue;
			float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
			float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
			double yawError = MathHelper.wrapDegrees(yaw - client.player.getYaw());
			double pitchError = pitch - client.player.getPitch();
			double score = yawError * yawError + pitchError * pitchError + distance * 0.01D;
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private double interactionRange(MinecraftClient client) {
		return Math.max(1.0D, client.player.getBlockInteractionRange());
	}

	private boolean withinReach(MinecraftClient client, Vec3d point) {
		double reach = interactionRange(client) + 0.1D;
		return client.player.getEyePos().squaredDistanceTo(point) <= reach * reach;
	}

	private HitResult freshHit(MinecraftClient client) {
		return client.player.getCrosshairTarget(1.0F,
				client.getCameraEntity() == null ? client.player : client.getCameraEntity());
	}

	private HitResult silentBlockHit(MinecraftClient client) {
		if (!Float.isFinite(silentYaw) || !Float.isFinite(silentPitch)) return null;
		Vec3d start = client.player.getEyePos();
		Vec3d end = start.add(Vec3d.fromPolar(silentPitch, silentYaw).multiply(interactionRange(client)));
		return client.world.raycast(new net.minecraft.world.RaycastContext(start, end,
				net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
				net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player));
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

	private boolean useBlock(MinecraftClient client, Hand hand, BlockHitResult hit) {
		ActionResult result;
		selfInteracting = true;
		try {
			client.crosshairTarget = hit;
			result = client.interactionManager.interactBlock(client.player, hand, hit);
		} finally {
			selfInteracting = false;
		}
		if (!result.isAccepted()) return false;
		client.player.swingHand(hand);
		aimReadyAge = -1;
		return true;
	}

	private boolean claim() {
		return CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_ANCHOR);
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

	private boolean offhandExplosionAllowed(MinecraftClient client) {
		var stack = client.player.getOffHandStack();
		if (stack.isEmpty() || stack.isOf(Items.GLOWSTONE) || stack.isOf(Items.RESPAWN_ANCHOR)
				|| stack.getItem() instanceof BlockItem) return false;
		if (!config.anchorExplosionItemWhitelist) return true;
		return config.anchorExplosionItems.contains(Registries.ITEM.getId(stack.getItem()).toString());
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
		client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	private void schedule() {
		int min = MathHelper.clamp(config.anchorDelayMinMs, 0, 500);
		int max = MathHelper.clamp(config.anchorDelayMaxMs, min, 500);
		int delay = min + (max <= min ? 0 : rng.nextInt(max - min + 1));
		nextActionNanos = System.nanoTime() + delay * 1_000_000L;
		status = phase.label;
	}

	private void retry(String retryStatus) {
		status = retryStatus;
		nextActionNanos = System.nanoTime() + 50_000_000L;
		aimReadyAge = -1;
	}

	private void armDeadline() {
		deadlineNanos = System.nanoTime() + SEQUENCE_TIMEOUT_NS;
		nextActionNanos = System.nanoTime();
		shieldDone = false;
		placeAttempts = 0;
		shieldAttempts = 0;
		silentYaw = Float.NaN;
		silentPitch = Float.NaN;
		silentPacketAge = -1;
		silentApplied = false;
		shieldPos = null;
		anchorConfirmStartedNanos = 0L;
		shieldConfirmStartedNanos = 0L;
		chargeConfirmStartedNanos = 0L;
		detonateConfirmStartedNanos = 0L;
		aimReadyAge = -1;
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
		aimReadyAge = -1;
		shieldDone = false;
		placeAttempts = 0;
		shieldAttempts = 0;
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

	private record Placement(BlockPos support, Direction face, Vec3d point) {}
}
