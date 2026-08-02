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
 * Routes typed characters into the Packet Utils overlay's focused text field (the in-GUI chat
 * box or the fabricator's slot/button fields).
 *
 * <p>This hooks {@link Keyboard#onChar} — the single dispatch point GLFW char events funnel
 * through — rather than {@code Screen.charTyped}. {@code charTyped} only exists as a default
 * method on the {@code Element} interface, and injecting into interface defaults doesn't
 * reliably apply at runtime, which left the overlay's fields deaf to typing. When no overlay
 * field is focused this does nothing and the character reaches the screen as normal. Fabric's
 * screen keyboard events cover key presses but not chars, so this stays a mixin.
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
