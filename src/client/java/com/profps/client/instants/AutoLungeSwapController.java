package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/**
 * Attribute-swap form of a spear lunge. The server drains packets before
 * {@code LivingEntity#tick -> sendEquipmentChanges} applies a newly-held item's
 * {@code ATTACK_SPEED}, so an attack sent in the same tick as the slot change resolves with the
 * carry item's attack speed and the spear's stack. The charge gate is therefore the carry item's,
 * and the return swap waits a later tick so it cannot overtake the attack.
 */
public final class AutoLungeSwapController {
	/** Upper bound on a sequence, so a stalled phase cannot strand the spear in hand. */
	private static final int RESTORE_DEADLINE_TICKS = 34;
	/** Upper bound on waiting for lift-off. */
	private static final int LAUNCH_TIMEOUT_TICKS = 8;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private Phase phase = Phase.IDLE;
	private int spearSlot = -1;
	private int carrySlot = -1;
	private int returnSlot = -1;
	private int nextActionAge;
	private int deadlineAge;
	private int launchDeadlineAge;
	private long lastFrameNanos;
	private boolean ownsRotation;
	private String status = "Idle";

	// The player's own keys, restored when the burst ends.
	private boolean originalForward;
	private boolean originalSprint;
	private boolean originalJump;
	private boolean holdingInput;

	// Burst pacing scales with the observed keypress rate.
	private long lastPressNanos;
	private double pressRateHz;
	private boolean queuedRequest;

	public AutoLungeSwapController(ProFPSConfig config) {
		this.config = config;
	}

	/** Runs at vanilla's input phase, before this tick's movement packet. */
	public void tickPreMovement(MinecraftClient client) {
		if (config.autoLungeRequested) {
			config.autoLungeRequested = false;
			notePress();
			if (phase == Phase.IDLE && allowed(client)) begin(client);
			else queuedRequest = true;
		}

		if (phase == Phase.IDLE) {
			if (queuedRequest) {
				queuedRequest = false;
				if (allowed(client)) begin(client);
			}
			// Fall through so a queued press runs its first step in this tick.
			if (phase == Phase.IDLE) return;
		}
		if (!allowed(client)) {
			abort(client);
			return;
		}

		ClientPlayerEntity player = client.player;
		int age = player.age;
		if (age > deadlineAge) {
			abort(client);
			return;
		}

		switch (phase) {
			case PREPARE -> prepare(client, player, age);
			case LAUNCH -> launch(client, player, age);
			case FIRE -> fire(client, player, age);
			case RECOVER -> recover(client, player, age);
			case IDLE -> { }
		}
	}

	/**
	 * Sprint-jumps and waits for lift-off. On the ground vanilla scrubs horizontal velocity by
	 * block slipperiness each tick, so a lunge fired while standing is mostly lost.
	 */
	private void launch(MinecraftClient client, ClientPlayerEntity player, int age) {
		if (!player.isOnGround()) {
			applyMovement(client, player, false);
			if (age < nextActionAge) return;
			// Fire in this tick; every extra tick is airtime the burst loses.
			phase = Phase.FIRE;
			fire(client, player, age);
			return;
		}
		if (age > launchDeadlineAge) {
			// Blocked from jumping; lunge from the ground rather than dropping the press.
			phase = Phase.FIRE;
			fire(client, player, age);
			return;
		}
		applyMovement(client, player, true);
		status = "Launching";
		// One tick of airtime before the swap, occasionally two.
		nextActionAge = age + 1 + (humanize() && rng.nextInt(4) == 0 ? 1 : 0);
	}

	/** Presses forward, sprint, and optionally jump, saving the player's own key states first. */
	private void applyMovement(MinecraftClient client, ClientPlayerEntity player, boolean jump) {
		if (!config.lungeSwapJump) return;
		if (!holdingInput) {
			originalForward = client.options.forwardKey.isPressed();
			originalSprint = client.options.sprintKey.isPressed();
			originalJump = client.options.jumpKey.isPressed();
			holdingInput = true;
		}
		client.options.forwardKey.setPressed(true);
		client.options.sprintKey.setPressed(true);
		client.options.jumpKey.setPressed(jump);
		player.setSprinting(true);
	}

	private void restoreInput(MinecraftClient client) {
		if (!holdingInput || client == null || client.options == null) return;
		client.options.forwardKey.setPressed(originalForward);
		client.options.sprintKey.setPressed(originalSprint);
		client.options.jumpKey.setPressed(originalJump);
		holdingInput = false;
	}

