package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spoofs the TAB-LIST row name. {@link PlayerListHud#getPlayerName(PlayerListEntry)} builds
 * each row's text from the entry's display name / profile name; rewriting its result makes
 * the tab show the Nickname / Nick Other value (the old TextRenderer hook missed it because
 * the tab text is drawn through the deferred GUI render state in 1.21.11).
 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudNameMixin {

	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void profps$nickTabName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		Text original = cir.getReturnValue();
		if (original == null) return;
		Text spoofed = NicknameManager.spoof(original);
		if (spoofed != original) cir.setReturnValue(spoofed);
	}
}
