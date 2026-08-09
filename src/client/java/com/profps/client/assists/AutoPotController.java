package com.profps.client.assists;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.inventory.InventoryItemScorer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Random;
import java.util.UUID;

/**
 * Auto Pot — the PvP splash-heal at real duel speed.
 *
 * <p>The motion is a BALLISTIC FLICK, not a tracking turn: a single eased
 * burst from the current orientation to the throw orientation over ~120-160ms
 * (the speed of a practiced pot flick), with a slight overshoot-and-settle at
 * the end the way a fast wrist actually lands. Applied per render frame and
 * GCD-quantized with carry, so on screen it is butter smooth and on the wire
 * every delta is a legitimate integer mouse count.
 *
 * <p>Sequence: health under 5 hearts with a splash heal on the hotbar → a short
 * flick STEEPLY DOWN, leaning a little away from the nearby enemy → hotbar
 * rolls to the pot mid-flick → one-tick use tap once the head has genuinely
 * reached the throw line → at ≤2.5 hearts a second pot follows after the
 * vanilla right-click cooldown with a small re-settle → flick back to the
 * original orientation (or live onto the enemy with "Flick To Player") while
 * the hotbar restores mid-turn. Whole single-pot cycle: ~350-450ms.
 *
 * <p>The motion is deliberately nearly all pitch. Vanilla throws a splash 20
 * degrees shallower than you are looking, at speed 0.5 under 0.05 gravity, so
 * from an ~83 degree look the potion lands about three quarters of a block in
 * front of your feet — and yaw only decides which way that three quarters of a
 * block points, against a splash radius of four. Turning further is a rounding
 * error in coverage and a real cost in travel, time and return distance, which
 * is why the sideways sweep this used to do is gone.
 *
 * <p>Two rules make it reliable next to an aim assist, which is what it shares
 * a duel with. It <b>owns the view</b> for the whole sequence — see
 * {@link #ownsRotation()} — because it used to run between the two aiming
 * blocks and the assist spent those same frames dragging the head back toward
 * the opponent. And the release is gated on {@link #onThrowLine} plus
 * {@link #safeToThrow}, the angle actually achieved, rather than on how long
 * the flick has been running: a clock says the wrist has finished, which is a
 * different claim from the head being pointed at the floor. Together those are
 * the difference between a pot at your feet and a pot at theirs. If the line is
 * never reached the flick times out and the pot stays in the bag, which in a
 * duel beats healing the person you are fighting.
 */
public final class AutoPotController {
	private enum Phase { IDLE, WINDUP, FLICK, REPOT, RETURN }

	private static final double ENEMY_RANGE = 12.0;

	/**
	 * How close the view has to have actually got to the throw line before the
	 * potion leaves the hand. The flick's own progress only says the wrist has
	 * finished moving; it says nothing about where the head ended up, which is
	 * the entire difference between a pot at your feet and a pot at theirs.
	 */
	private static final float YAW_TOLERANCE = 30.0F;
	private static final float PITCH_TOLERANCE = 8.0F;
	/**
	 * Below this the throw is no longer landing at your own feet, so it is not a
	 * heal, it is a gift. This is the ONLY safety condition, because it is the
	 * only one that does anything: vanilla throws a splash 20 degrees shallower
	 * than the look, so pitch alone decides whether the potion drops at your feet
	 * or sails off, and a steep throw lands under a block away whatever the yaw
	 * is doing.
	 */
	private static final float MIN_SAFE_PITCH = 62.0F;

	private final ProFPSConfig config;
	private final Random random = new Random();

	private float carryYaw;
	private float carryPitch;

	// ── Ballistic flick state (consumed per render frame) ──────────────────────
	private volatile boolean flickActive;
	private float flickStartYaw;
	private float flickStartPitch;
	private volatile float flickTargetYaw;
	private volatile float flickTargetPitch;
	private float flickProgress;
	private float flickDurationTicks;
	private float flickOvershoot;
	private boolean flickHold;
	private long lastFrameNanos;