	/**
	 * Puts a fast item in hand and waits for its charge bar to fill. A bare fist refills in
	 * about five ticks.
	 */
	private void prepare(MinecraftClient client, ClientPlayerEntity player, int age) {
		// Gate on the held item's bar, since that is what the server divides by. A bar that
		// already reads full needs no carry item at all.
		if (!SpearCombatPolicy.jabCharged(player.getAttackCooldownProgress(0.0F))) {
			if (carrySlot < 0) {
				notify(client, "Auto Lunge Swap: needs a free slot or a wind charge to swap from");
				abort(client);
				return;
			}
			if (player.getInventory().getSelectedSlot() != carrySlot) {
				if (returnSlot < 0) returnSlot = player.getInventory().getSelectedSlot();
				select(client, player, carrySlot);
			}
			// The attack-speed attribute still reads the previous item this tick.
			status = "Charging";
			return;
		}
		if (age < nextActionAge) return;

		// Whatever is in hand supplied the full bar, so that is the slot to return to.
		if (returnSlot < 0) returnSlot = player.getInventory().getSelectedSlot();

		launchDeadlineAge = age + LAUNCH_TIMEOUT_TICKS;
		nextActionAge = age;
		if (config.lungeSwapJump && player.isOnGround()) {
			phase = Phase.LAUNCH;
			launch(client, player, age);
		} else {
			phase = Phase.FIRE;
			fire(client, player, age);
		}
	}

