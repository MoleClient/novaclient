package com.profps.client.mixin;

import com.profps.client.packet.PacketOverlay;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes typed characters into the Packet Utils overlay's focused text field.
 * Hooks {@link Keyboard#onChar} because injecting into the {@code Element.charTyped}
 * interface default does not reliably apply at runtime.
 */
@Mixin(Keyboard.class)
public class PacketScreenCharMixin {

	@Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
	private void profps$overlayCharTyped(long window, CharInput input, CallbackInfo ci) {
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (screen != null
				&& PacketOverlay.shouldShow(screen)
				&& PacketOverlay.get().charTyped(input)) {
			ci.cancel();
		}
	}
}
