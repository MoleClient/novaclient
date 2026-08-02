package com.profps.client.ui.nova;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * In-game per-module toggle keys, assigned from the Nova GUI keybind rows.
 * Polls GLFW key state each tick (only while no screen is open) and flips the
 * bound module through the same {@link NovaModules} model the GUI uses, so
 * dependency rules stay consistent.
 */
public final class ModuleKeybinds {
	// Mode binds live under the same ids the Modes panel used, but the modes are ordinary catalogue
	// entries now, so the generic module loop below drives them and old keybinds keep working.
	public static final String MODE_SWORD_BIND = NovaModules.MODE_SWORD;
	public static final String MODE_AXE_BIND = NovaModules.MODE_AXE;
	public static final String MODE_MACE_BIND = NovaModules.MODE_MACE;

	private final ProFPSConfig config;
	private final List<NovaModules.Category> categories;
	private final Set<Integer> heldKeys = new HashSet<>();

	public ModuleKeybinds(ProFPSConfig config, List<NovaModules.Category> categories) {
		this.config = config;
		this.categories = categories;
	}

	public void tick(MinecraftClient client) {
		if (client.player == null || config.moduleKeybinds.isEmpty()) return;
		if (client.currentScreen != null) {
			// Preserve a warning-triggering key until it is physically released;
			// otherwise closing the confirmation while still holding the key would
			// immediately toggle the newly enabled module back off.
			heldKeys.removeIf(key -> !InputUtil.isKeyPressed(client.getWindow(), key));
			return;
		}
		for (NovaModules.Category category : categories) {
			for (NovaModules.Module module : category.modules) {
				Integer key = config.moduleKeybinds.get(module.id);
				if (key == null || key <= 0) continue;
				boolean pressed = InputUtil.isKeyPressed(client.getWindow(), key);
				if (pressed && heldKeys.add(key)) {
					// A module the active combat mode owns is not yours to flip from here either.
					// Without this the key silently rewrites the standalone field while the GUI, the
					// HUD and the policy all keep reading the mode's answer.
					String owner = NovaModules.managedBy(config, module.id);
					if (owner != null) {
						notifyLocked(client, module, owner);
					} else {
						boolean enabled = !module.get.get();
						if (enabled && BlatantModuleWarning.requiresConfirmation(module.id)) {
							BlatantModuleWarning.show(client, null, module, () -> {
								module.set.accept(true);
								config.save();
								notifyToggle(client, module, true);
							});
							continue;
						}
						module.set.accept(enabled);
						config.save();
						notifyToggle(client, module, enabled);
					}
				} else if (!pressed) {
					heldKeys.remove(key);
				}
			}
		}
	}


	private void notifyLocked(MinecraftClient client, NovaModules.Module module, String owner) {
		MutableText message = Text.empty();
		message.append(Text.literal("Nova ").withColor(0xA78BFA));
		message.append(Text.literal(module.name + " ").withColor(0xEDEDF4));
		message.append(Text.literal("locked by " + owner).withColor(0xFBBF24));
		client.inGameHud.setOverlayMessage(message, false);
		client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 0.72F));
	}

	private void notifyToggle(MinecraftClient client, NovaModules.Module module, boolean enabled) {
		MutableText message = Text.empty();
		message.append(Text.literal("Nova ").withColor(0xA78BFA));
		message.append(Text.literal(module.name + " ").withColor(0xEDEDF4));
		message.append(Text.literal(enabled ? "ON" : "OFF").withColor(enabled ? 0x34D399 : 0xF87171));
		client.inGameHud.setOverlayMessage(message, false);
		client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, enabled ? 1.15F : 0.85F));
	}

}
