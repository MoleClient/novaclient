package com.profps.client.instants;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * BreakOn and AutoTool. BreakOn swaps to the best hotbar tool and holds attack on the
 * crosshair block; AutoTool only swaps while the player is mining manually.
 */
public final class ToolMineController {
	private final ProFPSConfig config;
	private boolean miningHeld;
	private int originalSlot = -1;
	private int pendingSlot = -1;
	private long pendingSinceNanos;
	private long swapBackSinceNanos;
	private boolean swapped;

	public ToolMineController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		boolean breakOn = config.instantBreakOn;
		boolean autoTool = config.instantAutoTool;
		if (!config.enabled || (!breakOn && !autoTool)) {
			reset(client, true);
			return;
		}
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null
				|| client.currentScreen != null || !player.isAlive() || player.isSpectator()) {
			reset(client, true);
			return;
		}

		BlockState state = null;
		if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = hit.getBlockPos();
			BlockState looked = client.world.getBlockState(pos);
			if (!looked.isAir()) state = looked;
		}

		int bestTool = state == null ? -1 : bestToolSlot(player, state);
		boolean allowed = state != null && (!config.instantBreakOnCertain || isSelectedBlock(state));

		// BreakOn swaps immediately since it owns the mining action; AutoTool uses delayed state.
		if (breakOn && allowed && bestTool >= 0
				&& bestTool != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(bestTool);
		}
		if (autoTool) tickAutoTool(client, player);
		else clearAutoToolState(client, true);

		boolean wantMine = breakOn && allowed;
		if (wantMine && (bestTool >= 0 || config.instantBreakOnHandUse)) {
			client.options.attackKey.setPressed(true);
			miningHeld = true;
		} else {
			release(client);
		}
	}

	private boolean isSelectedBlock(BlockState state) {
		return config.instantBreakOnBlocks.contains(Registries.BLOCK.getId(state.getBlock()).toString());
	}

	private void release(MinecraftClient client) {
		if (miningHeld && client.options != null) {
			client.options.attackKey.setPressed(false);
			miningHeld = false;
		}
	}

	private void tickAutoTool(MinecraftClient client, ClientPlayerEntity player) {
		boolean mouseDown = client.options.attackKey.isPressed();
		int wantedSlot = hoveredSlot(client, player);
		if (config.instantAutoToolRequireMouseDown && !mouseDown) wantedSlot = -1;
		if (config.instantAutoToolOnlySneaking && !player.isSneaking()) wantedSlot = -1;

		long now = System.nanoTime();
		if (wantedSlot < 0) {
			pendingSlot = -1;
			pendingSinceNanos = 0L;
			if (swapped && config.instantAutoToolSwapBack && originalSlot >= 0) {
				if (swapBackSinceNanos == 0L) swapBackSinceNanos = now;
				if (elapsedMillis(now, swapBackSinceNanos) >= config.instantAutoToolSwapBackDelayMs) {
					restoreOriginal(player);
				}
			} else if (!config.instantAutoToolSwapBack) {
				clearAutoToolState(client, false);
			}
			return;
		}

		swapBackSinceNanos = 0L;
		int selected = player.getInventory().getSelectedSlot();
		if (wantedSlot == selected) {
			pendingSlot = -1;
			pendingSinceNanos = 0L;
			return;
		}

		boolean weaponTarget = client.crosshairTarget instanceof EntityHitResult;
		boolean instant = weaponTarget && config.instantAutoToolSwapWeapon
				&& config.instantAutoToolInstantWeapon;
		if (pendingSlot != wantedSlot) {
			pendingSlot = wantedSlot;
			pendingSinceNanos = now;
		}
		if (!instant && elapsedMillis(now, pendingSinceNanos) < config.instantAutoToolSwapToDelayMs) return;

		if (!swapped) originalSlot = selected;
		player.getInventory().setSelectedSlot(wantedSlot);
		swapped = true;
		pendingSlot = -1;
		pendingSinceNanos = 0L;
	}

	private int hoveredSlot(MinecraftClient client, ClientPlayerEntity player) {
		if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = client.world.getBlockState(hit.getBlockPos());
			return state.isAir() ? -1 : bestToolSlot(player, state);
		}
		if (client.crosshairTarget instanceof EntityHitResult && config.instantAutoToolSwapWeapon) {
			return bestWeaponSlot(player);
		}
		return -1;
	}

	private int bestWeaponSlot(ClientPlayerEntity player) {
		int best = -1;
		double bestDamage = 1.0D;
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isEmpty()) continue;
			AttributeModifiersComponent modifiers = stack.getOrDefault(
					DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
			double damage = modifiers.applyOperations(EntityAttributes.ATTACK_DAMAGE, 1.0D, EquipmentSlot.MAINHAND);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = slot;
			}
		}
		return best;
	}

	private long elapsedMillis(long now, long since) {
		return Math.max(0L, (now - since) / 1_000_000L);
	}

	private void restoreOriginal(ClientPlayerEntity player) {
		if (PlayerInventory.isValidHotbarIndex(originalSlot)) {
			player.getInventory().setSelectedSlot(originalSlot);
		}
		clearAutoToolState(null, false);
	}

	private void clearAutoToolState(MinecraftClient client, boolean restore) {
		if (restore && swapped && client != null && client.player != null
				&& PlayerInventory.isValidHotbarIndex(originalSlot)) {
			client.player.getInventory().setSelectedSlot(originalSlot);
		}
		originalSlot = -1;
		pendingSlot = -1;
		pendingSinceNanos = 0L;
		swapBackSinceNanos = 0L;
		swapped = false;
	}

	private void reset(MinecraftClient client, boolean restore) {
		release(client);
		clearAutoToolState(client, restore);
	}

	/** Hotbar slot that mines this block fastest, or -1 when nothing beats bare hands. */
	private int bestToolSlot(ClientPlayerEntity player, BlockState state) {
		int best = -1;
		float bestSpeed = 1.01F; // hands and blocks sit at 1.0
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isEmpty()) continue;
			float speed = stack.getMiningSpeedMultiplier(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = slot;
			}
		}
		return best;
	}
}
