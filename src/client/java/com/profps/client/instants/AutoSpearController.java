package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.aim.SilentAimController;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Auto Spear — keeps the kinetic charge running and puts the point on whoever you are
 * fighting, so the contact hit vanilla resolves for you has something to resolve against.
 *
 * <p>A spear is not a click weapon and this is the rule the module is built around. Every
 * tick the charge is held, {@code KineticWeaponComponent.usageTick} runs:
 *
 * <pre>
 *   held    = ticks the use has run;  inert while held &lt; delayTicks (8 netherite, 10 diamond)
 *   look    = getRotationVector()
 *   speed   = look · (yourMovement × 20)          blocks/second along the LOOK axis
 *   ray     = eye + look×2.0  →  eye + look×(4.5 + max(0, movement·look))
 *   for each player the ray pierces, outside its 10-tick contact cooldown:
 *       closing = max(0, speed − look · (theirMovement × 20))
 *       damage if closing ≥ 4.6
 * </pre>
 *
 * <p>Three things follow, and the module is those three things:
 * <ol>
 *   <li><b>The look vector is the weapon.</b> Contact is a ray along where the body points,
 *       not a swing at whatever is nearby — so aiming at them <em>is</em> the attack. That is
 *       why this now turns your head, frame by frame, exactly the way Auto Mace does.</li>
 *   <li><b>The charge has to already be running.</b> Arming costs 8–10 ticks. Started on
 *       arrival it is a hit that never happens, so the spear comes out and stays charged for
 *       the whole engagement rather than being produced at the moment of contact.</li>
 *   <li><b>Holding it is free.</b> A spear's {@code USE_EFFECTS} is
 *       {@code (canSprint = true, speedMultiplier = 1.0)}: the charge neither slows you nor
 *       stops you sprinting. There is therefore no cost to simply holding it while an
 *       opponent is close, and no reason to gate on anything cleverer.</li>
 * </ol>
 *
 * <p>That last point is what the previous version got wrong, and why it appeared to do
 * nothing. It refused to start until the player was already closing at 0.28 blocks/tick
 * <em>along the straight line to the target</em> — a bar a sprinting player only clears when
 * running exactly at somebody, and by the time that reads true the 8–10 tick arm delay has
 * already eaten the pass. It also never aimed at all, so even when it did charge, the ray it
 * was arming pointed wherever the player happened to be looking.
 *
 * <p>The one thing it will not do is move you. Closing speed is the player's to supply —
 * sprint, jump, swoop, or ride — and when a charged, aimed spear is sitting in contact range
 * and still short of the 4.6 blocks/second bar, the module says so instead of looking broken.
 */
public final class AutoSpearController {
	/** Hotbar slots to search, and the one search that has to stay cheap. */
	private static final int HOTBAR_SLOTS = 9;
	/** A target that ducks behind cover for a moment is still the fight you are in. */
	private static final int LOST_TARGET_GRACE_TICKS = 10;
	/** Re-check for a lapsed charge no more often than this. */
	private static final int REARM_INTERVAL_TICKS = 2;
	/** How long a charged spear may sit in contact range too slow before it is worth saying. */
	private static final int TOO_SLOW_NOTICE_TICKS = 20;
	/** Past this much of the contact band the turn stops being a sweep and becomes a flick. */
	private static final double URGENT_CONTACT_MARGIN = 2.0D;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd(); // shared rotation grid → valid deltas

	private UUID targetUuid;
	private double fx, fy, fz; // relative hitbox offsets (0..1), never dead centre
	private long nextRetargetNanos;
	private long lastFrameNanos;

	// Slow wandering aim bias — the drift that keeps a held aim off perfect centre.
	private double biasYaw, biasPitch, biasYawTarget, biasPitchTarget;
	private long nextBiasNanos;

	private boolean engaged;
	private boolean aiming;
	private boolean holdingUseKey;
	private boolean originalUseKey;
	private int returnSlot = -1;
	private int rearmAge;
	private int lostTargetTicks;
	private int tooSlowTicks;
	private boolean noticedThisEngagement;
	private String status = "Idle";

	public AutoSpearController(ProFPSConfig config) {
		this.config = config;
	}

