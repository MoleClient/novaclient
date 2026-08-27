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
 * Throws splash healing potions at the player's own feet when health drops below the trigger.
 * The view flicks steeply down, the hotbar rolls to the potion mid-flick, and the throw is
 * released only once {@link #onThrowLine} and {@link #safeToThrow} both hold.
 */
public final class AutoPotController {
	private enum Phase { IDLE, WINDUP, FLICK, REPOT, RETURN }

	private static final double ENEMY_RANGE = 12.0;

	/** How close the view must be to the throw line before the potion is released. */
	private static final float YAW_TOLERANCE = 30.0F;
	private static final float PITCH_TOLERANCE = 8.0F;
	/** Vanilla throws a splash 20 degrees shallower than the look, so pitch alone decides the landing. */
	private static final float MIN_SAFE_PITCH = 62.0F;

	private final ProFPSConfig config;
	private final Random random = new Random();

	private float carryYaw;
	private float carryPitch;

	// Flick state, consumed per render frame.
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
	private int potReadyAge;   // no use packet until the slot change has been sent
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

	/** Advances the flick; called every render frame. */
	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dtTicks = lastFrameNanos == 0L ? 1.0F : (float) ((now - lastFrameNanos) / 1_000_000_000.0 * 20.0);
		lastFrameNanos = now;
		ClientPlayerEntity player = client.player;
		if (!flickActive || player == null) return;

		flickProgress = Math.min(1.0F, flickProgress + MathHelper.clamp(dtTicks, 0.01F, 2.0F) / flickDurationTicks);
		float t = flickProgress;
		// Fast-out ease with a late overshoot bump that settles back to exact by the end.
		float eased = 1.0F - (float) Math.pow(1.0F - t, 2.4);
		float overshoot = t > 0.45F
				? (flickOvershoot - 1.0F) * (float) Math.sin((t - 0.45F) / 0.55F * Math.PI)
				: 0.0F;
		float k = eased + overshoot;

		float desiredYaw = flickStartYaw + MathHelper.wrapDegrees(flickTargetYaw - flickStartYaw) * k;
		float desiredPitch = flickStartPitch + (flickTargetPitch - flickStartPitch) * k;

		// Quantize the frame delta to the mouse GCD, carrying remainders across frames.
		float yawWanted = MathHelper.wrapDegrees(desiredYaw - player.getYaw()) + carryYaw;
		float pitchWanted = (desiredPitch - player.getPitch()) + carryPitch;
		float yawApplied = quantize(yawWanted);
		float pitchApplied = quantize(pitchWanted);
		carryYaw = yawWanted - yawApplied;
		carryPitch = pitchWanted - pitchApplied;
		if (yawApplied != 0.0F) player.setYaw(MathHelper.wrapDegrees(player.getYaw() + yawApplied));
		if (pitchApplied != 0.0F) player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -89.0F, 89.0F));

		// A holding flick keeps correcting toward the line after it lands.
		if (flickProgress >= 1.0F && !flickHold) flickActive = false;
	}

	private float quantize(float delta) {
		return com.profps.client.aim.MouseGcd.quantize(delta);
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
		// A throw is a one-tick tap, released at the start of the following tick.
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

		// Mostly pitch: from a steep look the splash lands under a block away whatever the yaw.
		if (enemy != null) {
			// Small turn the short way, leaning the splash away from the enemy.
			float side = MathHelper.wrapDegrees(bearingTo(player, enemy) - originalYaw) >= 0.0F
					? -1.0F : 1.0F;
			throwYaw = MathHelper.wrapDegrees(
					originalYaw + side * (22.0F + random.nextFloat() * 16.0F));
		} else {
			throwYaw = originalYaw;
		}
		// Steep, to absorb vanilla's 20 degree throw shortfall.
		throwPitch = 79.0F + random.nextFloat() * 9.0F;
		potsPlanned = config.autoPotMode == 0 && player.getHealth() <= triggerHealth * 0.5F
				&& countHealingPots(player) >= 2 ? 2 : 1;

		windupTicks = random.nextInt(2);
		slotSwitchTicks = random.nextInt(2); // pot in hand as the flick launches
		phase = Phase.WINDUP;
		phaseTicks = 0;
		if (windupTicks == 0) launchFlick(player);
	}

	private void launchFlick(ClientPlayerEntity player) {
		startFlick(player, throwYaw, throwPitch, 2.2F + random.nextFloat() * 1.0F, true);
		phase = Phase.FLICK;
		phaseTicks = 0;
	}

	private void tickWindup(ClientPlayerEntity player) {
		if (--windupTicks > 0) return;
		launchFlick(player);
	}

	private void tickFlick(MinecraftClient client, ClientPlayerEntity player) {
		phaseTicks++;

		// Hotbar rolls to the pot during the flick.
		if (slotSwitchTicks >= 0 && slotSwitchTicks-- == 0) {
			int slot = findHealingPotSlot(player);
			if (slot < 0) {
				startReturn(player);
				return;
			}
			select(client, player, slot);
			// The use packet must not overtake the slot change; the server resolves it
			// against the slot it believes is held.
			potReadyAge = player.age + 1;
		}

		// Release on the achieved angle, not on flick progress.
		if (slotSwitchTicks < 0 && player.age >= potReadyAge
				&& isHealingPot(player.getMainHandStack())
				&& onThrowLine(player) && safeToThrow(client, player)) {
			throwPot(client);
			if (potsPlanned > 1) {
				// Vanilla right-click cooldown plus a beat, then a small re-settle.
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
		// Give up if the throw line is never reached.
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
			repotTicks = 1; // a beat for the switch to land
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

	/** Whether the view has reached the throw orientation within tolerance. */
	private boolean onThrowLine(ClientPlayerEntity player) {
		return Math.abs(MathHelper.wrapDegrees(player.getYaw() - throwYaw)) <= YAW_TOLERANCE
				&& Math.abs(player.getPitch() - throwPitch) <= PITCH_TOLERANCE;
	}

	/** Pitch alone decides whether the potion lands at the player's feet. */
	private boolean safeToThrow(MinecraftClient client, ClientPlayerEntity player) {
		return player.getPitch() >= MIN_SAFE_PITCH;
	}

	private float bearingTo(ClientPlayerEntity player, PlayerEntity target) {
		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		return (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
	}

	/** Selects a hotbar slot and sends the slot packet immediately, ahead of any use packet. */
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

	/** True for the whole sequence; other aim modules must not move the view while it holds. */
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

		// Flick To Player returns onto the tracked enemy instead of the original orientation.
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
		cooldownTicks = 14 + random.nextInt(10);
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
		flickHold = false;   // stop holding the throw line
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
			// Bounded to the acquisition range plus slack for movement during the cycle.
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
