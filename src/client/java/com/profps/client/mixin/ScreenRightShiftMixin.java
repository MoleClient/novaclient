package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.nova.NovaScreenV2;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Right Shift open the NovaClient Modules UI while on a MENU (no world) — the in-game
 * keybind only fires when no screen is open, so without this you couldn't reach Modules from
 * the title/options/multiplayer screens. In-world is left to the normal keybind. The home
 * Modules screen handles the key itself, so it's skipped here.
 */
@Mixin(Screen.class)
public abstract class ScreenRightShiftMixin {

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void profps$openModules(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
		if (input.key() != GLFW.GLFW_KEY_RIGHT_SHIFT) return;
		Object self = this;
		if (self instanceof NovaScreenV2) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world != null) return; // in-game uses the normal keybind
		ProFPSConfig cfg = ProFPSClient.config();
		if (cfg == null || ProFPSClient.novaCategories() == null) return;
		mc.setScreen(new NovaScreenV2(cfg, ProFPSClient.novaCategories()));
		cir.setReturnValue(true);
	}
}
