package com.profps.client.packet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.profps.ProFPS;
import com.profps.client.ProFPSClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime engine behind the Packet Utils overlay — the plumbing every button on the
 * in-GUI toolbar drives. All state here is session-only (never written to the config):
 * a reconnect always starts clean, so you never accidentally rejoin a server already
 * silenced or holding a stale queue.
 *
 * <h2>Outbound interception</h2>
 * {@code PacketSendMixin} funnels every client-to-server packet through
 * {@link #interceptOutbound(Packet)} at the head of {@code ClientCommonNetworkHandler.sendPacket}.
 * Three states can hold a packet back:
 * <ul>
 *   <li><b>Send Packets = false</b> — the packet is dropped and gone. Full radio silence
 *       (keep-alives included, so the server will eventually time you out — leave on your
 *       own terms with "Disconnect &amp; flush").</li>
 *   <li><b>Delay Packets = true</b> / <b>De-sync</b> — the packet is parked in {@link #held}
 *       in order; releasing the hold re-sends the whole queue verbatim ("blink").</li>
 *   <li>a one-shot close suppression for "Close without packet".</li>
 * </ul>
 * The mixin never sees our own flush: {@link #flushHeld()} raises {@link #bypass} so the
 * queued packets sail straight through.
 */
public final class PacketManager {
	public static final PacketManager INSTANCE = new PacketManager();

	private static final Gson GSON = new Gson();

	// ── Live (session) state — mirrored by the overlay switches and the Packet Utils page ──
	/** false = drop every outbound packet (hard silence). */
	public boolean sendPackets = true;
	/** true = park outbound packets in {@link #held} instead of sending (persistent blink). */
	public boolean delayPackets = false;
	/** De-sync ("blink") — a quick one-tap freeze that also parks outbound packets. */
	public boolean desyncActive = false;

	private final List<Packet<?>> held = new ArrayList<>();
	private boolean bypass;              // true only while WE re-send the queue (skips interception)
	private boolean suppressCloseOnce;   // drop exactly one CloseHandledScreenC2SPacket

	private Screen savedScreen;          // "Save GUI" target, re-openable from the Packet Utils page
	private PacketManager() {}

	/** Master gate: the overlay renders and interception runs only while Packet Utils is enabled. */
	public boolean active() {
		var cfg = ProFPSClient.config();
		return cfg != null && cfg.packetUtils;
	}

	/** Are outbound packets currently being parked (delay mode or an active de-sync)? */
	public boolean holding() {
		return delayPackets || desyncActive;
	}

	/**
	 * Called at the head of the client's packet-send path. Returns {@code true} to CANCEL the
	 * send — the packet is either dropped (silence) or parked (delay/de-sync). Precedence:
	 * our own flush &gt; disabled &gt; close-suppression &gt; silence &gt; hold.
	 */
	public boolean interceptOutbound(Packet<?> packet) {
		if (bypass) return false;         // this is us flushing the queue — let it go
		if (active() && suppressCloseOnce && packet instanceof CloseHandledScreenC2SPacket) {
			suppressCloseOnce = false;
			return true;                  // eat the close packet requested by "Close without packet"
		}
		if (active() && !sendPackets) return true;    // hard drop
		if (active() && holding()) {
			held.add(packet);
			return true;
		}

		return false;
	}

	public int heldCount() {
		return held.size();
	}

	/** Re-send every parked packet in order, bypassing interception, then clear the queue. */
	public void flushHeld() {
		if (held.isEmpty()) return;
		ClientPlayNetworkHandler net = MinecraftClient.getInstance().getNetworkHandler();
		if (net == null) {
			held.clear();
			return;
		}
		List<Packet<?>> queue = new ArrayList<>(held);
		held.clear();
		sendBypass(queue);
	}

	private void sendBypass(List<Packet<?>> queue) {
		if (queue.isEmpty()) return;
		ClientPlayNetworkHandler net = MinecraftClient.getInstance().getNetworkHandler();
		if (net == null) return;
		bypass = true;
		try {
			for (Packet<?> packet : queue) {
				net.sendPacket(packet);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.warn("Packet Utils: failed while flushing the held queue.", exception);
		} finally {
			bypass = false;
		}
	}

	/** Throw the queue away without sending it. */
	public void clearHeld() {
		held.clear();
	}

	// ── Toolbar actions ───────────────────────────────────────────────────────────

	public void setSendPackets(boolean value) {
		sendPackets = value;
		// Turning sending back on while nothing is being held releases anything already parked.
		if (value && !holding()) flushHeld();
	}

	public void setDelayPackets(boolean value) {
		delayPackets = value;
		if (!holding()) flushHeld();      // dropped out of every hold mode → release the queue
	}

	/** De-sync toggle: press once to freeze (park outbound), press again to re-sync (flush). */
	public void toggleDesync() {
		desyncActive = !desyncActive;
		if (!holding()) flushHeld();
	}

	/**
	 * Close the current screen client-side WITHOUT telling the server. The server keeps the
	 * container open (handy for keeping an auction/menu session alive); the one-shot suppression
	 * is belt-and-suspenders in case the close path still tries to emit a close packet.
	 */
	public void closeWithoutPacket() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		suppressCloseOnce = true;
		mc.setScreen(null);
	}

	/** Remember the current screen so it can be re-opened later from the Packet Utils page. */
	public void saveGui() {
		savedScreen = MinecraftClient.getInstance().currentScreen;
	}

	public boolean hasSavedGui() {
		return savedScreen != null;
	}

	public void reopenSavedGui() {
		if (savedScreen != null) MinecraftClient.getInstance().setScreen(savedScreen);
	}

	/** Flush anything parked, then drop the connection on your own terms. */
	public void disconnectAndSend() {
		flushHeld();
		ClientPlayNetworkHandler net = MinecraftClient.getInstance().getNetworkHandler();
		if (net != null) {
			net.getConnection().disconnect(Text.literal("[ProFPS] Packet Utils — disconnect & flush"));
		}
	}

	/**
	 * Fabricate a container slot-click and send it through vanilla's interaction manager, which
	 * builds a fully-valid {@code ClickSlotC2SPacket} (correct sync id, revision and slot-state
	 * snapshot). Great for poking container plugins with clicks the real UI wouldn't let you make.
	 */
	public void fabricateClick(int slot, int button, SlotActionType action) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.interactionManager == null) return;
		int syncId = mc.player.currentScreenHandler.syncId;
		try {
			mc.interactionManager.clickSlot(syncId, slot, button, action, mc.player);
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.warn("Packet Utils: fabricate click failed (slot {} button {}).", slot, button, exception);
		}
	}

	/** Send a chat line — or a command when it begins with '/' — from inside an open GUI. */
	public void sendChat(String message) {
		if (message == null) return;
		String text = message.trim();
		if (text.isEmpty()) return;
		ClientPlayNetworkHandler net = MinecraftClient.getInstance().getNetworkHandler();
		if (net == null) return;
		if (text.startsWith("/")) {
			net.sendChatCommand(text.substring(1));
		} else {
			net.sendChatMessage(text);
		}
	}

	/** The current screen's title, serialised to a chat-component JSON string (falls back to plain text). */
	public String currentTitleJson() {
		MinecraftClient mc = MinecraftClient.getInstance();
		Screen screen = mc.currentScreen;
		if (screen == null) return "";
		Text title = screen.getTitle();
		try {
			var ops = mc.world != null
					? mc.world.getRegistryManager().getOps(JsonOps.INSTANCE)
					: JsonOps.INSTANCE;
			JsonElement json = TextCodecs.CODEC.encodeStart(ops, title).getOrThrow();
			return GSON.toJson(json);
		} catch (Exception exception) {
			return "\"" + title.getString() + "\"";
		}
	}

	/** The live sync id of the open screen handler (0 = the player's own inventory handler). */
	public int currentSyncId() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player == null ? -1 : mc.player.currentScreenHandler.syncId;
	}

	/** The open screen handler's revision counter — the value the server acknowledges clicks against. */
	public int currentRevision() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player == null ? -1 : mc.player.currentScreenHandler.getRevision();
	}

	/** Reset all live state (called on disconnect) so the next session starts sending normally. */
	public void reset() {
		sendPackets = true;
		delayPackets = false;
		desyncActive = false;
		held.clear();
		bypass = false;
		suppressCloseOnce = false;
		savedScreen = null;
	}
}
