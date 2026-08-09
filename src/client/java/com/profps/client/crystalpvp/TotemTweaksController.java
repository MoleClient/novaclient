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
 * still opens a real inventory, hovers the stored totem and swaps it into the
 * offhand — the same SWAP the swap-hands key performs inside a screen. There is
 * no setting for it: with a hotbar totem the screen is never reached, and without
 * one the only other outcome is doing nothing at all, which is not a choice worth
 * offering. It stops the sprint first, because that is what opening a screen does
 * to a real player, it waits before clicking, because no hand opens a screen and
 * reaches into it on the same tick, and it takes the shared combat action claim
 * so it can never emit a slot change in the same tick as another module.
 *
 * <p>Crucially the screen is only ever reached when the offhand is genuinely
 * empty of totems. Opening one to top up a spare — while you are already
 * protected — costs a sprint, a screen and a hotbar selection, and buys nothing
 * that the next pop could not buy for itself.
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

		// The offhand is already holding a totem, so there is nothing to refill.
		// This is where a "stage a spare into the hotbar" pass used to sit, and it
		// was worse than the problem it solved: a fighting loadout has no free
		// hotbar slot, so it opened a real inventory, found nowhere to put the
		// totem and closed again — every action gap, for as long as a spare sat in
		// the backpack. That loop stopped the sprint, spammed close-screen packets
		// and put the selected slot back each time, which is what left Auto Mace
		// unable to hold a mace long enough to ever swing it.
		status = "Ready";
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
		client.setScreen(new TotemScreen(client.player));
		openedInventory = true;
		guiDeadlineNanos = now + GUI_DEADLINE_NANOS;
		// Reaching for a stack takes a moment. Opening a screen and clicking
		// inside it on the same tick is not something a hand does, and this
		// module exists to avoid exactly that kind of contradiction.
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

		// Swap the stored totem straight into the offhand. Button 40 is the
		// offhand for a SWAP click — the same action vanilla runs when you hover a
		// stack in a screen and press the swap-hands key. Routing it through a
		// free hotbar slot instead needed one to exist, and a fighting loadout has
		// none, so that path arrived here with nowhere to put the totem and did
		// nothing but open and close the screen again.
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

	// Nothing in the refill changes which hotbar slot is held — the swap targets
	// the offhand directly — so nothing here may put a selected slot "back".
	// Doing that fought every other module for the hotbar: it reverted Auto Mace's
	// handoff on the tick after it was made, so the mace was never in hand when
	// the swing was due.
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
