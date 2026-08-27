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

/** Outbound packet interception and queueing behind the Packet Utils overlay. State is session-only. */
public final class PacketManager {
	public static final PacketManager INSTANCE = new PacketManager();

	private static final Gson GSON = new Gson();

	/** false = drop every outbound packet. */
	public boolean sendPackets = true;
	/** true = park outbound packets in {@link #held} instead of sending. */
	public boolean delayPackets = false;
	/** One-tap freeze that parks outbound packets. */
	public boolean desyncActive = false;

	private final List<Packet<?>> held = new ArrayList<>();
	private boolean bypass;              // true only while re-sending the queue, skips interception
	private boolean suppressCloseOnce;   // drop exactly one CloseHandledScreenC2SPacket

	private Screen savedScreen;
	private PacketManager() {}

	/** True while the Packet Utils feature is enabled in config. */
	public boolean active() {
		var cfg = ProFPSClient.config();
		return cfg != null && cfg.packetUtils;
	}

	/** True while outbound packets are being parked. */
	public boolean holding() {
		return delayPackets || desyncActive;
	}

	/**
	 * Called at the head of the client's packet-send path. Returns true to cancel the send.
	 * Precedence: flush bypass, disabled, close-suppression, silence, hold.
	 */
	public boolean interceptOutbound(Packet<?> packet) {
		if (bypass) return false;
		if (active() && suppressCloseOnce && packet instanceof CloseHandledScreenC2SPacket) {
			suppressCloseOnce = false;
			return true;
		}
		if (active() && !sendPackets) return true;
		if (active() && holding()) {
			held.add(packet);
			return true;
		}

		return false;
	}

	public int heldCount() {
		return held.size();
	}

	/** Re-sends parked packets in order, bypassing interception, then clears the queue. */
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

	/** Discards the queue without sending it. */
	public void clearHeld() {
		held.clear();
	}

	public void setSendPackets(boolean value) {
		sendPackets = value;
		if (value && !holding()) flushHeld();
	}

	public void setDelayPackets(boolean value) {
		delayPackets = value;
		if (!holding()) flushHeld();
	}

	/** Toggles de-sync: parks outbound packets, or flushes them when turned off. */
	public void toggleDesync() {
		desyncActive = !desyncActive;
		if (!holding()) flushHeld();
	}

	/** Closes the current screen client-side without sending a close packet, leaving the server container open. */
	public void closeWithoutPacket() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		suppressCloseOnce = true;
		mc.setScreen(null);
	}

	/** Stores the current screen so it can be re-opened later. */
	public void saveGui() {
		savedScreen = MinecraftClient.getInstance().currentScreen;
	}

	public boolean hasSavedGui() {
		return savedScreen != null;
	}

	public void reopenSavedGui() {
		if (savedScreen != null) MinecraftClient.getInstance().setScreen(savedScreen);
	}

	/** Flushes the held queue, then disconnects. */
	public void disconnectAndSend() {
		flushHeld();
		ClientPlayNetworkHandler net = MinecraftClient.getInstance().getNetworkHandler();
		if (net != null) {
			net.getConnection().disconnect(Text.literal("[ProFPS] Packet Utils — disconnect & flush"));
		}
	}

	/** Sends a slot click through vanilla's interaction manager so sync id, revision and slot state are valid. */
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

	/** Sends a chat message, or a command when the text begins with '/'. */
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

	/** Returns the current screen title as chat-component JSON, falling back to plain text. */
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

	/** Sync id of the open screen handler; 0 is the player's own inventory handler. */
	public int currentSyncId() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player == null ? -1 : mc.player.currentScreenHandler.syncId;
	}

	/** Revision counter the server acknowledges clicks against. */
	public int currentRevision() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player == null ? -1 : mc.player.currentScreenHandler.getRevision();
	}

	/** Resets all session state; called on disconnect. */
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