	/**
	 * Selects the spear, emits the slot packet, then attacks, all in one dispatch so both
	 * packets land in the same server tick.
	 */
	private void fire(MinecraftClient client, ClientPlayerEntity player, int age) {
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_LUNGE)) return;
		ItemStack spear = player.getInventory().getStack(spearSlot);
		if (!isSpear(spear) || !hasLunge(spear)) {
			// The spear moved mid-sequence.
			abort(client);
			return;
		}

		// Fall back to the carry slot only when PREPARE did not record what was in hand.
		if (returnSlot < 0) returnSlot = carrySlot >= 0 ? carrySlot : player.getInventory().getSelectedSlot();
		select(client, player, spearSlot);
		boolean swung = ((MinecraftClientInvoker) client).invokeDoAttack();
		if (!swung) player.swingHand(Hand.MAIN_HAND);

		PlayerEntity target = nearestTarget(client, player);
		if (config.lungeSpearMace && target != null) {
			CombatModeRuntime.armSpearMace(target.getUuid(), 2_400L);
		}
		phase = Phase.RECOVER;
		// At least one tick, so the return slot change cannot overtake the attack.
		nextActionAge = age + 1 + (humanize() ? rng.nextInt(2) : 0);
		status = config.lungeSpearMace && target != null ? "Spear → Mace armed" : "Recovering";
	}

	/** Swaps back to the carry item so the next charge bar is the fast one. */
	private void recover(MinecraftClient client, ClientPlayerEntity player, int age) {
		// Keep holding forward mid-arc to retain air control.
		if (!player.isOnGround()) applyMovement(client, player, false);
		if (age < nextActionAge) return;
		// Only restore while our own spear is still selected, so manual scrolling wins.
		if (player.getInventory().getSelectedSlot() == spearSlot) {
			select(client, player, returnSlot);
		}
		restoreInput(client);
		boolean chain = queuedRequest && allowed(client);
		reset();
		if (chain) {
			queuedRequest = false;
			begin(client);
		}
	}

	private void begin(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!SpearCombatPolicy.canStartLunge(
				player.getHungerManager().getFoodLevel(),
				player.hasVehicle(),
				player.isGliding(),
				player.isTouchingWater())) {
			if (player.getHungerManager().getFoodLevel() < SpearCombatPolicy.MIN_LUNGE_FOOD) {
				notify(client, "Auto Lunge Swap: need at least 3 hunger bars");
				return;
			}
			notify(client, "Auto Lunge Swap: unavailable while riding, gliding, or in water");
			return;
		}
		spearSlot = findLungeSpear(player);
		if (spearSlot < 0) {
			notify(client, "Auto Lunge Swap: need a Lunge spear in the hotbar");
			return;
		}
		// Optional: a carry item is only needed to refill a spent bar.
		carrySlot = findCarrySlot(player, spearSlot);

		phase = Phase.PREPARE;
		nextActionAge = player.age + reactionTicks();
		deadlineAge = player.age + RESTORE_DEADLINE_TICKS;
		status = "Priming";
		// No first step here: the caller falls straight through to the phase
		// switch, so PREPARE already runs in this same tick.
	}

	/** No pre-delay; variation is sampled in the airtime and recovery gaps instead. */
	private int reactionTicks() {
		return 0;
	}

	private boolean humanize() {
		return config.lungeSwapHumanize;
	}

	/**
	 * Slot to swap out of and back into, preferred in order of charge refill speed: an empty
	 * slot, then a wind charge, then any non-weapon, non-tool item.
	 */
	private int findCarrySlot(ClientPlayerEntity player, int excludeSlot) {
		for (int slot = 0; slot < 9; slot++) {
			if (slot == excludeSlot) continue;
			if (player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		for (int slot = 0; slot < 9; slot++) {
			if (slot == excludeSlot) continue;
			if (player.getInventory().getStack(slot).isOf(Items.WIND_CHARGE)) return slot;
		}
		for (int slot = 0; slot < 9; slot++) {
			if (slot == excludeSlot) continue;
			ItemStack stack = player.getInventory().getStack(slot);
			if (!isSpear(stack) && !stack.contains(DataComponentTypes.TOOL)) return slot;
		}
		return -1;
	}

	/** Hotbar slot with the highest Lunge level, or -1. */
	private int findLungeSpear(ClientPlayerEntity player) {
		int best = -1;
		int bestLevel = 0;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!isSpear(stack)) continue;
			int level = lungeLevel(stack);
			if (level > bestLevel) {
				bestLevel = level;
				best = slot;
			}
		}
		return best;
	}

	private int lungeLevel(ItemStack stack) {
		if (stack.isEmpty() || !stack.hasEnchantments()) return 0;
		var enchantments = stack.getEnchantments();
		for (RegistryEntry<Enchantment> enchantment : enchantments.getEnchantments()) {
			if (enchantment.matchesKey(Enchantments.LUNGE)) return Math.max(1, enchantments.getLevel(enchantment));
		}
		return 0;
	}

	private boolean isSpear(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.contains(DataComponentTypes.PIERCING_WEAPON)
				&& stack.contains(DataComponentTypes.KINETIC_WEAPON);
	}

	private boolean hasLunge(ItemStack stack) {
		return lungeLevel(stack) > 0;
	}

	// ── Spam scaling ──────────────────────────────────────────────────────────

	private void notePress() {
		long now = System.nanoTime();
		if (lastPressNanos != 0L) {
			double gapSeconds = (now - lastPressNanos) / 1_000_000_000.0D;
			if (gapSeconds > 1.5D) {
				pressRateHz = 0.0D;
			} else {
				double instant = 1.0D / Math.max(0.03D, gapSeconds);
				pressRateHz = pressRateHz * 0.45D + instant * 0.55D;
			}
		}
		lastPressNanos = now;
	}

	private boolean spamming() {
		if (!config.lungeSpamScaling || lastPressNanos == 0L) return false;
		return (System.nanoTime() - lastPressNanos) < 1_500_000_000L && pressRateHz >= 4.0D;
	}

	/** Rotation hook kept for the shared frame chain. This controller never takes the camera. */
	public boolean frame(MinecraftClient client) {
		ownsRotation = false;
		return false;
	}

	/** Nearest living player, used to arm the optional spear-to-mace follow-up. */
	private PlayerEntity nearestTarget(MinecraftClient client, ClientPlayerEntity self) {
		PlayerEntity best = null;
		double bestSq = 36.0D;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator()) continue;
			double sq = self.squaredDistanceTo(other);
			if (sq < bestSq) {
				bestSq = sq;
				best = other;
			}
		}
		return best;
	}

	// ── Plumbing ──────────────────────────────────────────────────────────────

	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	public boolean isBusy() {
		return phase != Phase.IDLE;
	}

	public String status() {
		return phase == Phase.IDLE ? "Idle" : status;
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null
				|| client.getOverlay() != null || !client.isWindowFocused()) return false;
		return client.player.isAlive() && !client.player.isSpectator();
	}

	private void notify(MinecraftClient client, String message) {
		if (client.player != null) client.inGameHud.setOverlayMessage(Text.literal(message), false);
	}

	/** Cancels the sequence, restoring the carry item and the player's keys. */
	private void abort(MinecraftClient client) {
		if (client != null && client.player != null && returnSlot >= 0
				&& client.player.getInventory().getSelectedSlot() == spearSlot) {
			select(client, client.player, returnSlot);
		}
		restoreInput(client);
		reset();
	}

	private void reset() {
		phase = Phase.IDLE;
		spearSlot = -1;
		carrySlot = -1;
		returnSlot = -1;
		nextActionAge = 0;
		deadlineAge = 0;
		ownsRotation = false;
		status = "Idle";
		// pressRateHz is not cleared; it tracks the player's rhythm across bursts.
	}

	private enum Phase {
		IDLE,
		/** Carry item in hand, waiting for its charge bar to fill. */
		PREPARE,
		/** Sprint-jump, then wait for lift-off. */
		LAUNCH,
		/** One tick: slot packet then attack, same dispatch. */
		FIRE,
		/** Back to the carry item. */
		RECOVER
	}
}
