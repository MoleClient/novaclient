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
 * Auto Totem — keeps a totem in your offhand using only actions a vanilla client
 * can produce while you are moving and fighting.
 *
 * <p>The refill is the swap-hands key. Pressing F emits a
 * {@code PlayerActionC2SPacket} with {@code SWAP_ITEM_WITH_OFFHAND}, which the
 * server accepts with no screen open at all — it is how anybody swaps a totem in
 * mid-fight by hand. So a refill is three packets a client already sends
 * constantly: select the hotbar slot, swap hands, select back.
 *
 * <p>That matters because the obvious implementation is not legal. Moving an item
 * with {@code ClickSlotC2SPacket} is something a vanilla client only ever does
 * while an inventory screen is open, and opening that screen releases the
 * player's input: sprinting stops, the mouse drives a cursor instead of the view,
 * and attacking is impossible. A slot click arriving from someone who is
 * simultaneously sprinting, turning and swinging describes a state no real client
 * can be in, and that contradiction — not the speed — is what an anti-cheat has
 * to work with. Swapping hands introduces no such contradiction at any speed.
 *
 * <p>A totem stored deeper than the hotbar has no legal alternative, so that case
 * still opens a real inventory — but only to stage one totem up into the hotbar,
 * after which every refill is the swap again. There is no setting for it: with a
 * hotbar totem the screen is never reached, and without one the only other
 * outcome is doing nothing at all, which is not a choice worth offering. It stops
 * the sprint first, because that is what opening a screen does to a real player,
 * and it takes the shared combat action claim so it can never emit a slot change
 * in the same tick as another module.
 *
 * <p>The swap is not predicted locally — vanilla does not predict it either, the
 * server performs it and syncs back — so the offhand only shows the totem a
 * round trip later. Everything here therefore waits for that sync rather than
 * re-sending, which is what stops a burst of duplicate swaps.
 */
public final class TotemTweaksController {
	private static final int OFFHAND_SWAP_BUTTON = 40;  // vanilla SWAP button that targets the offhand
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
	private int savedSelectedSlot = -1;
	private int restoreSlot = -1;
	private int attempts;
	private String status = "Idle";

	public TotemTweaksController(ProFPSConfig config) {
		this.config = config;
	}

