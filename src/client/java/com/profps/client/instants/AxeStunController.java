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
 * Shield breaker. Swaps to a hotbar axe and lands one hit on the crosshair-confirmed shielder,
 * then restores the previous slot or continues onto the configured sword follow-up. The slot
 * packet is always sent before the attack packet.
 */
public final class AxeStunController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	// 0 = idle, 1 = reaction pending, 2 = hit sent / waiting to swap back,
	// 3 = axe selected / waiting for that axe's real cooldown.
	private int phase;
	private long engageAtNanos;   // phase 1: when the reaction delay elapses
	private long returnAtNanos;   // phase 2: when to swap back
	private long cooldownUntil;   // rest period after a combo
	private int previousSlot = -1;
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
			// Restore the original item if disabled mid-combo while holding the axe.
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

		// Phase 2: the axe hit is out, pick the destination slot.
		if (phase == 2) {
			if (player.age < axeReadyAge || now < returnAtNanos) return;
			PlayerEntity hitTarget = byUuid(client, followupTarget);
			// Wait a few ticks for the server to publish the cleared shield, with a deadline.
			if (hitTarget != null && isHoldingShield(hitTarget)
					&& player.age < shieldConfirmDeadlineAge) return;

			int destination = config.axeStunRestorePrevious ? previousSlot : axeSlot;
			boolean axeMode = CombatModePolicy.mode(config) == CombatMode.AXE;
			if (axeMode && CombatModePolicy.enabled(config, CombatFeature.AXE_SWORD_FOLLOWUP)) {
				int sword = findBestSword(player);
				if (sword >= 0) destination = sword;
			}

			// A manual slot change during the return gap wins.
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

		// Phase 3: the axe is selected, so the cooldown read below is the axe's own.
		if (phase == 3) {
			if (player.getInventory().getSelectedSlot() != axeSlot) {
				resetCombo(); // manual scroll wins
				return;
			}
			PlayerEntity aimedTarget = freshPlayerTarget(client, player);
			if (aimedTarget == null || followupTarget == null
					|| !aimedTarget.getUuid().equals(followupTarget)) {
				// The axe selection belongs to one target; restore if the crosshair moves off it.
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

		// Phase 1: waiting out the reaction delay; abandon if the shielder leaves the crosshair.
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
			// The hotbar packet and the attack must not share a dispatch; wait one movement tick.
			axeReadyAge = player.age + 1;
			axeAttackDeadlineAge = player.age + 6;
			return;
		}

		// Phase 0: start the reaction delay.
		if (now < cooldownUntil || target == null || findAxe(player) < 0) return;
		// A crit window is only a few ticks wide, so shorten the delay while already falling.
		double reactionScale = isCritFalling(player) ? 0.25D : 1.0D;
		engageAtNanos = now + (long) (Math.max(0, tuning.stunReactionMs()) * reactionScale) * 1_000_000L
				+ (long) (rng.nextDouble() * Math.max(0, tuning.stunReactionJitterMs())
						* reactionScale * 1_000_000L);
		phase = 1;
	}

	/**
	 * Charge the hit must reach. The shield disable is a weapon component applied on any
	 * connecting hit, so it is not gated at full attack charge.
	 */
	private float requiredCharge(ClientPlayerEntity player, CombatModeProfile.Axe tuning) {
		return AxeStunPolicy.requiredCharge(
				tuning.minimumAttackChargePct(), isCritFalling(player));
	}

	/** Whether the player is inside the vanilla crit window. */
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

	/** The crosshair-confirmed player raising a shield, or null. */
	private PlayerEntity shieldTargetUnderCrosshair(MinecraftClient client, ClientPlayerEntity player) {
		// Airborne is allowed so the axe can crit, except while AutoMace owns the descent.
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
	 * True when the target is raising a shield. Blocking is driven by the {@code BLOCKS_ATTACKS}
	 * component, and {@code isBlocking()} only flips after the roughly 5-tick warmup, so an
	 * actively-used blocking item counts too.
	 */
	private boolean isHoldingShield(PlayerEntity target) {
		if (target == null) return false;
		if (target.isBlocking()) return true;
		return target.isUsingItem()
				&& target.getActiveItem().contains(DataComponentTypes.BLOCKS_ATTACKS);
	}

	/** First hotbar slot holding any axe, or -1. */
	private int findAxe(ClientPlayerEntity player) {
		for (int s = 0; s < 9; s++) {
			if (isAxe(player.getInventory().getStack(s).getItem())) return s;
		}
		return -1;
	}

	/** Hotbar slot with the highest-scoring sword, or -1. Material and enchantments both count. */
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

	private void attackSelectedAxe(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		player.resetTicksSinceLastAttack();
	}

	/**
	 * Sets the held slot locally and sends the packet immediately, before any attack this tick.
	 * No-op changes are skipped and vanilla's slot-sync is updated to avoid a duplicate packet.
	 */
	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

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
