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
 * Axe Crit — you jump onto somebody with an axe, this lands the swing on a
 * critical.
 *
 * <p>It does not jump for you and it does not touch your hotbar. The player
 * makes the play; this only gets the release right, because the release is the
 * hard part. Verified against 1.21.11's own {@code PlayerEntity#isCriticalHit}
 * and {@code #attack}, a crit needs every one of these at the instant the packet
 * is sent:
 *
 * <ul>
 *   <li>attack charge past 0.9 — below that the 1.5x is never applied at all,
 *       so an early click is not a weak crit, it is no crit;</li>
 *   <li>{@code fallDistance > 0} and not on the ground — the descent, not the
 *       jump, so the whole ascent is dead time;</li>
 *   <li>not climbing, not in water, no vehicle, no blindness;</li>
 *   <li>and <b>not sprinting</b>.</li>
 * </ul>
 *
 * <p>That last one is why hand-timed jump crits fail so often: sprinting cancels
 * the crit outright and silently, so a swing that looks perfect just pays normal
 * damage. Releasing it is handled the way a player does, by publishing the same
 * W-tap through the real input path ({@link #critSprintOverride}) rather than
 * flipping the sprint flag underneath the server — air control means the jump
 * still carries.
 *
 * <p>The click itself is vanilla's: the tick-current crosshair ray picks the
 * target at vanilla reach with vanilla occlusion, and the swing goes out through
 * the same interaction manager a real click uses. What is humanized is the
 * timing — the reaction to the window opening is sampled once per jump, so the
 * hits land scattered through the descent instead of on its first legal tick
 * every single time, which is the pattern that reads as a machine.
 */
public final class AxeCritController {
	/** Vanilla applies the 1.5x only past this charge; under it there is no crit to time. */
	private static final float CRIT_CHARGE = 0.9F;
	/** One crit per jump. A second swing in the same descent is not something a hand does. */
	private static final long RESWING_GAP_NANOS = 260_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private static AxeCritController instance;

	private long windowOpenedNanos;   // when this descent first became crit-legal
	private long reactionNanos;       // sampled once per window, not per tick
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

		// Landing ends the jump. Everything about this module is scoped to one
		// descent, so this is also what re-arms it for the next one.
		if (player.isOnGround()) {
			resetJump();
			status = "Grounded";
			return;
		}
		// The axe has to already be in your hand. Producing one for you is a
		// different module's job, and a weapon that appears mid-jump is a far
		// louder tell than any click timing.
		if (!isAxe(player.getMainHandStack().getItem())) {
			resetJump();
			status = "No axe";
			return;
		}
		// Vanilla's own ray, this tick: it carries vanilla reach and block
		// occlusion, so a hit it confirms is a hit the server will accept.
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
		// Sample the reaction once, when the window opens. Re-rolling it every
		// tick would pull every swing onto the earliest legal tick.
		if (windowOpenedNanos == 0L) {
			windowOpenedNanos = now;
			reactionNanos = (long) ((18.0D + rng.nextDouble() * 95.0D) * 1_000_000D);
		}
		if (swungThisJump || now - lastSwingNanos < RESWING_GAP_NANOS) return;

		// Sprinting cancels the crit. The W-tap that ends it is published through
		// the real input path, so this simply waits for that to take effect
		// rather than swinging into a hit that cannot crit.
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
		// attackEntity sends the packet but does not reset the local charge clock
		// the way MinecraftClient#doAttack does. Mirror vanilla or the next check
		// reads a full bar that the server has already spent.
		player.resetTicksSinceLastAttack();
		swungThisJump = true;
		lastSwingNanos = now;
		status = "Crit";
	}

	/**
	 * The W-tap that ends the sprint so the swing can actually crit.
	 *
	 * <p>Releasing forward is what a player does by hand, and it is the only
	 * thing that ends a sprint through vanilla's own path — the sprint flag is
	 * derived from input, so setting it directly describes a state the client
	 * could not have produced. Momentum and air control carry the jump through
	 * the tap, so the arc is unchanged.
	 *
	 * <p>Only ever published while airborne with a real target in front and an
	 * axe in hand: on the ground there is no crit to protect, and away from a
	 * fight this would be an unexplained stutter in ordinary movement.
	 */
	public static PlayerInput critSprintOverride(PlayerInput current) {
		AxeCritController controller = instance;
		if (controller == null || current == null || !controller.enabled()) return null;

		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isSprinting() || player.isOnGround()) return null;
		if (!controller.usable(client)) return null;
		if (!isAxe(player.getMainHandStack().getItem())) return null;
		// A manual retreat or sneak is the player's own intent and already ends
		// the sprint; never layer a tap on top of it.
		if (current.backward() || current.sneak() || !current.forward()) return null;
		// Deliberately airborne rather than the full crit position: the sprint has
		// to be gone BEFORE the descent starts. Waiting for fallDistance to climb
		// would spend the first ticks of the window dropping a sprint the swing
		// was already blocked on.
		if (player.isClimbing() || player.isTouchingWater() || player.isInLava()
				|| player.hasVehicle() || player.isGliding() || player.getAbilities().flying) {
			return null;
		}
		if (controller.crosshairPlayer(client, player) == null) return null;

		return new PlayerInput(false, false, current.left(), current.right(),
				current.jump(), false, false);
	}

	/**
	 * Everything vanilla asks of the attacker's own state for a crit, minus the
	 * charge and the sprint, which are handled where they can still be waited
	 * out. Mirrors {@code PlayerEntity#isCriticalHit} exactly.
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

	/** The player under the tick-current vanilla ray, or null. */
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