	/**
	 * A totem was consumed. Raises urgency only; it deliberately does not inspect
	 * the offhand, because at this instant the consumed stack is usually still
	 * there client-side — the status packet outruns the slot update.
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

		// Put the player's own slot back after a swap before doing anything else.
		if (restoreSlot >= 0 && now >= nextActionNanos) {
			int slot = restoreSlot;
			restoreSlot = -1;
			select(client, slot);
			nextActionNanos = now + ACTION_GAP_NANOS;
			return;
		}
		if (now < nextActionNanos || now < swapSettleNanos) return;
		// Never reach into a real container's handler, and never fight a screen
		// the player opened themselves.
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

		// Upkeep only: a spare in the hotbar so the next refill is the legal path.
		if (findTotem(client, 0, 9) >= 0) {
			status = "Ready";
			return;
		}
		if (findTotem(client, 9, 36) < 0) {
			status = "Ready";
			return;
		}
		openInventory(client, now);
	}

	public String status(MinecraftClient client) {
		if (!config.totemTweaks) return "Off";
		if (client == null || client.player == null) return "Idle";
		return status;
	}

	// ── Legal path: the swap-hands key ───────────────────────────────────────

	/**
	 * Select the totem's slot and press swap-hands, exactly as a player does.
	 * Both packets are ones a vanilla client emits while running around, so this
	 * carries no implied screen state and no impossible-input contradiction.
	 */
	private void swapHands(MinecraftClient client, int hotbarSlot, long now) {
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_TOTEM)) return;
		int previous = client.player.getInventory().getSelectedSlot();
		select(client, hotbarSlot);
		client.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
				PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
		// Vanilla does not predict this either; the server performs the swap and
		// syncs the slots back. Waiting for that is what stops a duplicate burst.
		swapSettleNanos = now + SWAP_SETTLE_NANOS;
		restoreSlot = previous == hotbarSlot ? -1 : previous;
		nextActionNanos = now + ACTION_GAP_NANOS;
		attempts++;
		status = "Refilled";
	}

	// ── Fallback: a real inventory, only for a totem outside the hotbar ──────

	private void openInventory(MinecraftClient client, long now) {
		// Opening a screen ends a sprint for a real player, so end it before the
		// screen exists. A slot click from someone still sprinting is the exact
		// contradiction this module has to avoid.
		if (client.player.isSprinting()) client.player.setSprinting(false);
		savedSelectedSlot = client.player.getInventory().getSelectedSlot();
		client.setScreen(new TotemScreen(client.player));
		openedInventory = true;
		guiDeadlineNanos = now + GUI_DEADLINE_NANOS;
		status = "Opening";
		driveInventoryRefill(client, now);
	}

	private void driveInventoryRefill(MinecraftClient client, long now) {
		if (!openedInventory) return;
		if (now > guiDeadlineNanos) {
			closeInventory(client);
			nextActionNanos = now + ACTION_GAP_NANOS;
			return;
		}
		if (now < nextActionNanos) return;
		if (client.player.isSprinting()) client.player.setSprinting(false);

		// Stage a totem into the hotbar. Once one is there the offhand refill can
		// use the swap-hands key, so the screen is only ever needed once.
		if (attempts < MAX_ATTEMPTS_PER_EPISODE && findTotem(client, 0, 9) < 0) {
			int source = findTotem(client, 9, 36);
			int destination = freeHotbarSlot(client);
			if (source >= 0 && destination >= 0
					&& CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_TOTEM)) {
				click(client, source, destination, SlotActionType.SWAP);
				attempts++;
				nextActionNanos = now + ms(4D + rng.nextDouble() * 10D);
				status = "Staging";
				return;
			}
		}
		closeInventory(client);
		nextActionNanos = now + ACTION_GAP_NANOS;
	}

	private void closeInventory(MinecraftClient client) {
		if (openedInventory && ourScreenOpen(client)) {
			client.player.closeHandledScreen();
			client.setScreen(null);
		}
		if (savedSelectedSlot >= 0 && savedSelectedSlot < 9) {
			client.player.getInventory().setSelectedSlot(savedSelectedSlot);
		}
		openedInventory = false;
		savedSelectedSlot = -1;
		guiDeadlineNanos = 0L;
		status = client.player.getOffHandStack().isOf(TOTEM) ? "Ready" : "Retrying";
	}

	private boolean ourScreenOpen(MinecraftClient client) {
		return client.currentScreen instanceof TotemScreen;
	}

	// ── Shared helpers ──────────────────────────────────────────────────────

	private void click(MinecraftClient client, int inventorySlot, int button, SlotActionType type) {
		int syncId = client.player.currentScreenHandler.syncId;
		client.interactionManager.clickSlot(syncId, invToScreen(inventorySlot), button, type, client.player);
	}

	/** Ordinary hotbar selection — the same packet scrolling the wheel produces. */
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

	private int freeHotbarSlot(MinecraftClient client) {
		for (int slot = 0; slot < 9; slot++) {
			if (client.player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	/** Inventory index → player-handler screen slot (hotbar 0-8 → 36-44; main 9-35 unchanged). */
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
			if (savedSelectedSlot >= 0 && savedSelectedSlot < 9) {
				client.player.getInventory().setSelectedSlot(savedSelectedSlot);
			}
		}
		openedInventory = false;
		savedSelectedSlot = -1;
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

	/**
	 * The screen the fallback uses. It swallows the player's own input so a stray
	 * click cannot shuffle items while it works; the module's own slot move goes
	 * through the network and is unaffected. Esc still closes it.
	 */
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
