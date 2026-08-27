package com.profps.client.mixin;

import com.profps.client.aim.SilentAimController;
import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@Shadow
	private double cursorDeltaX;

	@Shadow
	private double cursorDeltaY;

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void profps$freecamScrollTrimsSpeed(long window, double horizontal, double vertical, CallbackInfo ci) {
		// Consumes the event so the hotbar does not switch slots during freecam.
		if (!FreecamController.isActive() || client.currentScreen != null) return;
		double amount = vertical != 0.0 ? vertical : horizontal;
		if (amount == 0.0) return;
		FreecamController.onScroll(amount);
		ci.cancel();
	}

	@Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
	private void profps$redirectSilentAimLook(double timeDelta, CallbackInfo ci) {
		if (client.currentScreen != null || !((Mouse) (Object) this).isCursorLocked()) return;
		// The delta steers the rendered view only; vanilla must not also apply it.
		if (SilentAimController.isActive()) {
			SilentAimController.instance().handleMouse(client, cursorDeltaX, cursorDeltaY);
			cursorDeltaX = 0.0;
			cursorDeltaY = 0.0;
			ci.cancel();
		}
	}
}
