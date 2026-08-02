package com.profps.client.instants;

import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatMode;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Axe Stun — a standalone ground shield-breaker. While you're aiming at a player who's
 * raising a shield and an axe is in your hotbar, it swiftly swaps to the axe and lands one hit
 * which disables their shield. Previous-slot restoration is optional; Axe mode can instead
 * continue onto its configured sword follow-up.
 *
 * It only fires when the shielder is genuinely your crosshair target (so you're already
 * looking at them — no rotation spoofing) and you're on the ground (normal ground combat,
 * not an aerial mace dive — that's AutoMace's job). The whole thing is humanized: a short
 * randomized reaction before the swap, a jittered ~1-tick gap before swapping back, and a
 * brief cooldown after, so it reads like a real hand flicking to an axe and back rather than
 * a robotic flicker. The slot packet is always sent before the attack packet so the server
 * sees us holding the axe the instant the hit resolves.
 */
public final class AxeStunController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	// 0 = idle, 1 = reaction pending, 2 = hit sent / waiting to swap back,
	// 3 = axe selected / waiting for that axe's real cooldown.
	private int phase;
	private long engageAtNanos;   // when the reaction delay elapses and we fire (phase 1)
	private long returnAtNanos;   // when we swap back to the original item (phase 2)
	private long cooldownUntil;   // brief rest after a combo before we'll engage again
	private int previousSlot = -1; // the hotbar slot we were on, to return to
	private int axeSlot = -1;
	private int axeReadyAge;
	private int axeAttackDeadlineAge;
	private int shieldConfirmDeadlineAge;
	private UUID followupTarget;

	public AxeStunController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!enabled(client)) {
			// Toggled off / left the world mid-combo while holding the axe — restore the original item.
			if ((phase == 2 || phase == 3) && client.player != null && previousSlot >= 0
					&& client.player.getInventory().getSelectedSlot() != previousSlot) {
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AXE_STUN)) return;
				selectSlot(client, client.player, previousSlot);
			}
			resetCombo();
			return;
		}
		ClientPlayerEntity player = client.player;
		long now = System.nanoTime();
		CombatModeProfile.Axe tuning = CombatModePolicy.axe(config);

		// Phase 2: the axe hit is out. Axe mode continues onto the best hotbar sword;
		// standalone restoration happens only when its explicit setting is enabled.
		if (phase == 2) {
			if (player.age < axeReadyAge || now < returnAtNanos) return;
			PlayerEntity hitTarget = byUuid(client, followupTarget);
			// Give the server several ordinary movement ticks to publish the shield
			// cooldown/cleared active item. The deadline prevents lag from wedging
			// the controller on the axe forever.
			if (hitTarget != null && isHoldingShield(hitTarget)
					&& player.age < shieldConfirmDeadlineAge) return;

			int destination = config.axeStunRestorePrevious ? previousSlot : axeSlot;
			boolean axeMode = CombatModePolicy.mode(config) == CombatMode.AXE;
			if (axeMode && CombatModePolicy.enabled(config, CombatFeature.AXE_SWORD_FOLLOWUP)) {
				int sword = findBestSword(player);
				if (sword >= 0) destination = sword;
			}

			// A manual slot change during the tiny return gap always wins. Do not fight the
			// player's hand or arm a trigger continuation for a slot we did not choose.
			int selected = player.getInventory().getSelectedSlot();
			if (selected != axeSlot) {
				finishCombo(now, tuning);
				return;
			}
			if (selected != destination) {
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AXE_STUN)) return;
				selectSlot(client, player, destination);
			}
			if (axeMode && isSword(player.getInventory().getStack(destination).getItem())) {
				CombatModeRuntime.armAxeFollowup(config, followupTarget);
			}
			finishCombo(now, tuning);
			return;
		}

		PlayerEntity target = shieldTargetUnderCrosshair(client, player);

		// The axe is already genuinely selected, so this cooldown is calculated from
		// the prospective weapon rather than whichever fast item was held beforehand.
		if (phase == 3) {
			if (player.getInventory().getSelectedSlot() != axeSlot) {
				resetCombo(); // manual scroll wins
				return;
			}
			// Once armed, retain the same ray-confirmed player even if the remote
			// "using item" flag flickers for a tick during shield warmup.
			PlayerEntity aimedTarget = freshPlayerTarget(client, player);
			if (aimedTarget == null || followupTarget == null
					|| !aimedTarget.getUuid().equals(followupTarget)) {
				// The axe selection belongs to one shield target. If the crosshair moves to
				// another shielder while charge fills, restore instead of attacking B and
				// accidentally arming the sword continuation for A.
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AXE_STUN)) return;
				selectSlot(client, player, previousSlot);
				resetCombo();
				return;
			}
			if (player.age < axeReadyAge) return;
			float cooldown = player.getAttackCooldownProgress(0.0F);
			if (!AxeStunPolicy.readyToHit(
					cooldown, requiredCharge(player, tuning),
					player.age, axeAttackDeadlineAge)) return;
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AXE_STUN)) return;
			attackSelectedAxe(client, player, aimedTarget);
			returnAtNanos = now + Math.max(0, tuning.switchToSwordMs()) * 1_000_000L
					+ (long) (rng.nextDouble() * Math.max(0, tuning.switchJitterMs()) * 1_000_000L);
			axeReadyAge = player.age + 1;
			shieldConfirmDeadlineAge = player.age + 4;
			phase = 2;
			return;
		}

		// Phase 1: we've spotted a shielder and are sitting out a human reaction delay. If they
		// drop the shield or leave the crosshair before it elapses, abandon the swap.
		if (phase == 1) {
			if (target == null) { resetCombo(); return; }
			if (now < engageAtNanos) return;
			int axe = findAxe(player);
			if (axe < 0) { resetCombo(); return; }
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AXE_STUN)) return;
			previousSlot = player.getInventory().getSelectedSlot();
			axeSlot = axe;
			followupTarget = target.getUuid();
			selectSlot(client, player, axe);
			phase = 3;
			// Never combine a hotbar packet and attack in the same dispatch. One
			// movement tick with the axe visibly selected keeps the sequence ordinary.
			axeReadyAge = player.age + 1;
			axeAttackDeadlineAge = player.age + 6;
			return;
		}

		// Phase 0 (idle): only engage when not resting, a shielder is under the crosshair, and we
		// actually carry an axe. Start the reaction delay; the swap fires next tick once it elapses.
		if (now < cooldownUntil || target == null || findAxe(player) < 0) return;
		// A crit window is a handful of ticks wide. Spending the usual reaction delay inside one
		// throws the crit away, so when you are already falling on them, commit almost immediately.
		double reactionScale = isCritFalling(player) ? 0.25D : 1.0D;
		engageAtNanos = now + (long) (Math.max(0, tuning.stunReactionMs()) * reactionScale) * 1_000_000L
				+ (long) (rng.nextDouble() * Math.max(0, tuning.stunReactionJitterMs())
						* reactionScale * 1_000_000L);
		phase = 1;
	}

	/**
	 * Charge this hit has to reach first. The old floor was 90%, and an axe needs about a second to
	 * get there — so the module stood holding an axe while the shield came back down, which is why
	 * it so rarely broke anything. The disable lands on any hit that connects, so the bar only has
	 * to be high enough for the hit to be worth taking, and lower still inside a crit window.
	 */
	private float requiredCharge(ClientPlayerEntity player, CombatModeProfile.Axe tuning) {
		// Shield disable duration is a WEAPON component on the connected axe hit;
		// it is not gated at 90% attack charge. Waiting near a full axe cooldown
		// routinely lets a raised shield disappear before the packet is sent.
		return AxeStunPolicy.requiredCharge(
				tuning.minimumAttackChargePct(), isCritFalling(player));
	}

	/** The vanilla crit window: falling after a jump, with nothing that cancels a crit. */
	private boolean isCritFalling(ClientPlayerEntity player) {
		return player.fallDistance > 0.0F
				&& !player.isOnGround()
				&& player.getVelocity().y < 0.0D
				&& !player.isClimbing()
				&& !player.isTouchingWater()
				&& !player.hasVehicle()
				&& !player.isSprinting();
	}

	private boolean enabled(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.AXE_STUN)) return false;
		ClientPlayerEntity player = client.player;
		return player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && player.isAlive() && !player.isSpectator();
	}

	/**
	 * The player you're aiming at if they're raising a shield and in normal ground combat —
	 * i.e. your crosshair entity is a living player who's blocking, both of you on the ground.
	 * Returns null otherwise. Crosshair-gated, so we never act unless you're already looking
	 * right at them.
	 */
	private PlayerEntity shieldTargetUnderCrosshair(MinecraftClient client, ClientPlayerEntity player) {
		// Deliberately NOT ground-only any more. Refusing to fire unless you were standing on the
		// ground meant the axe could never land as a crit — a crit is by definition mid-fall — so
		// the one hit this module exists to land was always the weakest version of itself. The one
		// case the old rule was right about survives: while AutoMace is live a descent is its smash
		// window, and the axe must not take the tick off it.
		if (player.hasVehicle() || player.isTouchingWater() || player.isClimbing()) return null;
		if (!player.isOnGround() && CombatModePolicy.enabled(config, CombatFeature.AUTO_MACE)) return null;
		PlayerEntity target = playerTargetUnderCrosshair(client, player);
		return isHoldingShield(target) ? target : null;
	}

	private PlayerEntity playerTargetUnderCrosshair(MinecraftClient client,
			ClientPlayerEntity player) {
		PlayerEntity target = vanillaPlayer(client.crosshairTarget, player);
		PlayerEntity current = freshPlayerTarget(client, player);
		return target != null && target == current ? target : null;
	}

	private PlayerEntity freshPlayerTarget(MinecraftClient client,
			ClientPlayerEntity player) {
		Entity camera = client.getCameraEntity();
		HitResult fresh = player.getCrosshairTarget(1.0F, camera == null ? player : camera);
		return vanillaPlayer(fresh, player);
	}

	private PlayerEntity vanillaPlayer(HitResult hit, ClientPlayerEntity self) {
		if (!(hit instanceof EntityHitResult ehr) || ehr.getType() != HitResult.Type.ENTITY) return null;
		if (!(ehr.getEntity() instanceof PlayerEntity target)) return null;
		return target != self && target.isAlive() && target.getHealth() > 0.0F && !target.isSpectator()
				? target : null;
	}

	/**
	 * True when the target is raising a shield. In 1.21.11 blocking is driven by the
	 * {@code BLOCKS_ATTACKS} data component, not the SHIELD item id, and {@code isBlocking()}
	 * only flips true AFTER the item's block-delay warmup (~5 ticks) — so we also catch that
	 * warmup (any actively-used item that can block attacks) to break it the instant it goes up.
	 */
	private boolean isHoldingShield(PlayerEntity target) {
		if (target == null) return false;
		if (target.isBlocking()) return true;
		return target.isUsingItem()
				&& target.getActiveItem().contains(DataComponentTypes.BLOCKS_ATTACKS);
	}

	/** First hotbar slot (0..8) holding any axe, or -1. */
	private int findAxe(ClientPlayerEntity player) {
		for (int s = 0; s < 9; s++) {
			if (isAxe(player.getInventory().getStack(s).getItem())) return s;
		}
		return -1;
	}

	/** Best practical PvP sword in the hotbar; material and damage enchantments both count. */
	private int findBestSword(ClientPlayerEntity player) {
		int bestSlot = -1;
		int bestScore = Integer.MIN_VALUE;
		for (int slot = 0; slot < 9; slot++) {
			int score = swordScore(player.getInventory().getStack(slot));
			if (score > bestScore) {
				bestScore = score;
				bestSlot = slot;
			}
		}
		return bestScore > 0 ? bestSlot : -1;
	}

	private int swordScore(ItemStack stack) {
		int score = swordScore(stack.getItem());
		if (score < 0) return score;
		var enchantments = EnchantmentHelper.getEnchantments(stack);
		for (var enchantment : enchantments.getEnchantments()) {
			int level = enchantments.getLevel(enchantment);
			if (enchantment.matchesKey(Enchantments.SHARPNESS)) score += level * 55;
			else if (enchantment.matchesKey(Enchantments.FIRE_ASPECT)) score += level * 18;
		}
		return score;
	}

	private int swordScore(Item item) {
		if (item == Items.NETHERITE_SWORD) return 700;
		if (item == Items.DIAMOND_SWORD) return 600;
		if (item == Items.IRON_SWORD) return 500;
		if (item == Items.COPPER_SWORD) return 450;
		if (item == Items.STONE_SWORD) return 400;
		if (item == Items.GOLDEN_SWORD) return 300;
		if (item == Items.WOODEN_SWORD) return 200;
		return -1;
	}

	private boolean isSword(Item item) {
		return swordScore(item) > 0;
	}

	private boolean isAxe(Item item) {
		return item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.COPPER_AXE
				|| item == Items.IRON_AXE || item == Items.GOLDEN_AXE || item == Items.DIAMOND_AXE
				|| item == Items.NETHERITE_AXE;
	}

	private PlayerEntity byUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null || client.world == null) return null;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (uuid.equals(player.getUuid())) return player;
		}
		return null;
	}

	/**
	 * Select {@code slot} and attack {@code target} in one shot, sending the slot-change packet
	 * BEFORE the attack packet so the server sees the new held item when the hit resolves.
	 */
	private void attackSelectedAxe(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		player.resetTicksSinceLastAttack();
	}

	/**
	 * Set the held hotbar slot locally and tell the server immediately (ordered before any attack
	 * this same tick). Skips a no-op change and keeps vanilla's slot-sync in step so it never fires
	 * a duplicate packet — both of which would otherwise flag as BadPacketsA.
	 */
	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	/** Read-only coordination state for diagnostics and future controller ordering. */
	public boolean isBusy() {
		return phase != 0;
	}

	public boolean ownsActionThisTick() {
		return CombatModeRuntime.claimedBy() == CombatModeRuntime.ActionOwner.AXE_STUN;
	}

	private void finishCombo(long now, CombatModeProfile.Axe tuning) {
		int min = Math.max(0, Math.min(tuning.restMinMs(), tuning.restMaxMs()));
		int max = Math.max(min, Math.max(tuning.restMinMs(), tuning.restMaxMs()));
		cooldownUntil = now + (min + (long) (rng.nextDouble() * (max - min))) * 1_000_000L;
		resetCombo();
	}

	private void resetCombo() {
		phase = 0;
		engageAtNanos = 0L;
		returnAtNanos = 0L;
		previousSlot = -1;
		axeSlot = -1;
		axeReadyAge = 0;
		axeAttackDeadlineAge = 0;
		shieldConfirmDeadlineAge = 0;
		followupTarget = null;
	}
}
