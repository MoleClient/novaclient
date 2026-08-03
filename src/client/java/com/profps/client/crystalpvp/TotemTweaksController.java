package com.profps.client.crystalpvp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.security.SecureRandom;

/**
 * Auto Totem — keeps a totem in your offhand, and a spare in the hotbar.
 *
 * <p>This is a <em>maintenance</em> loop, not a reaction to one event. Every tick
 * it compares what you are holding against what you should be holding and fixes
 * the difference. A totem pop only makes it act sooner; it is never the thing
 * that decides whether the refill happens at all.
 *
 * <p>That distinction is the whole reliability story. The pop arrives as an
 * {@code EntityStatusS2CPacket}, which the server sends while it is handling the
 * damage — before the slot update that actually empties the offhand client-side.
 * Reacting to the pop and asking "is the offhand empty?" at that instant reads a
 * stack the server has already consumed, concludes there is nothing to do, and
 * gives up. Whether the slot update happened to land first decided whether the
 * refill happened, which is why the old one-shot version worked roughly half the
 * time. Re-checking every tick until the offhand genuinely holds a totem removes
 * the race entirely: no packet order can hide an empty offhand for long.
 *
 * <p>Sent moves are verified rather than assumed. {@code clickSlot} predicts its
 * own result locally, so a rejected swap looks successful until the server
 * reverts it; the watch outlives that revert and simply refills again.
 *
 * <p>Two refill styles, chosen by the <b>Open Inventory</b> toggle:
 * <ul>
 *   <li><b>Off (default) — silent swap.</b> Slips the totem into the offhand with
 *       a single {@code SWAP} slot-action on the always-live player handler, the
 *       same mechanic as vanilla's offhand-swap key. Nothing opens, so your own
 *       movement and clicks are never interrupted mid-fight.</li>
 *   <li><b>Open Inventory.</b> Opens the inventory and moves the totem the way you
 *       physically would. The screen is <em>guarded</em> — it swallows your clicks,
 *       drags, scroll and number keys so you cannot shuffle items while it works
 *       (Esc still closes it) — and it is held open for as few ticks as possible,
 *       because a screen blocks your input for as long as it is up.</li>
 * </ul>
 */
public final class TotemTweaksController {
	private static final int OFFHAND_SWAP_BUTTON = 40;  // vanilla SWAP button that targets the offhand
	private static final Item TOTEM = Items.TOTEM_OF_UNDYING;
	// How long a pop keeps the refill urgent. It has to outlast a server revert
	// of a rejected swap, or a rejected refill would never be retried.
	private static final long POP_WATCH_NANOS = 2_500_000_000L;
	// Spacing between slot actions, and the longer pause taken after a burst of
	// attempts has failed, so a server that refuses the move is never spammed.
	private static final long ACTION_GAP_NANOS = 90_000_000L;
	private static final long BACKOFF_NANOS = 900_000_000L;
	private static final int MAX_ATTEMPTS_PER_EPISODE = 4;
	// A screen blocks player input, so the open-inventory path is bounded hard.
	private static final long GUI_DEADLINE_NANOS = 900_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private long popWatchUntilNanos;
	private long nextActionNanos;
	private long guiDeadlineNanos;
	private boolean openedInventory;
	private int savedSelectedSlot = -1;
	private int attempts;
	private String status = "Idle";

	public TotemTweaksController(ProFPSConfig config) {
		this.config = config;
	}

	/**
	 * A totem was consumed. This only raises urgency: it clears the rate limiter
	 * and arms the watch. It deliberately does not inspect the offhand, because
	 * at this instant the consumed stack is usually still there client-side.
	 */
	public void markTotemPop(MinecraftClient client, Entity entity) {
		if (!config.totemTweaks || client == null || client.player == null || entity != client.player) return;
		if (client.player.isSpectator()) return;
		long now = System.nanoTime();
		popWatchUntilNanos = now + POP_WATCH_NANOS;
		// Keep a small non-fixed reaction window, but never the old fixed delay.
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
			// The pop has been answered. Anything left is ordinary upkeep.
			popWatchUntilNanos = 0L;
			attempts = 0;
		}

		if (now < nextActionNanos) return;
		// Never reach into the handler while a real container is open: the slot
		// indices would address that screen instead of the player's inventory.
		if (client.currentScreen != null) return;

		int source = offhandMissing ? findTotem(client, false) : -1;
		boolean wantsBackup = !offhandMissing && !hotbarHasTotem(client)
				&& findEmptyHotbar(client) >= 0 && findTotem(client, true) >= 0;
		if (source < 0 && !wantsBackup) {
			status = offhandMissing ? "No totem" : "Ready";
			return;
		}
		if (attempts >= MAX_ATTEMPTS_PER_EPISODE) {
			// The moves are being refused. Wait it out instead of flooding the
			// server with slot actions it is already rejecting.
			nextActionNanos = now + BACKOFF_NANOS;
			attempts = 0;
			status = "Retrying";
			return;
		}

