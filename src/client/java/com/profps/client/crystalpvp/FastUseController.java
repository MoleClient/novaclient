package com.profps.client.crystalpvp;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.GameMode;

import java.security.SecureRandom;

public final class FastUseController {
	private final ProFPSConfig config;
	private final AnchorMacroController anchorMacro;
	private final SecureRandom rng = new SecureRandom();

	private long nextRerollNanos;
	private int itemUseCap = 3;

	public FastUseController(ProFPSConfig config, AnchorMacroController anchorMacro) {
		this.config = config;
		this.anchorMacro = anchorMacro;
	}

	public void tick(MinecraftClient client) {
		if (!isAllowed(client)) {
			return;
		}
		if (!crystalPvpContext(client)) return;

		long now = System.nanoTime();
		if (now >= nextRerollNanos) {
			rerollCaps(now);
		}

		MinecraftClientInvoker mc = (MinecraftClientInvoker) client;
		if (mc.profps$getItemUseCooldown() > itemUseCap) {
			mc.profps$setItemUseCooldown(itemUseCap);
		}
	}

	public String status(MinecraftClient client) {
		if (!config.fastUse) return "Off";
		if (!isAllowed(client)) return "Idle";
		return "Level " + config.fastUseLevel;
	}

	private void rerollCaps(long now) {
		int level = Math.max(1, Math.min(10, config.fastUseLevel));
		double t = (level - 1) / 9.0D;
		// Never rewrite attack or mining cooldowns. Fast Use now accelerates only
		// right-click combat items, with a two-tick floor and a continuously
		// re-rolled cap so it cannot produce impossible zero-cooldown bursts.
		itemUseCap = randomCap(5, 2, t, 0.35D);
		nextRerollNanos = now + (long) ((95D + rng.nextDouble() * 105D) * 1_000_000D);
	}

	private int randomCap(int slowCap, int fastCap, double t, double jitterChance) {
		int cap = (int) Math.round(slowCap + (fastCap - slowCap) * t);
		if (rng.nextDouble() < jitterChance) {
			cap += rng.nextBoolean() ? 1 : -1;
		}
		return Math.max(fastCap, Math.min(slowCap, cap));
	}

	private boolean crystalPvpContext(MinecraftClient client) {
		// Anchor Macro owns its sequence and deliberately retains vanilla's use
		// cooldown. Fast Use must not shorten it, even if the physical key is held.
		boolean anchorSequenceActive = anchorMacro != null && anchorMacro.isSequencing();
		if (!canAccelerate(client.options.useKey.isPressed(), anchorSequenceActive)) return false;
		ItemStack main = client.player.getMainHandStack();
		ItemStack off = client.player.getOffHandStack();
		boolean combatItem = isCrystalPvpItem(main) || isCrystalPvpItem(off);
		return combatItem;
	}

	/** Pure policy helper pinned by tests: macros never impersonate held input. */
	static boolean canAccelerate(boolean physicalUsePressed, boolean anchorSequenceActive) {
		return physicalUsePressed && !anchorSequenceActive;
	}

	private boolean isCrystalPvpItem(ItemStack stack) {
		return stack.isOf(Items.END_CRYSTAL)
				|| stack.isOf(Items.RESPAWN_ANCHOR)
				|| stack.isOf(Items.GLOWSTONE);
	}

	private boolean isAllowed(MinecraftClient client) {
		if (!config.fastUse) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null) return false;
		if (client.currentScreen != null) return false;
		if (!client.player.isAlive()) return false;
		return client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}
}
