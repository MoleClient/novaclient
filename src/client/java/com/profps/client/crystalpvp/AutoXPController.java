package com.profps.client.crystalpvp;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;

import java.security.SecureRandom;

/** Throws experience bottles to repair damaged Mending armor, then restores the previous slot. */
public final class AutoXPController {
	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private int savedSlot = -1;
	private long nextThrowNanos;

	public AutoXPController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			reset(client);
			return;
		}
		ClientPlayerEntity player = client.player;
		if (!needsMending(player)) {
			reset(client);
			return;
		}
		int slot = bottleSlot(player);
		if (slot < 0) {
			reset(client);
			return;
		}

		long now = System.nanoTime();
		if (now < nextThrowNanos) return;

		if (savedSlot < 0) savedSlot = player.getInventory().getSelectedSlot();
		selectForUse(client, player, slot);
		if (!player.getMainHandStack().isOf(Items.EXPERIENCE_BOTTLE)) return;

		ActionResult result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		if (result instanceof ActionResult.Success success
				&& success.swingSource() == ActionResult.SwingSource.CLIENT) {
			player.swingHand(Hand.MAIN_HAND);
		}
		nextThrowNanos = now + throwDelayNanos();
	}

	/** True while any worn piece is damaged and has Mending. */
	private boolean needsMending(ClientPlayerEntity player) {
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = player.getEquippedStack(slot);
			if (stack.isEmpty() || !stack.isDamageable() || stack.getDamage() <= 0) continue;
			if (hasMending(stack)) return true;
		}
		return false;
	}

	private boolean hasMending(ItemStack stack) {
		for (var enchantment : EnchantmentHelper.getEnchantments(stack).getEnchantments()) {
			if (enchantment.matchesKey(Enchantments.MENDING)) return true;
		}
		return false;
	}

	private int bottleSlot(ClientPlayerEntity player) {
		int selected = player.getInventory().getSelectedSlot();
		if (player.getInventory().getStack(selected).isOf(Items.EXPERIENCE_BOTTLE)) return selected;
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isOf(Items.EXPERIENCE_BOTTLE)) return slot;
		}
		return -1;
	}

	/** Configured throw delay plus jitter, in nanoseconds. */
	private long throwDelayNanos() {
		int base = MathHelper.clamp(config.autoXpDelayMs, 0, 1000);
		int jitter = Math.max(1, base / 5);
		return (base + rng.nextInt(jitter + 1)) * 1_000_000L;
	}

	private void selectForUse(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	private boolean allowed(MinecraftClient client) {
		return config.enabled && config.autoXpEnabled && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.player.isAlive() && !client.player.isSpectator()
				&& client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}

	private void reset(MinecraftClient client) {
		if (savedSlot >= 0 && savedSlot < 9 && client != null && client.player != null
				&& client.interactionManager != null) {
			selectForUse(client, client.player, savedSlot);
		}
		savedSlot = -1;
		nextThrowNanos = 0L;
	}
}
