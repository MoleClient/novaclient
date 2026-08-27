package com.profps.client.instants;

import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.security.SecureRandom;

/**
 * Times an axe swing during a jump so it lands as a critical hit. Does not jump or swap slots.
 * Mirrors {@code PlayerEntity#isCriticalHit}: charge past 0.9, falling, not climbing, not in
 * water, no vehicle, no blindness, and not sprinting. Sprint is dropped via a W-tap published
 * through the real input path, see {@link #critSprintOverride}.
 */
public final class AxeCritController {
	/** Vanilla applies the 1.5x multiplier only past this charge. */
	private static final float CRIT_CHARGE = 0.9F;
	/** Minimum gap between swings, enforcing one crit per descent. */
	private static final long RESWING_GAP_NANOS = 260_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private static AxeCritController instance;

	private long windowOpenedNanos;   // when this descent first became crit-legal
	private long reactionNanos;       // sampled once per window
	private long lastSwingNanos;
	private boolean swungThisJump;
	private String status = "Idle";

	public AxeCritController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	public void tick(MinecraftClient client) {
		if (!enabled() || !usable(client)) {
			resetJump();
			status = enabled() ? "Idle" : "Off";
			return;
		}
		ClientPlayerEntity player = client.player;

		// Landing ends the jump and re-arms for the next one.
		if (player.isOnGround()) {
			resetJump();
			status = "Grounded";
			return;
		}
		if (!isAxe(player.getMainHandStack().getItem())) {
			resetJump();
			status = "No axe";
			return;
		}
		PlayerEntity target = crosshairPlayer(client, player);
		if (target == null) {
			status = "No target";
			return;
		}
		if (!critPosition(player)) {
			status = "Not falling";
			return;
		}

		long now = System.nanoTime();
		// Sample the reaction once when the window opens, not per tick.
		if (windowOpenedNanos == 0L) {
			windowOpenedNanos = now;
			reactionNanos = (long) ((18.0D + rng.nextDouble() * 95.0D) * 1_000_000D);
		}
		if (swungThisJump || now - lastSwingNanos < RESWING_GAP_NANOS) return;

		// Sprinting cancels the crit; wait for the W-tap to take effect.
		if (player.isSprinting()) {
			status = "Dropping sprint";
			return;
		}
		if (player.getAttackCooldownProgress(0.5F) <= CRIT_CHARGE) {
			status = "Charging";
			return;
		}
		if (now - windowOpenedNanos < reactionNanos) return;

		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AXE_CRIT)) return;
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		// attackEntity does not reset the local charge clock the way doAttack does.
		player.resetTicksSinceLastAttack();
		swungThisJump = true;
		lastSwingNanos = now;
		status = "Crit";
	}

	/**
	 * Releases forward input to end the sprint so the swing can crit. Returns null unless
	 * airborne with an axe in hand and a target in front.
	 */
	public static PlayerInput critSprintOverride(PlayerInput current) {
		AxeCritController controller = instance;
		if (controller == null || current == null || !controller.enabled()) return null;

		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isSprinting() || player.isOnGround()) return null;
		if (!controller.usable(client)) return null;
		if (!isAxe(player.getMainHandStack().getItem())) return null;
		if (current.backward() || current.sneak() || !current.forward()) return null;
		// Gated on airborne, not the full crit position: the sprint must be gone before
		// the descent starts, so this cannot wait on fallDistance.
		if (player.isClimbing() || player.isTouchingWater() || player.isInLava()
				|| player.hasVehicle() || player.isGliding() || player.getAbilities().flying) {
			return null;
		}
		if (controller.crosshairPlayer(client, player) == null) return null;

		return new PlayerInput(false, false, current.left(), current.right(),
				current.jump(), false, false);
	}

	/**
	 * Mirrors {@code PlayerEntity#isCriticalHit} minus the charge and sprint checks,
	 * which are handled separately.
	 */
	private boolean critPosition(ClientPlayerEntity player) {
		return player.fallDistance > 0.0D
				&& !player.isOnGround()
				&& !player.isClimbing()
				&& !player.isTouchingWater()
				&& !player.hasVehicle()
				&& !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
				&& !player.isGliding()
				&& !player.getAbilities().flying;
	}

	/** The player under the tick-current vanilla crosshair ray, or null. */
	private PlayerEntity crosshairPlayer(MinecraftClient client, ClientPlayerEntity self) {
		Entity camera = client.getCameraEntity();
		HitResult hit = self.getCrosshairTarget(1.0F, camera == null ? self : camera);
		if (!(hit instanceof EntityHitResult entityHit) || entityHit.getType() != HitResult.Type.ENTITY) {
			return null;
		}
		if (!(entityHit.getEntity() instanceof PlayerEntity target)) return null;
		return target != self && target.isAlive() && !target.isSpectator() ? target : null;
	}

	private boolean enabled() {
		return config.enabled && CombatModePolicy.enabled(config, CombatFeature.AXE_CRIT);
	}

	private boolean usable(MinecraftClient client) {
		return client != null && client.player != null && client.world != null
				&& client.interactionManager != null && client.currentScreen == null
				&& client.player.isAlive() && !client.player.isSpectator();
	}

	private void resetJump() {
		windowOpenedNanos = 0L;
		reactionNanos = 0L;
		swungThisJump = false;
	}

	private static boolean isAxe(Item item) {
		return item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.COPPER_AXE
				|| item == Items.IRON_AXE || item == Items.GOLDEN_AXE || item == Items.DIAMOND_AXE
				|| item == Items.NETHERITE_AXE;
	}

	public String status() {
		return status;
	}
}