		if (config.totemOpenInventory) {
			openInventory(client, now);
			return;
		}
		if (source >= 0) {
			click(client, invToScreen(source), OFFHAND_SWAP_BUTTON, SlotActionType.SWAP);
			attempts++;
			nextActionNanos = now + ACTION_GAP_NANOS;
			status = "Refilled";
			return;
		}
		int backup = findTotem(client, true);
		int emptyHotbar = findEmptyHotbar(client);
		if (backup >= 0 && emptyHotbar >= 0) {
			click(client, invToScreen(backup), emptyHotbar, SlotActionType.SWAP);
			nextActionNanos = now + ACTION_GAP_NANOS;
			status = "Backup";
		}
	}

	public String status(MinecraftClient client) {
		if (!config.totemTweaks) return "Off";
		if (client == null || client.player == null) return "Idle";
		return status;
	}

	// ── Open-inventory path ─────────────────────────────────────────────────

	private void openInventory(MinecraftClient client, long now) {
		savedSelectedSlot = client.player.getInventory().getSelectedSlot();
		client.setScreen(new TotemScreen(client.player));
		openedInventory = true;
		guiDeadlineNanos = now + GUI_DEADLINE_NANOS;
		status = "Opening";
		// The player inventory rides the always-live handler 0, so there is no
		// container-open acknowledgement to await; refill in this same tick.
		driveInventoryRefill(client, now);
	}

	/** One move per visit, re-deciding from live state, then closes. */
	private void driveInventoryRefill(MinecraftClient client, long now) {
		if (!openedInventory) return;
		if (now > guiDeadlineNanos) {
			closeInventory(client);
			nextActionNanos = now + ACTION_GAP_NANOS;
			return;
		}
		if (now < nextActionNanos) return;

		if (attempts < MAX_ATTEMPTS_PER_EPISODE && !client.player.getOffHandStack().isOf(TOTEM)) {
			int source = findTotem(client, false);
			if (source >= 0) {
				click(client, invToScreen(source), OFFHAND_SWAP_BUTTON, SlotActionType.SWAP);
				attempts++;
				nextActionNanos = now + ms(4D + rng.nextDouble() * 10D);
				status = "Refilling";
				return;
			}
		}
		if (!hotbarHasTotem(client)) {
			int emptyHotbar = findEmptyHotbar(client);
			int backup = findTotem(client, true);
			if (emptyHotbar >= 0 && backup >= 0) {
				click(client, invToScreen(backup), emptyHotbar, SlotActionType.SWAP);
				nextActionNanos = now + ms(5D + rng.nextDouble() * 11D);
				status = "Refilling";
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

	private void click(MinecraftClient client, int screenSlot, int button, SlotActionType type) {
		int syncId = client.player.currentScreenHandler.syncId; // TotemScreen or the live player handler (0)
		client.interactionManager.clickSlot(syncId, screenSlot, button, type, client.player);
	}

	private int findTotem(MinecraftClient client, boolean skipHotbar) {
		for (int slot = skipHotbar ? 9 : 0; slot < 36; slot++) {
			if (client.player.getInventory().getStack(slot).isOf(TOTEM)) return slot;
		}
		return -1;
	}

	private boolean hotbarHasTotem(MinecraftClient client) {
		for (int slot = 0; slot < 9; slot++) {
			if (client.player.getInventory().getStack(slot).isOf(TOTEM)) return true;
		}
		return false;
	}

	private int findEmptyHotbar(MinecraftClient client) {
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

	/** Drops any screen this controller opened and clears the episode. */
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
		guiDeadlineNanos = 0L;
		popWatchUntilNanos = 0L;
		nextActionNanos = 0L;
		attempts = 0;
		status = "Idle";
	}

	private long ms(double value) {
		return (long) (value * 1_000_000D);
	}

	/**
	 * The inventory screen the open-inventory mode uses to grab a totem. It swallows the player's
	 * own input — clicks, drags, scroll, hotbar/drop keys — so you can't accidentally shuffle
	 * items and corrupt your hotbar while it's working. The module's slot moves go straight
	 * through the network, so they're unaffected. Esc still lets you bail.
	 */
	private static final class TotemScreen extends InventoryScreen {
		private TotemScreen(PlayerEntity player) {
			super(player);
		}

		@Override
		public boolean mouseClicked(Click click, boolean doubled) {
			return true; // consume, do nothing
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
				return super.keyPressed(input); // let the player close it manually
			}
			return true; // block hotbar swaps, drop, the inventory key, everything else
		}
	}
}
