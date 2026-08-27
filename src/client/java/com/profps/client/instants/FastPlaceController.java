package com.profps.client.instants;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.GameMode;

/** Lowers vanilla's right-click reuse timer for the selected held-item family. */
public final class FastPlaceController {
	private final ProFPSConfig config;

	public FastPlaceController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!canRun(client) || !matchesHeldItem(client)) return;
		MinecraftClientInvoker minecraft = (MinecraftClientInvoker) client;
		int configuredDelay = Math.max(0, Math.min(4, config.instantFastPlaceDelay));
		if (minecraft.profps$getItemUseCooldown() > configuredDelay) {
			minecraft.profps$setItemUseCooldown(configuredDelay);
		}
	}

	private boolean matchesHeldItem(MinecraftClient client) {
		ItemStack main = client.player.getMainHandStack();
		ItemStack off = client.player.getOffHandStack();
		return switch (Math.max(0, Math.min(2, config.instantFastPlaceHeldItem))) {
			case 1 -> isBlock(main) || isBlock(off);
			case 2 -> isProjectile(main) || isProjectile(off);
			default -> true;
		};
	}

	private boolean isBlock(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
	}

	private boolean isProjectile(ItemStack stack) {
		return stack.isOf(Items.SNOWBALL) || stack.isOf(Items.EGG);
	}

	private boolean canRun(MinecraftClient client) {
		return config.enabled
				&& config.instantFastPlace
				&& client != null
				&& client.player != null
				&& client.world != null
				&& client.interactionManager != null
				&& client.currentScreen == null
				&& client.player.isAlive()
				&& client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}
}
