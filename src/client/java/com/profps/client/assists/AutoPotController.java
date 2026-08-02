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
 * <p>Sequence: health under 5 hearts with a splash heal on the hotbar →
 * instant flick 90° TO THE SIDE of the nearby enemy (whichever side is the
 * shorter head travel) pointing steeply down → hotbar rolls to the pot
 * mid-flick → one-tick use tap at ~80% of the flick (a human releases the
 * throw a hair before fully settling) → at ≤2.5 hearts a second pot follows
 * after the vanilla right-click cooldown with a small re-settle → flick back
 * to the original orientation (or live onto the enemy with "Flick To Player")
 * while the hotbar restores mid-turn. Whole single-pot cycle: ~350-450ms.
 */
public final class AutoPotController {
	private enum Phase { IDLE, WINDUP, FLICK, REPOT, RETURN }

	private static final double ENEMY_RANGE = 12.0;

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
	private long lastFrameNanos;

	private Phase phase = Phase.IDLE;
	private int cooldownTicks;
	private int phaseTicks;
	private int windupTicks;
	private int slotSwitchTicks;
	private int repotTicks;
	private int restoreSlotTicks;
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

		if (flickProgress >= 1.0F) flickActive = false;
	}

	private float quantize(float delta) {
		return com.profps.client.aim.MouseGcd.quantize(delta); // player's real live mouse grid
	}

	private void startFlick(ClientPlayerEntity player, float targetYaw, float targetPitch, float durationTicks) {
		flickStartYaw = player.getYaw();
		flickStartPitch = player.getPitch();
		flickTargetYaw = targetYaw;
		flickTargetPitch = targetPitch;
		flickProgress = 0.0F;
		flickDurationTicks = durationTicks;
		flickOvershoot = 1.015F + random.nextFloat() * 0.05F;
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

		// 90° to the SIDE of the enemy bearing — the splash still catches you
		// fully but lands clear of them. Flick whichever side is the shorter
		// head travel, like a hand taking the cheap path.
		if (enemy != null) {
			double dx = enemy.getX() - player.getX();
			double dz = enemy.getZ() - player.getZ();
			float toEnemy = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			float left = MathHelper.wrapDegrees(toEnemy - 90.0F + (random.nextFloat() - 0.5F) * 18.0F);
			float right = MathHelper.wrapDegrees(toEnemy + 90.0F + (random.nextFloat() - 0.5F) * 18.0F);
			float travelLeft = Math.abs(MathHelper.wrapDegrees(left - originalYaw));
			float travelRight = Math.abs(MathHelper.wrapDegrees(right - originalYaw));
			throwYaw = travelLeft <= travelRight ? left : right;
		} else {
			float side = random.nextBoolean() ? 1.0F : -1.0F;
			throwYaw = MathHelper.wrapDegrees(originalYaw + side * (75.0F + random.nextFloat() * 30.0F));
		}
		throwPitch = 74.0F + random.nextFloat() * 10.0F;
		potsPlanned = config.autoPotMode == 0 && player.getHealth() <= triggerHealth * 0.5F
				&& countHealingPots(player) >= 2 ? 2 : 1;

		windupTicks = random.nextInt(2);     // 0-1 tick — duels don't wait
		slotSwitchTicks = random.nextInt(2); // pot in hand as the flick launches
		phase = Phase.WINDUP;
		phaseTicks = 0;
		if (windupTicks == 0) launchFlick(player);
	}

	private void launchFlick(ClientPlayerEntity player) {
		startFlick(player, throwYaw, throwPitch, 2.2F + random.nextFloat() * 1.0F); // 110-160ms down
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
			player.getInventory().setSelectedSlot(slot);
		}

		// Release the throw at ~80% of the flick — a human lets go a hair
		// before the wrist fully settles.
		if (slotSwitchTicks < 0 && flickProgress >= 0.80F && isHealingPot(player.getMainHandStack())) {
			throwPot(client);
			if (potsPlanned > 1) {
				// Vanilla right-click cooldown + a beat, with a small re-settle
				// so the second throw isn't pixel-identical.
				repotTicks = 4 + random.nextInt(2);
				throwYaw = MathHelper.wrapDegrees(throwYaw + (random.nextFloat() - 0.5F) * 9.0F);
				throwPitch = MathHelper.clamp(throwPitch + (random.nextFloat() - 0.5F) * 6.0F, 68.0F, 86.0F);
				startFlick(player, throwYaw, throwPitch, 1.4F + random.nextFloat() * 0.8F);
				phase = Phase.REPOT;
				phaseTicks = 0;
			} else {
				startReturn(player);
			}
			return;
		}
		if (phaseTicks > 8) startReturn(player); // fumbled — bail like a human
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
			player.getInventory().setSelectedSlot(slot);
			repotTicks = 1; // a beat for the switch, like a real wheel roll
			return;
		}
		if (isHealingPot(player.getMainHandStack())) {
			throwPot(client);
			startReturn(player);
		} else if (phaseTicks > 8) {
			startReturn(player);
		}
	}

	private void startReturn(ClientPlayerEntity player) {
		restoreSlotTicks = 1 + random.nextInt(2);
		startFlick(player, originalYaw, originalPitch, 2.4F + random.nextFloat() * 1.0F);
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
		if (restoreSlotTicks > 0 && --restoreSlotTicks == 0
				&& originalSlot >= 0 && PlayerInventory.isValidHotbarIndex(originalSlot)) {
			player.getInventory().setSelectedSlot(originalSlot);
		}

		if ((restoreSlotTicks == 0 && !flickActive) || phaseTicks > 12) {
			finish();
		}
	}

	private void finish() {
		phase = Phase.IDLE;
		flickActive = false;
		originalSlot = -1;
		enemyUuid = null;
		cooldownTicks = 14 + random.nextInt(10); // ready again fast, never machine-gun
	}

	private void abort(MinecraftClient client) {
		if (phase == Phase.IDLE) return;
		if (client != null && client.player != null && originalSlot >= 0
				&& PlayerInventory.isValidHotbarIndex(originalSlot)) {
			client.player.getInventory().setSelectedSlot(originalSlot);
		}
		phase = Phase.IDLE;
		flickActive = false;
		originalSlot = -1;
		enemyUuid = null;
		cooldownTicks = 10;
	}

	private void throwPot(MinecraftClient client) {
		client.options.useKey.setPressed(true);
		useTapped = true;
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
			return other.squaredDistanceTo(player) <= 40.0 * 40.0 ? other : null;
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
