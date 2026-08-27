package com.profps.client.crystalpvp;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

import java.security.SecureRandom;

/**
 * Keeps a totem in the offhand, refilling from the hotbar with a swap-hands action.
 * A totem outside the hotbar falls back to opening an inventory screen and swapping it in.
 */
public final class TotemTweaksController {
	private static final int OFFHAND_SWAP_BUTTON = 40;  // vanilla SWAP button index for the offhand
	private static final Item TOTEM = Items.TOTEM_OF_UNDYING;
	private static final long POP_WATCH_NANOS = 2_500_000_000L;
	/** Round trip to wait for the server's slot sync before judging a swap failed. */
	private static final long SWAP_SETTLE_NANOS = 260_000_000L;
	private static final long ACTION_GAP_NANOS = 90_000_000L;
	private static final long BACKOFF_NANOS = 900_000_000L;
	private static final int MAX_ATTEMPTS_PER_EPISODE = 4;
	private static final long GUI_DEADLINE_NANOS = 900_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private long popWatchUntilNanos;
	private long nextActionNanos;
	private long swapSettleNanos;
	private long guiDeadlineNanos;
	private boolean openedInventory;
	private int restoreSlot = -1;
	private int attempts;
	private String status = "Idle";

	public TotemTweaksController(ProFPSConfig config) {
		this.config = config;
	}

	/**
	 * Records a totem pop and shortens the next action delay.
	 * Does not inspect the offhand: the status packet arrives before the slot update.
	 */
	public void markTotemPop(MinecraftClient client, Entity entity) {
		if (!config.totemTweaks || client == null || client.player == null || entity != client.player) return;
		if (client.player.isSpectator()) return;
		long now = System.nanoTime();
		popWatchUntilNanos = now + POP_WATCH_NANOS;
		nextActionNanos = Math.min(nextActionNanos, now + ms(12D + rng.nextDouble() * 26D));
		attempts = 0;
		status = "Reacting";
	}

	public void tick(MinecraftClient client) {
		if (!config.totemTweaks) {
			abandon(client);
			status = "Off";
			return;
		}
		if (!isUsable(client)) {
			abandon(client);
			return;
		}

		long now = System.nanoTime();
		if (ourScreenOpen(client)) {
			driveInventoryRefill(client, now);
			return;
		}

		boolean offhandMissing = !client.player.getOffHandStack().isOf(TOTEM);
		if (!offhandMissing) {
			popWatchUntilNanos = 0L;
			attempts = 0;
		}

		// Restore the player's own slot after a swap before doing anything else.
		if (restoreSlot >= 0 && now >= nextActionNanos) {
			int slot = restoreSlot;
			restoreSlot = -1;
			select(client, slot);
			nextActionNanos = now + ACTION_GAP_NANOS;
			return;
		}
		if (now < nextActionNanos || now < swapSettleNanos) return;
		// Never act through another screen's handler.
		if (client.currentScreen != null) return;

		if (offhandMissing) {
			int hotbar = findTotem(client, 0, 9);
			if (hotbar >= 0) {
				swapHands(client, hotbar, now);
				return;
			}
			if (attempts >= MAX_ATTEMPTS_PER_EPISODE) {
				nextActionNanos = now + BACKOFF_NANOS;
				attempts = 0;
				status = "Retrying";
				return;
			}
			if (findTotem(client, 9, 36) < 0) {
				status = "No totem";
				return;
			}
			openInventory(client, now);
			return;
		}

		// Offhand already holds a totem; no spare is staged into the hotbar.
		status = "Ready";
	}

	public String status(MinecraftClient client) {
		if (!config.totemTweaks) return "Off";
		if (client == null || client.player == null) return "Idle";
		return status;
	}