	private Phase phase = Phase.IDLE;
	private int cooldownTicks;
	private int phaseTicks;
	private int windupTicks;
	private int slotSwitchTicks;
	private int repotTicks;
	private int restoreSlotTicks;
	private int potReadyAge;   // no use packet before the slot change has been sent
	private int originalSlot = -1;
	private float originalYaw;
	private float originalPitch;
	private float throwYaw;
	private float throwPitch;
	private int potsPlanned;
	private UUID enemyUuid;
	private boolean useTapped;

	public AutoPotController(ProFPSConfig config) {
		this.config = config;
	}

	/** Called every render frame: advances the ballistic flick, mouse-smooth. */
	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dtTicks = lastFrameNanos == 0L ? 1.0F : (float) ((now - lastFrameNanos) / 1_000_000_000.0 * 20.0);
		lastFrameNanos = now;
		ClientPlayerEntity player = client.player;
		if (!flickActive || player == null) return;

		flickProgress = Math.min(1.0F, flickProgress + MathHelper.clamp(dtTicks, 0.01F, 2.0F) / flickDurationTicks);
		float t = flickProgress;
		// Fast-out ease (a flick accelerates immediately and lands soft) with a
		// late overshoot bump that settles back to exact by the end.
		float eased = 1.0F - (float) Math.pow(1.0F - t, 2.4);
		float overshoot = t > 0.45F
				? (flickOvershoot - 1.0F) * (float) Math.sin((t - 0.45F) / 0.55F * Math.PI)
				: 0.0F;
		float k = eased + overshoot;

		float desiredYaw = flickStartYaw + MathHelper.wrapDegrees(flickTargetYaw - flickStartYaw) * k;
		float desiredPitch = flickStartPitch + (flickTargetPitch - flickStartPitch) * k;

