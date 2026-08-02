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
 * Auto Totem — after your totem pops, rapidly refills a totem into your offhand (and, if there's room,
 * a hotbar backup).
 *
 * <p>Two refill styles, chosen by the <b>Open Inventory</b> toggle (on by default):
 * <ul>
 *   <li><b>Open Inventory (default).</b> Opens your inventory and moves the totem the way you
 *       physically would. The screen it opens is <em>guarded</em> — it swallows your own
 *       clicks/drags/scroll/number-keys so you can't shuffle items and corrupt your hotbar while
 *       it's working (Esc still closes it), and your selected slot is snapshotted and restored.</li>
 *   <li><b>Off — silent swap.</b> Never opens a screen; slips the totem into the offhand via a
 *       single {@code SWAP} slot-action on the always-live player handler (sync id 0), the same
 *       mechanic as vanilla's offhand-swap key. Least interruptive, but no visible inventory.</li>
 * </ul>
 *
 * <p>Emergency timing stays slightly varied without delaying the offhand refill. Every move is
 * re-validated, and the visible-inventory path uses vanilla SWAP actions so it does not need a
 * slow pickup/place/cleanup cursor chain.
 */
public final class TotemTweaksController {
	private static final int OFFHAND_SWAP_BUTTON = 40;  // vanilla SWAP button that targets the offhand
	private static final Item TOTEM = Items.TOTEM_OF_UNDYING;
	private static final int MAX_MOVES = 3;             // safety cap so a stuck state can't loop forever

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private Phase phase = Phase.IDLE;
	private long nextActionNanos;
	private long deadlineNanos;
	private String status = "Idle";

	private boolean guiMode;            // captured at pop time: open-inventory vs. silent swap
	private boolean openedInventory;
	private int savedSelectedSlot = -1;
	private int moveCount;

	public TotemTweaksController(ProFPSConfig config) {
		this.config = config;
	}

	public void markTotemPop(MinecraftClient client, Entity entity) {
		if (!config.totemTweaks || client == null || client.player == null || entity != client.player) {
			return;
		}
		if (client.player.isSpectator()) return;
		// A second pop always supersedes backup/close work from the prior one.
		if (phase != Phase.IDLE) reset(client);
		long now = System.nanoTime();
		// Emergency refill: retain a small non-fixed response window, but do not leave the
		// offhand empty for the old 140-320 ms reaction delay.
		guiMode = config.totemOpenInventory;
		phase = Phase.WAIT_AFTER_POP;
		nextActionNanos = now + ms(18D + rng.nextDouble() * 34D);
		deadlineNanos = now + ms(1500D);
		openedInventory = false;
		savedSelectedSlot = -1;
		moveCount = 0;
		status = "Reacting";
	}

	public void tick(MinecraftClient client) {
		if (!config.totemTweaks) { reset(client); return; }
		if (phase == Phase.IDLE) { status = "Idle"; return; }
		if (!isUsable(client)) { reset(client); return; }

		long now = System.nanoTime();
		if (now > deadlineNanos) { reset(client); return; }
		if (now < nextActionNanos) return;

		switch (phase) {
			case WAIT_AFTER_POP -> begin(client, now);
			// Open-inventory path
			case ANALYZE -> analyze(client, now);
			case WAIT_BEFORE_CLOSE -> closeInventory(client, now);
			case CLOSING -> finish(client);
			// Silent-swap path
			case SWAP_OFFHAND -> swapOffhand(client, now);
			case SWAP_HOTBAR -> swapHotbar(client, now);
			case IDLE -> {}
		}
	}

	public String status(MinecraftClient client) {
		if (!config.totemTweaks) return "Off";
		if (client == null || client.player == null) return "Idle";
		return status;
	}

	private void begin(MinecraftClient client, long now) {
		if (guiMode) {
			openInventory(client, now);
		} else {
			phase = Phase.SWAP_OFFHAND;
			swapOffhand(client, now);
		}
	}

	// ── Open-inventory path ─────────────────────────────────────────────────

	private void openInventory(MinecraftClient client, long now) {
		if (client.currentScreen != null) { reset(client); return; } // a real screen is open — don't fight it
		savedSelectedSlot = client.player.getInventory().getSelectedSlot();
		client.setScreen(new TotemScreen(client.player));
		openedInventory = true;
		phase = Phase.ANALYZE;
		status = "Opening";
		// Player inventory uses the always-live handler 0, so there is no container-open
		// acknowledgement to await. Refill the offhand in this same tick.
		analyze(client, now);
	}