	/** Selects the totem's hotbar slot and issues a swap-hands action. */
	private void swapHands(MinecraftClient client, int hotbarSlot, long now) {
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_TOTEM)) return;
		int previous = client.player.getInventory().getSelectedSlot();
		select(client, hotbarSlot);
		client.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
				PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
		// The swap is not predicted client-side; wait for the server's slot sync.
		swapSettleNanos = now + SWAP_SETTLE_NANOS;
		restoreSlot = previous == hotbarSlot ? -1 : previous;
		nextActionNanos = now + ACTION_GAP_NANOS;
		attempts++;
		status = "Refilled";
	}

	private void openInventory(MinecraftClient client, long now) {
		// Vanilla ends a sprint when a screen opens.
		if (client.player.isSprinting()) client.player.setSprinting(false);
		client.setScreen(new TotemScreen(client.player));
		openedInventory = true;
		guiDeadlineNanos = now + GUI_DEADLINE_NANOS;
		// Delay the first click so it does not land on the same tick the screen opens.
		nextActionNanos = now + ms(45D + rng.nextDouble() * 70D);
		status = "Opening";
	}

	private void driveInventoryRefill(MinecraftClient client, long now) {
		if (!openedInventory) return;
		if (now > guiDeadlineNanos) {
			closeInventory(client);
			nextActionNanos = now + BACKOFF_NANOS;
			return;
		}
		if (now < nextActionNanos) return;
		if (client.player.isSprinting()) client.player.setSprinting(false);

		// SWAP with button 40 moves the stored totem straight into the offhand.
		if (!client.player.getOffHandStack().isOf(TOTEM)) {
			int source = findTotem(client, 9, 36);
			if (source >= 0 && attempts < MAX_ATTEMPTS_PER_EPISODE
					&& CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_TOTEM)) {
				click(client, source, OFFHAND_SWAP_BUTTON, SlotActionType.SWAP);
				attempts++;
				nextActionNanos = now + ms(35D + rng.nextDouble() * 55D);
				status = "Refilling";
				return;
			}
		}
		closeInventory(client);
		nextActionNanos = now + ACTION_GAP_NANOS;
	}

	// The refill never changes the selected hotbar slot, so none is restored here.
	private void closeInventory(MinecraftClient client) {
		if (openedInventory && ourScreenOpen(client)) {
			client.player.closeHandledScreen();
			client.setScreen(null);
		}
		openedInventory = false;
		guiDeadlineNanos = 0L;
		status = client.player.getOffHandStack().isOf(TOTEM) ? "Ready" : "Retrying";
	}

	private boolean ourScreenOpen(MinecraftClient client) {
		return client.currentScreen instanceof TotemScreen;
	}

	private void click(MinecraftClient client, int inventorySlot, int button, SlotActionType type) {
		int syncId = client.player.currentScreenHandler.syncId;
		client.interactionManager.clickSlot(syncId, invToScreen(inventorySlot), button, type, client.player);
	}

	/** Selects a hotbar slot and sends the matching slot-update packet. */
	private void select(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) return;
		client.player.getInventory().setSelectedSlot(slot);
		client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	private int findTotem(MinecraftClient client, int from, int to) {
		for (int slot = from; slot < to; slot++) {
			if (client.player.getInventory().getStack(slot).isOf(TOTEM)) return slot;
		}
		return -1;
	}

	/** Maps an inventory index to a player-handler screen slot: hotbar 0-8 becomes 36-44, main 9-35 unchanged. */
	private int invToScreen(int inventorySlot) {
		return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
	}

	private boolean isUsable(MinecraftClient client) {
		return client != null && client.player != null && client.world != null
				&& client.interactionManager != null && client.player.isAlive()
				&& !client.player.isSpectator();
	}

	private void abandon(MinecraftClient client) {
		if (openedInventory && client != null && client.player != null
				&& client.currentScreen instanceof TotemScreen) {
			client.player.closeHandledScreen();
			client.setScreen(null);
		}
		openedInventory = false;
		restoreSlot = -1;
		guiDeadlineNanos = 0L;
		popWatchUntilNanos = 0L;
		nextActionNanos = 0L;
		swapSettleNanos = 0L;
		attempts = 0;
		status = "Idle";
	}

	private long ms(double value) {
		return (long) (value * 1_000_000D);
	}

	/** Inventory screen used by the fallback; swallows player input except Esc. */
	private static final class TotemScreen extends InventoryScreen {
		private TotemScreen(PlayerEntity player) {
			super(player);
		}

		@Override
		public boolean mouseClicked(Click click, boolean doubled) {
			return true;
		}

		@Override
		public boolean mouseReleased(Click click) {
			return true;
		}

		@Override
		public boolean mouseDragged(Click click, double offsetX, double offsetY) {
			return true;
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
			return true;
		}

		@Override
		public boolean charTyped(CharInput input) {
			return true;
		}

		@Override
		public boolean keyPressed(KeyInput input) {
			if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
				return super.keyPressed(input);
			}
			return true;
		}
	}
}
