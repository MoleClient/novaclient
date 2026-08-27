package com.profps.client.instants;

import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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
import net.minecraft.util.math.MathHelper;

/**
 * Same-tick Breach mace handoff. The slot change and the attack are emitted in one dispatch:
 * the server drains packets before {@code LivingEntity#tick -> sendEquipmentChanges} swaps the
 * {@code ATTACK_SPEED} modifier, so the attack resolves with the sword's attack speed and the
 * mace's stack. The charge gate below is therefore the sword's.
 */
public final class AutoBreachSwapController {
	/** Vanilla requires strictly more than this for the crit flag and undiminished damage. */
	private static final float FULL_STRENGTH = 0.9F;

	private final ProFPSConfig config;
	private long disengageUntilNanos;
	private int phase; // 0 idle, 1 attack sent / restore pending
	private int readyAge;
	private int returnSlot = -1;
	private int maceSlot = -1;

	public AutoBreachSwapController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			reset();
			return;
		}

		// Restore on a later tick so the return slot change cannot overtake the attack packet.
		if (phase == 1) {
			CombatModeRuntime.markBreachSwapHold(true);
			if (player.age < readyAge) return;
			// Only restore when the mace is still selected, so manual scrolling wins.
			if (player.getInventory().getSelectedSlot() == maceSlot
					&& CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.BREACH_SWAP)) {
				select(client, player, returnSlot);
			}
			reset();
			return;
		}

		if (!ready(client)) {
			CombatModeRuntime.markBreachSwapHold(false);
			return;
		}
		CombatModeProfile.Breach tuning = CombatModePolicy.breach(config);
		long now = System.nanoTime();
		int swordSlot = player.getInventory().getSelectedSlot();
		int breachMace = findBreachMace(player);
		boolean armed = isSword(player.getMainHandStack().getItem())
				&& breachMace >= 0 && breachMace != swordSlot
				&& isCritFalling(player);
		CombatModeRuntime.markBreachSwapHold(armed);
		if (!armed || now < disengageUntilNanos) return;

		// The server divides by the sword's 12.5-tick recharge, so gate on the sword's bar.
		if (!fullStrength(player.getAttackCooldownProgress(0.5F), chargeThreshold(tuning))) return;

		PlayerEntity target = confirmedVanillaTarget(client, player);
		if (target == null || !CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.BREACH_SWAP)) return;

		// Set the slot locally only: attackEntity's own syncSelectedSlot emits the slot packet
		// immediately before the attack packet, so both land in one server tick.
		returnSlot = swordSlot;
		maceSlot = breachMace;
		player.getInventory().setSelectedSlot(maceSlot);
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		phase = 1;
		readyAge = player.age + 1;
		disengageUntilNanos = now + Math.max(0, tuning.disengageMs()) * 1_000_000L;
	}

	private boolean isCritFalling(ClientPlayerEntity player) {
		return player.fallDistance > 0.0F
				&& !player.isOnGround()
				&& player.getVelocity().y < 0.0D
				&& !player.isClimbing()
				&& !player.isTouchingWater()
				&& !player.hasBlindnessEffect()
				&& !player.hasVehicle()
				&& !player.isSprinting();
	}

	private float chargeThreshold(CombatModeProfile.Breach tuning) {
		return MathHelper.clamp(Math.max(90, tuning.chargePct()), 90, 100) / 100.0F;
	}

	private boolean fullStrength(float progress, float charge) {
		return progress > FULL_STRENGTH && progress >= charge;
	}

	private PlayerEntity confirmedVanillaTarget(MinecraftClient client, ClientPlayerEntity self) {
		PlayerEntity cached = vanillaPlayer(client.crosshairTarget, self);
		Entity camera = client.getCameraEntity();
		PlayerEntity fresh = vanillaPlayer(
				self.getCrosshairTarget(1.0F, camera == null ? self : camera), self);
		return cached != null && cached == fresh ? cached : null;
	}

	private PlayerEntity vanillaPlayer(HitResult hit, ClientPlayerEntity self) {
		if (!(hit instanceof EntityHitResult entityHit)
				|| !(entityHit.getEntity() instanceof PlayerEntity target)) return null;
		return target != self && target.isAlive() && target.getHealth() > 0.0F && !target.isSpectator()
				? target : null;
	}

	private int findBreachMace(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (isBreachMace(player.getInventory().getStack(slot))) return slot;
		}
		return -1;
	}

	private boolean isBreachMace(ItemStack stack) {
		if (!stack.isOf(Items.MACE)) return false;
		for (var enchantment : EnchantmentHelper.getEnchantments(stack).getEnchantments()) {
			if (enchantment.matchesKey(Enchantments.BREACH)) return true;
		}
		return false;
	}

	private boolean isSword(Item item) {
		return item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.COPPER_SWORD
				|| item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD
				|| item == Items.NETHERITE_SWORD;
	}

	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	private boolean ready(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.BREACH_SWAP)) return false;
		ClientPlayerEntity player = client.player;
		return config.enabled && player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && client.getOverlay() == null
				&& client.isWindowFocused() && player.isAlive() && !player.isSpectator();
	}

	private void reset() {
		phase = 0;
		readyAge = 0;
		returnSlot = -1;
		maceSlot = -1;
		CombatModeRuntime.markBreachSwapHold(false);
	}

	public boolean isBusy() {
		return phase != 0;
	}

	public boolean ownsActionThisTick() {
		return CombatModeRuntime.claimedBy() == CombatModeRuntime.ActionOwner.BREACH_SWAP;
	}
}