	/** Decide the next totem move from the current state; re-run between every move. */
	private void analyze(MinecraftClient client, long now) {
		if (!ourScreenOpen(client)) { reset(client); return; }

		if (moveCount < MAX_MOVES && !client.player.getOffHandStack().isOf(TOTEM)) {
			int source = findTotem(client, false);
			if (source >= 0) {
				// One vanilla offhand SWAP replaces the previous pickup + place + cleanup
				// sequence and never leaves an item attached to the cursor.
				click(client, invToScreen(source), OFFHAND_SWAP_BUTTON, SlotActionType.SWAP);
				moveCount++;
				nextActionNanos = now + ms(5D + rng.nextDouble() * 13D);
				status = "Refilling";
				return;
			}
		}
		if (moveCount < MAX_MOVES && !hotbarHasTotem(client)) {
			int emptyHotbar = findEmptyHotbar(client);
			int backup = findTotem(client, true);
			if (emptyHotbar >= 0 && backup >= 0) {
				click(client, invToScreen(backup), emptyHotbar, SlotActionType.SWAP);
				moveCount++;
				nextActionNanos = now + ms(6D + rng.nextDouble() * 14D);
				status = "Refilling";
				return;
			}
		}
		phase = Phase.WAIT_BEFORE_CLOSE;
		nextActionNanos = now + ms(7D + rng.nextDouble() * 15D);
		status = "Closing";
	}

	private void closeInventory(MinecraftClient client, long now) {
		if (openedInventory && ourScreenOpen(client)) {
			client.player.closeHandledScreen();
			client.setScreen(null);
		}
		if (savedSelectedSlot >= 0 && savedSelectedSlot < 9) {
			client.player.getInventory().setSelectedSlot(savedSelectedSlot);
		}
		phase = Phase.CLOSING;
		nextActionNanos = now + ms(5D + rng.nextDouble() * 12D);
	}

	private void finish(MinecraftClient client) {
		reset(client);
	}

	private boolean ourScreenOpen(MinecraftClient client) {
		return client.currentScreen instanceof TotemScreen;
	}

	// ── Silent-swap path ────────────────────────────────────────────────────

	private void swapOffhand(MinecraftClient client, long now) {
		// Never reach into the handler while a container is open — the click would hit the wrong
		// screen handler. Wait for the player to be free; the deadline bails us out.
		if (client.currentScreen != null) { nextActionNanos = now + ms(25D); return; }
		if (!client.player.getOffHandStack().isOf(TOTEM)) {
			int totem = findTotem(client, false);
			if (totem < 0) { reset(client); return; }
			click(client, invToScreen(totem), OFFHAND_SWAP_BUTTON, SlotActionType.SWAP);
			status = "Refilled";
		}
		if (hotbarHasTotem(client)) { reset(client); return; }
		int backup = findTotem(client, true);
		int emptyHotbar = findEmptyHotbar(client);
		if (backup >= 0 && emptyHotbar >= 0) {
			phase = Phase.SWAP_HOTBAR;
			nextActionNanos = now + ms(8D + rng.nextDouble() * 18D);
			status = "Backup";
		} else {
			reset(client);
		}
	}

	private void swapHotbar(MinecraftClient client, long now) {
		if (client.currentScreen != null || hotbarHasTotem(client)) { reset(client); return; }
		int backup = findTotem(client, true);
		int emptyHotbar = findEmptyHotbar(client); // inventory index 0-8, also the SWAP button
		if (backup >= 0 && emptyHotbar >= 0) {
			click(client, invToScreen(backup), emptyHotbar, SlotActionType.SWAP);
		}
		reset(client);
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
				&& client.interactionManager != null && client.player.isAlive();
	}

	private void reset(MinecraftClient client) {
		if (openedInventory && client != null && client.currentScreen instanceof TotemScreen) {
			client.player.closeHandledScreen();
			client.setScreen(null);
			if (savedSelectedSlot >= 0 && savedSelectedSlot < 9) {
				client.player.getInventory().setSelectedSlot(savedSelectedSlot);
			}
		}
		phase = Phase.IDLE;
		nextActionNanos = 0L;
		deadlineNanos = 0L;
		openedInventory = false;
		savedSelectedSlot = -1;
		moveCount = 0;
		status = "Idle";
	}

	private long ms(double value) {
		return (long) (value * 1_000_000D);
	}

	private enum Phase {
		IDLE,
		WAIT_AFTER_POP,
		// open-inventory path
		ANALYZE,
		WAIT_BEFORE_CLOSE,
		CLOSING,
		// silent-swap path
		SWAP_OFFHAND,
		SWAP_HOTBAR
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