	// ── Tick ──────────────────────────────────────────────────────────────────

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			disengage(client);
			return;
		}
		ClientPlayerEntity player = client.player;

		// Stay on the opponent already committed to. Re-running the selector every tick made
		// two nearby players trade ownership and restart the charge, and a restarted charge is
		// another 8–10 inert ticks.
		PlayerEntity target = byUuid(client, targetUuid);
		if (target != null && !trackable(player, target)) target = null;
		if (target == null) target = acquire(client, player);
		if (target == null) {
			if (engaged && ++lostTargetTicks <= LOST_TARGET_GRACE_TICKS) {
				sustain(client, player);
				return;
			}
			disengage(client);
			status = "No target";
			return;
		}
		lostTargetTicks = 0;
		long now = System.nanoTime();
		if (!target.getUuid().equals(targetUuid)) {
			targetUuid = target.getUuid();
			tooSlowTicks = 0;
			noticedThisEngagement = false;
			pickPoint();
			nextRetargetNanos = now + retargetDelayNanos();
		} else if (now >= nextRetargetNanos) {
			pickPoint();
			nextRetargetNanos = now + retargetDelayNanos();
		}

		// The spear is out for the approach, not produced on arrival.
		if (!isSpear(player.getMainHandStack())) {
			releaseUseKey(client);
			int slot = findSpear(player);
			if (slot < 0) {
				disengage(client);
				status = "No spear in the hotbar";
				return;
			}
			if (!config.autoSpearAutoSwitch) {
				status = "Hold a spear";
				return;
			}
			// Only the slot change is claimed. It is the part that genuinely collides — a
			// spear arriving mid Auto Mace stun-slam would swap the axe out from under it.
			// The charge itself is an ordinary use and is never worth starving over.
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_SPEAR)) return;
			if (returnSlot < 0) returnSlot = player.getInventory().getSelectedSlot();
			select(client, player, slot);
			return;
		}

		engaged = true;
		sustain(client, player);
		describe(client, player, target);
	}

	/**
	 * Keeps the charge alive. Vanilla ends a use the moment the key reads released, and the
	 * use also simply finishes on its own after {@code delayTicks + damage window} — so this
	 * both holds the key and restarts a charge that has lapsed.
	 */
	private void sustain(MinecraftClient client, ClientPlayerEntity player) {
		// Never hold the key, and never fire a use, for anything but a spear. This is the same
		// button that throws an ender pearl and eats a gap, and the grace window below reaches
		// here on a tick where the player may have scrolled off the spear themselves — so the
		// check belongs here, on the one path that can press it, rather than at each caller.
		if (!isSpear(player.getMainHandStack())) {
			releaseUseKey(client);
			return;
		}
		if (!holdingUseKey) {
			originalUseKey = client.options.useKey.isPressed();
			holdingUseKey = true;
		}
		client.options.useKey.setPressed(true);

		// An offhand shield, or whatever was mid-use before the hotbar swap, is not a spear
		// charge. Treating any active item as success is how the old version could stay
		// engaged indefinitely without ever arming the spear.
		if (chargingSpear(player)) return;
		if (player.isUsingItem()) client.interactionManager.stopUsingItem(player);
		if (player.age < rearmAge) return;
		rearmAge = player.age + REARM_INTERVAL_TICKS;
		// Started here rather than left to the key press, because this runs at the tail of
		// handleInputEvents, after vanilla has already read the key for this tick — a tick of
		// arm delay thrown away at exactly the moment it is least affordable.
		client.interactionManager.interactItem(player, Hand.MAIN_HAND);
	}

	/**
	 * Reports what the charge is actually doing against vanilla's own gates, and says the one
	 * thing the player has to fix. Everything here is read from the held stack rather than
	 * assumed, so a netherite spear is measured as a netherite spear.
	 */
	private void describe(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
		ItemStack spear = player.getMainHandStack();
		KineticWeaponComponent kinetic = spear.get(DataComponentTypes.KINETIC_WEAPON);
		int armTicks = kinetic == null ? SpearCombatPolicy.FALLBACK_ARM_TICKS : kinetic.delayTicks();
		int windowTicks = kinetic == null ? SpearCombatPolicy.FALLBACK_DAMAGE_WINDOW_TICKS
				: kinetic.damageConditions()
						.map(KineticWeaponComponent.Condition::maxDurationTicks)
						.orElse(SpearCombatPolicy.FALLBACK_DAMAGE_WINDOW_TICKS);
		double minClosing = kinetic == null ? SpearCombatPolicy.FALLBACK_MIN_CLOSING_SPEED
				: kinetic.damageConditions()
						.map(KineticWeaponComponent.Condition::minRelativeSpeed)
						.orElse(SpearCombatPolicy.FALLBACK_MIN_CLOSING_SPEED);

		int held = chargingSpear(player) ? player.getItemUseTime() : 0;
		if (!SpearCombatPolicy.armed(held, armTicks)) {
			status = "Arming " + (armTicks - held) + "t";
			tooSlowTicks = 0;
			return;
		}

		Vec3d look = player.getRotationVector();
		double closing = SpearCombatPolicy.closingSpeed(
				look.dotProduct(KineticWeaponComponent.getAmplifiedMovement(player)),
				look.dotProduct(KineticWeaponComponent.getAmplifiedMovement(target)));
		double distance = target.getBoundingBox().getCenter().distanceTo(player.getEyePos());
		double near = minRange(player);
		boolean inBand = SpearCombatPolicy.withinContactBand(distance, near, maxRange(player));
		boolean wouldDamage = SpearCombatPolicy.contactDamages(
				held - armTicks, windowTicks, closing, minClosing);

		if (!inBand && distance < near) {
			status = String.format("Too close · %.1fm", distance);
			tooSlowTicks = 0;
			return;
		}
		status = String.format("Charged · %.1fm · %.1f m/s", distance, closing);
		if (!inBand) {
			tooSlowTicks = 0;
			return;
		}

		// A charged, aimed spear sitting inside contact range and still under the bar is the
		// one failure that looks exactly like a broken module. Say it once per engagement.
		if (wouldDamage) {
			tooSlowTicks = 0;
			return;
		}
		if (++tooSlowTicks >= TOO_SLOW_NOTICE_TICKS && !noticedThisEngagement) {
			noticedThisEngagement = true;
			client.inGameHud.setOverlayMessage(Text.literal(String.format(
					"Auto Spear: in reach but closing at %.1f of %.1f m/s — sprint into them",
					closing, minClosing)), false);
		}
	}

	// ── Frame: the aim ────────────────────────────────────────────────────────

	/**
	 * Whips the real view onto the target every frame: a large fraction of the remaining
	 * error, capped per frame so it stays a smooth flick rather than a teleport, with tremor,
	 * an occasional overshoot and a slow wandering bias so it reads like a hand.
	 *
	 * @return true while this controller owns rotation, so nothing else drags the head off.
	 */
	public boolean frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0 * 20.0, 0.05, 4.0);
		lastFrameNanos = now;

		aiming = false;
		if (!engaged || targetUuid == null || !allowed(client)) return false;
		ClientPlayerEntity player = client.player;
		// Never turn on a tick that could not legally end in a contact hit: the ray is cast
		// from the held stack's own component, so with no spear in hand there is nothing to
		// aim. This was the other half of "aims but never hits".
		if (!isSpear(player.getMainHandStack())) return false;
		PlayerEntity target = byUuid(client, targetUuid);
		if (target == null || !trackable(player, target)) return false;

		// Silent aim is held by continuous request, so simply not asking on a frame where the
		// spear is not aiming is what hands the body back.
		if (config.autoSpearSilentAim) SilentAimController.instance().engage(player);

		updateBias(now, dt);
		Vec3d eye = player.getEyePos();
		Vec3d point = aimPoint(target);
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) + (float) biasYaw;
		float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal))) + (float) biasPitch;

		float yawErr = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchErr = MathHelper.wrapDegrees(desiredPitch - player.getPitch());

		// Inside the contact band the ray is about to be evaluated, so the turn has to finish
		// now; further out it eases on slowly, because a turn that closes a large angle in one
		// tick is a rotation-speed fingerprint regardless of how legitimate the hit is.
		double distance = target.getBoundingBox().getCenter().distanceTo(eye);
		boolean urgent = distance <= maxRange(player) + URGENT_CONTACT_MARGIN;
		float base = MathHelper.clamp(config.autoSpearTurnSpeed, 20, 90) / 100.0F;
		float speed = urgent ? Math.min(0.92F, base * 1.7F) : base;
		float k = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawStep = yawErr * k + (float) (rng.nextGaussian() * 0.30D);   // micro-tremor
		float pitchStep = pitchErr * k + (float) (rng.nextGaussian() * 0.22D);

		// A human snap blows slightly past on a big turn.
		if (Math.abs(yawErr) > 28.0F && rng.nextFloat() < 0.16F * dt) {
			yawStep += Math.signum(yawErr) * (2.0F + rng.nextFloat() * 4.0F);
		}

		// Per-frame rotation cap, then snapped to the player's own mouse grid so the packet
		// still looks like whole mouse counts at their sensitivity.
		float cap = speed * 100.0F * (urgent ? 0.6F : 0.5F) * dt;
		float yawApplied = mouse.yaw(MathHelper.clamp(yawStep, -cap, cap));
		float pitchApplied = mouse.pitch(MathHelper.clamp(pitchStep, -cap * 0.8F, cap * 0.8F));

		player.setYaw(player.getYaw() + yawApplied);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -90.0F, 90.0F));
		aiming = true;
		return true;
	}

	/** Re-roll the slow aim bias every so often — a drifting "not perfectly on" wander. */
	private void updateBias(long now, float dt) {
		if (now >= nextBiasNanos) {
			biasYawTarget = rng.nextGaussian() * 0.55D;
			biasPitchTarget = rng.nextGaussian() * 0.40D;
			nextBiasNanos = now + 180_000_000L + (long) (rng.nextDouble() * 320_000_000L);
		}
		double blend = 1.0D - Math.pow(0.86D, dt);
		biasYaw += (biasYawTarget - biasYaw) * blend;
		biasPitch += (biasPitchTarget - biasPitch) * blend;
	}

	/**
	 * A randomized point in the upper chest, plus a fraction of a tick of lead.
	 *
	 * <p>Height is not cosmetic here. Contact speed is measured along the look vector, so
	 * every degree of pitch spent aiming at their feet is speed subtracted from the closing
	 * bar the hit has to clear. Upper chest keeps the ray flat, which is both where a player
	 * aims and where the arithmetic is kindest.
	 */
	private Vec3d aimPoint(PlayerEntity target) {
		Box box = target.getBoundingBox();
		Vec3d point = new Vec3d(
				box.minX + (box.maxX - box.minX) * fx,
				box.minY + (box.maxY - box.minY) * fy,
				box.minZ + (box.maxZ - box.minZ) * fz);
		Vec3d velocity = target.getVelocity();
		Vec3d lead = new Vec3d(velocity.x, velocity.y * 0.45D, velocity.z).multiply(0.55D);
		double horizontal = Math.sqrt(lead.x * lead.x + lead.z * lead.z);
		if (horizontal > 0.18D) {
			double scale = 0.18D / horizontal;
			lead = new Vec3d(lead.x * scale, MathHelper.clamp(lead.y, -0.12D, 0.12D), lead.z * scale);
		}
		return point.add(lead);
	}

	private long retargetDelayNanos() {
		return 240_000_000L + (long) (rng.nextDouble() * 420_000_000L);
	}

	/** Upper-chest, off-centre hitbox fractions — never dead centre. */
	private void pickPoint() {
		fx = 0.32 + rng.nextDouble() * 0.36; // 0.32..0.68
		fy = 0.52 + rng.nextDouble() * 0.24; // upper chest, kept high so the ray stays flat
		fz = 0.32 + rng.nextDouble() * 0.36;
	}

	// ── Targeting ─────────────────────────────────────────────────────────────

	/** Nearest visible player inside the configured range and cone, with bounded stickiness. */
	private PlayerEntity acquire(MinecraftClient client, ClientPlayerEntity self) {
		double range = range();
		PlayerEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (!trackable(self, other)) continue;
			// A spear is a contact weapon, so nearest wins; the bonus only stops a marginally
			// closer newcomer from stealing an approach already under way.
			double distance = other.getBoundingBox().getCenter().distanceTo(self.getEyePos());
			double score = (range - distance) / range
					+ (other.getUuid().equals(targetUuid) ? 0.35D : 0.0D);
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	/** Configured acquisition cone plus real line of sight — the contact ray needs both. */
	private boolean trackable(ClientPlayerEntity self, PlayerEntity target) {
		if (target == self || !target.isAlive() || target.isSpectator()) return false;
		double range = range();
		Vec3d delta = target.getBoundingBox().getCenter().subtract(self.getEyePos());
		if (delta.lengthSquared() > range * range) return false;
		if (!self.canSee(target)) return false;
		if (delta.lengthSquared() < 1.0E-6D) return true;
		double minDot = Math.cos(Math.toRadians(MathHelper.clamp(config.autoSpearFov, 20, 140)));
		return delta.normalize().dotProduct(self.getRotationVec(1.0F)) >= minDot;
	}

	/**
	 * How early the spear comes out — not its reach. Contact resolves between 2 and ~4.5
	 * blocks whatever this is set to; the range only decides how much of the approach the
	 * charge gets to run for, and the charge needs 8–10 ticks of it to arm.
	 */
	private double range() {
		return MathHelper.clamp(config.autoSpearRange, 4, 64);
	}

	private PlayerEntity byUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null) return null;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (uuid.equals(other.getUuid())) return other;
		}
		return null;
	}

	// ── Spear handling ────────────────────────────────────────────────────────

	private boolean isSpear(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.contains(DataComponentTypes.PIERCING_WEAPON)
				&& stack.contains(DataComponentTypes.KINETIC_WEAPON);
	}

	private boolean chargingSpear(ClientPlayerEntity player) {
		return player.isUsingItem()
				&& player.getActiveHand() == Hand.MAIN_HAND
				&& isSpear(player.getActiveItem());
	}

	private int findSpear(ClientPlayerEntity player) {
		for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
			if (isSpear(player.getInventory().getStack(slot))) return slot;
		}
		return -1;
	}

	/** Where vanilla's contact ray starts and ends, read off the held stack. */
	private double minRange(ClientPlayerEntity player) {
		AttackRangeComponent range = player.getAttackRange();
		return range == null ? SpearCombatPolicy.MIN_JAB_REACH : range.getEffectiveMinRange(player);
	}

	private double maxRange(ClientPlayerEntity player) {
		AttackRangeComponent range = player.getAttackRange();
		if (range == null) return SpearCombatPolicy.MAX_JAB_REACH;
		// Vanilla extends the far end by the forward component of this tick's movement, which
		// is why a fast pass reaches slightly further than a standing one.
		double lunge = Math.max(0.0D,
				player.getMovement().dotProduct(player.getHeadRotationVector()));
		return range.getEffectiveMaxRange(player) + lunge;
	}

	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot >= HOTBAR_SLOTS || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	/** Gives the use key back to whatever the player had it doing before the charge. */
	private void releaseUseKey(MinecraftClient client) {
		if (!holdingUseKey || client == null || client.options == null) return;
		client.options.useKey.setPressed(originalUseKey);
		holdingUseKey = false;
	}

	/** Stops the charge, hands the use key back, and returns the slot that was borrowed. */
	private void disengage(MinecraftClient client) {
		if (client != null && client.player != null) {
			if (holdingUseKey && client.interactionManager != null
					&& chargingSpear(client.player)) {
				client.interactionManager.stopUsingItem(client.player);
			}
			releaseUseKey(client);
			// Deliberately outside the use-key branch. The spear is selected on one tick and
			// only charged on the next, so a module switched off in between had borrowed the
			// hotbar without ever taking the key — and the slot stayed borrowed forever.
			if (returnSlot >= 0 && config.autoSpearAutoSwitch
					&& isSpear(client.player.getMainHandStack())) {
				select(client, client.player, returnSlot);
			}
		}
		engaged = false;
		aiming = false;
		holdingUseKey = false;
		returnSlot = -1;
		rearmAge = 0;
		lostTargetTicks = 0;
		tooSlowTicks = 0;
		noticedThisEngagement = false;
		targetUuid = null;
		status = "Idle";
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.autoSpearEnabled) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null
				|| client.getOverlay() != null || !client.isWindowFocused()) return false;
		ClientPlayerEntity player = client.player;
		// Water caps you well under the closing bar, so a charge there is pure cost: it holds
		// the use key down and blocks every other right-click for a hit that cannot land.
		return player.isAlive() && !player.isSpectator() && !player.isTouchingWater();
	}

	public boolean ownsRotation() {
		return aiming;
	}

	public boolean isBusy() {
		return engaged;
	}

	/**
	 * What the charge is doing right now, including the reasons it is doing nothing — "Hold a
	 * spear", "Too close", or a live closing-speed readout. Gating this on {@code engaged}
	 * would hide exactly the states worth reading.
	 */
	public String status() {
		return status;
	}
}