		// Quantize the frame delta to the mouse GCD, carrying remainders so
		// the per-tick sums on the wire stay genuine integer mouse counts.
		float yawWanted = MathHelper.wrapDegrees(desiredYaw - player.getYaw()) + carryYaw;
		float pitchWanted = (desiredPitch - player.getPitch()) + carryPitch;
		float yawApplied = quantize(yawWanted);
		float pitchApplied = quantize(pitchWanted);
		carryYaw = yawWanted - yawApplied;
		carryPitch = pitchWanted - pitchApplied;
		if (yawApplied != 0.0F) player.setYaw(MathHelper.wrapDegrees(player.getYaw() + yawApplied));
		if (pitchApplied != 0.0F) player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -89.0F, 89.0F));

		// A throw flick keeps holding the line after it has landed, instead of
		// switching itself off the moment the animation finishes. Whatever nudges
		// the view in the last few frames before the release — the player's own
		// mouse, most often — is then pulled straight back out, rather than
		// leaving the pot to time out unthrown because the head drifted 3 degrees.
		if (flickProgress >= 1.0F && !flickHold) flickActive = false;
	}

	private float quantize(float delta) {
		return com.profps.client.aim.MouseGcd.quantize(delta); // player's real live mouse grid
	}

	private void startFlick(ClientPlayerEntity player, float targetYaw, float targetPitch,
			float durationTicks, boolean hold) {
		flickStartYaw = player.getYaw();
		flickStartPitch = player.getPitch();
		flickTargetYaw = targetYaw;
		flickTargetPitch = targetPitch;
		flickProgress = 0.0F;
		flickDurationTicks = durationTicks;
		flickOvershoot = 1.015F + random.nextFloat() * 0.05F;
		flickHold = hold;
		carryYaw = 0.0F;
		carryPitch = 0.0F;
		flickActive = true;
	}

	public void tick(MinecraftClient client) {
		// A throw is a one-tick tap, released first thing the following tick —
		// exactly the press profile of a real click.
		if (useTapped) {
			if (client.options != null) client.options.useKey.setPressed(false);
			useTapped = false;
		}
		if (!isReady(client)) {
			abort(client);
			return;
		}
		if (cooldownTicks > 0) cooldownTicks--;

		ClientPlayerEntity player = client.player;
		switch (phase) {
			case IDLE -> tryStart(client, player);
			case WINDUP -> tickWindup(player);
			case FLICK -> tickFlick(client, player);
			case REPOT -> tickRepot(client, player);
			case RETURN -> tickReturn(client, player);
		}
	}

	private void tryStart(MinecraftClient client, ClientPlayerEntity player) {
		if (cooldownTicks > 0) return;
		float triggerHealth = Math.max(1.0F, Math.min(20.0F, config.autoPotHealth));
		if (player.getHealth() <= 0.0F || player.getHealth() > triggerHealth) return;
		if (findHealingPotSlot(player) < 0) return;

		PlayerEntity enemy = nearestEnemy(client, player);
		enemyUuid = enemy == null ? null : enemy.getUuid();
		originalSlot = player.getInventory().getSelectedSlot();
		originalYaw = player.getYaw();
		originalPitch = player.getPitch();

		// The flick is almost entirely PITCH. Run the numbers and the yaw barely
		// matters: a splash leaves the hand 20 degrees shallower than you are
		// looking (vanilla throws it at pitch - 20) at speed 0.5 under 0.05
		// gravity, so from an ~83 degree look it lands about three quarters of a
		// block in front of your feet. Yaw only chooses which direction that
		// three quarters of a block points, against a splash radius of four. A
		// big turn buys a rounding error and costs the whole flick: more travel,
		// more time for something to contest the view, and a return turn just as
		// long. So nudge just far enough that the small offset leans away from
		// them, and spend the motion where it actually does something.
		if (enemy != null) {
			// Turn the short way, opposite to whichever side they are on. Always
			// a small flick, always leaning the splash away from them.
			float side = MathHelper.wrapDegrees(bearingTo(player, enemy) - originalYaw) >= 0.0F
					? -1.0F : 1.0F;
			throwYaw = MathHelper.wrapDegrees(
					originalYaw + side * (22.0F + random.nextFloat() * 16.0F));
		} else {
			// Nobody to lean away from, so there is nothing for a turn to
			// achieve. It used to swing 75-105 degrees to one side anyway, which
			// is most of a sideways flick spent on nothing at all.
			throwYaw = originalYaw;
		}
		// Steep, because the 20 degree shortfall above comes straight off this.
		throwPitch = 79.0F + random.nextFloat() * 9.0F;
		potsPlanned = config.autoPotMode == 0 && player.getHealth() <= triggerHealth * 0.5F
				&& countHealingPots(player) >= 2 ? 2 : 1;

		windupTicks = random.nextInt(2);     // 0-1 tick — duels don't wait
		slotSwitchTicks = random.nextInt(2); // pot in hand as the flick launches
		phase = Phase.WINDUP;
		phaseTicks = 0;
		if (windupTicks == 0) launchFlick(player);
	}

	private void launchFlick(ClientPlayerEntity player) {
		startFlick(player, throwYaw, throwPitch, 2.2F + random.nextFloat() * 1.0F, true); // 110-160ms down
		phase = Phase.FLICK;
		phaseTicks = 0;
	}

	private void tickWindup(ClientPlayerEntity player) {
		if (--windupTicks > 0) return;
		launchFlick(player);
	}

	private void tickFlick(MinecraftClient client, ClientPlayerEntity player) {
		phaseTicks++;

		// Hotbar rolls to the pot DURING the flick, not before or after.
		if (slotSwitchTicks >= 0 && slotSwitchTicks-- == 0) {
			int slot = findHealingPotSlot(player);
			if (slot < 0) {
				startReturn(player);
				return;
			}
			select(client, player, slot);
			// The use packet must not overtake the slot change: the server resolves
			// a use against the slot IT believes is held, so a same-tick pair throws
			// whatever was in the previous slot. One tick, exactly like the mace
			// handoff, and the ordering is guaranteed.
			potReadyAge = player.age + 1;
		}

		// Release on where the view actually IS, not on how long the wrist has
		// been moving. The flick clock said "close enough" even when another
		// aiming module had spent those frames dragging the head somewhere else
		// entirely, and that is what sent pots at the opponent.
		if (slotSwitchTicks < 0 && player.age >= potReadyAge
				&& isHealingPot(player.getMainHandStack())
				&& onThrowLine(player) && safeToThrow(client, player)) {
			throwPot(client);
			if (potsPlanned > 1) {
				// Vanilla right-click cooldown + a beat, with a small re-settle
				// so the second throw isn't pixel-identical.
				repotTicks = 4 + random.nextInt(2);
				throwYaw = MathHelper.wrapDegrees(throwYaw + (random.nextFloat() - 0.5F) * 9.0F);
				throwPitch = MathHelper.clamp(throwPitch + (random.nextFloat() - 0.5F) * 6.0F, 68.0F, 86.0F);
				startFlick(player, throwYaw, throwPitch, 1.4F + random.nextFloat() * 0.8F, true);
				phase = Phase.REPOT;
				phaseTicks = 0;
			} else {
				startReturn(player);
			}
			return;
		}
		// Timing out is now a real outcome rather than a fumble: the release waits
		// for the throw line, so this is what happens when the line is never
		// reached. Give it enough ticks that a settling head still makes it.
		if (phaseTicks > 12) startReturn(player);
	}

	private void tickRepot(MinecraftClient client, ClientPlayerEntity player) {
		phaseTicks++;
		if (repotTicks > 0) {
			repotTicks--;
			return;
		}
		int slot = findHealingPotSlot(player);
		if (slot < 0) {
			startReturn(player);
			return;
		}
		if (player.getInventory().getSelectedSlot() != slot) {
			select(client, player, slot);
			potReadyAge = player.age + 1;
			repotTicks = 1; // a beat for the switch, like a real wheel roll
			return;
		}
		if (isHealingPot(player.getMainHandStack()) && player.age >= potReadyAge
				&& onThrowLine(player) && safeToThrow(client, player)) {
			throwPot(client);
			startReturn(player);
		} else if (phaseTicks > 8) {
			startReturn(player);
		}
	}

	/**
	 * Whether the head has genuinely reached the throw line. This is the release
	 * authority: the flick animation is a comfort, this is the fact.
	 */
	private boolean onThrowLine(ClientPlayerEntity player) {
		return Math.abs(MathHelper.wrapDegrees(player.getYaw() - throwYaw)) <= YAW_TOLERANCE
				&& Math.abs(player.getPitch() - throwPitch) <= PITCH_TOLERANCE;
	}

	/**
	 * The last line of defence: never let a pot go anywhere but down. A steep
	 * throw lands at your own feet regardless of bearing, so pitch carries the
	 * whole guarantee. There used to be a bearing test beside it, and it was
	 * worse than useless — it protected nothing the pitch did not already cover,
	 * and because it read the opponent's bearing live, an opponent circling you
	 * could hold it false for the entire flick. The throw then timed out, the
	 * cycle retried, and the result was the endless flicking with nothing to
	 * show for it.
	 */
	private boolean safeToThrow(MinecraftClient client, ClientPlayerEntity player) {
		return player.getPitch() >= MIN_SAFE_PITCH;
	}

	private float bearingTo(ClientPlayerEntity player, PlayerEntity target) {
		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		return (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
	}

	/**
	 * Ordinary hotbar selection, told to the server immediately so it is ordered
	 * ahead of the use packet rather than left to vanilla's own sync, which runs
	 * in a later phase of the tick than the input handling that sends the throw.
	 */
	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (!PlayerInventory.isValidHotbarIndex(slot)
				|| player.getInventory().getSelectedSlot() == slot) {
			return;
		}
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(
				new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	/**
	 * True for the whole sequence, not just while the wrist is moving: between
	 * the flick landing and the throw leaving there is still a view that must not
	 * be dragged, and the return turn is just as much this module's motion as the
	 * flick out was.
	 */
	public boolean ownsRotation() {
		return phase != Phase.IDLE;
	}

	private void startReturn(ClientPlayerEntity player) {
		restoreSlotTicks = 1 + random.nextInt(2);
		flickHold = false;
		startFlick(player, originalYaw, originalPitch, 2.4F + random.nextFloat() * 1.0F, false);
		phase = Phase.RETURN;
		phaseTicks = 0;
	}

	private void tickReturn(MinecraftClient client, ClientPlayerEntity player) {
		phaseTicks++;

		// With the setting on, come back ONTO the nearby player instead of the
		// old orientation — live-tracked, like re-acquiring the fight.
		PlayerEntity enemy = config.autoPotFlickToPlayer ? enemyByUuid(client, player) : null;
		if (enemy != null) {
			Vec3d eye = player.getEyePos();
			Vec3d target = enemy.getBoundingBox().getCenter();
			double dx = target.x - eye.x;
			double dy = target.y - eye.y;
			double dz = target.z - eye.z;
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			flickTargetYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			flickTargetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		}

		// Hotbar returns to the original slot mid-turn.
		if (restoreSlotTicks > 0 && --restoreSlotTicks == 0 && originalSlot >= 0) {
			select(client, player, originalSlot);
		}

		if ((restoreSlotTicks == 0 && !flickActive) || phaseTicks > 12) {
			finish();
		}
	}

	private void finish() {
		phase = Phase.IDLE;
		flickActive = false;
		flickHold = false;
		originalSlot = -1;
		enemyUuid = null;
		cooldownTicks = 14 + random.nextInt(10); // ready again fast, never machine-gun
	}

	private void abort(MinecraftClient client) {
		if (phase == Phase.IDLE) return;
		if (client != null && client.player != null && client.interactionManager != null
				&& originalSlot >= 0) {
			select(client, client.player, originalSlot);
		}
		phase = Phase.IDLE;
		flickActive = false;
		flickHold = false;
		originalSlot = -1;
		enemyUuid = null;
		cooldownTicks = 10;
	}

	private void throwPot(MinecraftClient client) {
		client.options.useKey.setPressed(true);
		useTapped = true;
		flickHold = false;   // the line has served its purpose; let the flick end
	}

	private boolean isReady(MinecraftClient client) {
		if (!config.enabled || !config.autoPot) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null) return false;
		if (client.currentScreen != null) return false;
		if (!client.player.isAlive()) return false;
		return client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}

	private PlayerEntity nearestEnemy(MinecraftClient client, ClientPlayerEntity player) {
		PlayerEntity best = null;
		double bestSq = ENEMY_RANGE * ENEMY_RANGE;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == player || other.isSpectator() || !other.isAlive()) continue;
			double sq = other.squaredDistanceTo(player);
			if (sq < bestSq) {
				bestSq = sq;
				best = other;
			}
		}
		return best;
	}

	private PlayerEntity enemyByUuid(MinecraftClient client, ClientPlayerEntity player) {
		if (enemyUuid == null) return null;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (!enemyUuid.equals(other.getUuid())) continue;
			if (!other.isAlive() || other.isSpectator()) return null;
			// Bounded to the range they were acquired in, plus slack for the
			// ground they cover during the cycle. It used to allow forty blocks —
			// more than three times the acquisition range — so an opponent who
			// disengaged mid-pot had the "flick to player" return whip the head
			// across the map onto them, which is not re-acquiring a fight.
			double bound = ENEMY_RANGE * 1.5;
			return other.squaredDistanceTo(player) <= bound * bound ? other : null;
		}
		return null;
	}

	private int findHealingPotSlot(ClientPlayerEntity player) {
		java.util.ArrayList<Integer> candidates = new java.util.ArrayList<>();
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			if (isHealingPot(player.getInventory().getStack(slot))) candidates.add(slot);
		}
		if (candidates.isEmpty()) return -1;
		return config.autoPotRandom ? candidates.get(random.nextInt(candidates.size())) : candidates.get(0);
	}

	private int countHealingPots(ClientPlayerEntity player) {
		int count = 0;
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			if (isHealingPot(player.getInventory().getStack(slot))) count++;
		}
		return count;
	}

	private boolean isHealingPot(ItemStack stack) {
		boolean pots = config.autoPotType != 1;
		boolean soup = config.autoPotType != 0;
		return InventoryItemScorer.isHealing(stack, pots, soup);
	}
}
