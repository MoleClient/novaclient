package com.profps.client.instants;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/** Holds the forward and sprint keybinds for AutoWalk and AutoSprint. */
public final class MovementInstantsController {
	private final ProFPSConfig config;
	private boolean walkHeld;
	private boolean sprintHeld;

	public MovementInstantsController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || player == null || client.options == null || client.currentScreen != null) {
			release(client);
			return;
		}

		if (config.instantAutoWalk) {
			client.options.forwardKey.setPressed(true);
			walkHeld = true;
		} else if (walkHeld) {
			client.options.forwardKey.setPressed(false);
			walkHeld = false;
		}

		boolean movingForward = client.options.forwardKey.isPressed();
		if (config.instantAutoSprint && movingForward && !player.isSneaking()) {
			client.options.sprintKey.setPressed(true);
			sprintHeld = true;
		} else if (sprintHeld) {
			client.options.sprintKey.setPressed(false);
			sprintHeld = false;
		}
	}

	private void release(MinecraftClient client) {
		if (client.options == null) return;
		if (walkHeld) {
			client.options.forwardKey.setPressed(false);
			walkHeld = false;
		}
		if (sprintHeld) {
			client.options.sprintKey.setPressed(false);
			sprintHeld = false;
		}
	}
}
