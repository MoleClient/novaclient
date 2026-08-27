package com.profps.client.inventory;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.SignEditScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;

/** Fills a freshly opened sign edit screen with the configured text and closes it. */
public final class AutoSignController {
	private static final int MAX_LINE = 100;

	private final ProFPSConfig config;

	public AutoSignController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.autoSignEnabled) return;
		if (client == null || client.player == null) return;
		if (!(client.currentScreen instanceof AbstractSignEditScreen screen)) return;

		String[] wanted = {
				line(config.autoSignLine1), line(config.autoSignLine2),
				line(config.autoSignLine3), line(config.autoSignLine4)
		};
		// Nothing configured: leave the sign editable.
		if (wanted[0].isEmpty() && wanted[1].isEmpty() && wanted[2].isEmpty() && wanted[3].isEmpty()) return;

		String[] messages = ((SignEditScreenAccessor) screen).profps$getMessages();
		if (messages == null || messages.length < wanted.length) return;
		System.arraycopy(wanted, 0, messages, 0, wanted.length);
		screen.close();
	}

	private String line(String value) {
		if (value == null) return "";
		String trimmed = value.strip();
		return trimmed.length() <= MAX_LINE ? trimmed : trimmed.substring(0, MAX_LINE);
	}
}
