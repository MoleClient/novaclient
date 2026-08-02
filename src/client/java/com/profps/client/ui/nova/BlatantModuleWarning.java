package com.profps.client.ui.nova;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Set;

/**
 * Central confirmation gate for modules that directly violate vanilla multiplayer
 * reach, velocity, or movement physics. Runtime behavior remains available; only
 * an explicit user confirmation may turn one on through the module UI/keybinds.
 */
final class BlatantModuleWarning {
	private static final Set<String> MODULE_IDS = Set.of(
			"reach", "velocity", "flight", "waterwalk", "boatfly", "teleporter");

	private BlatantModuleWarning() {}

	static boolean requiresConfirmation(String moduleId) {
		return moduleId != null && MODULE_IDS.contains(moduleId);
	}

	static Set<String> moduleIds() {
		return MODULE_IDS;
	}

	static void show(MinecraftClient client, Screen returnTo,
			NovaModules.Module module, Runnable enable) {
		if (client == null || module == null || enable == null) return;
		Text title = Text.literal("BLATANT FLAG RISK")
				.formatted(Formatting.RED, Formatting.BOLD);
		Text message = Text.literal(module.name
				+ " directly changes vanilla multiplayer reach, knockback, movement, or packet timing. "
				+ "Servers can detect this immediately and may flag, kick, or ban you. Enable anyway?");
		Text yes = Text.literal("Enable Anyway").formatted(Formatting.RED);
		Text no = Text.literal("Cancel");
		client.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) enable.run();
			client.setScreen(returnTo);
		}, title, message, yes, no));
	}
}
