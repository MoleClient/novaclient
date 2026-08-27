package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Rewrites names as chat messages are added, which preserves the server's text styling. */
@Mixin(ChatHud.class)
public abstract class ChatHudNickMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), argsOnly = true)
	private Text profps$nickChat(Text message) {
		return NicknameManager.spoof(message);
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), argsOnly = true)
	private Text profps$nickChatSigned(Text message) {
		return NicknameManager.spoof(message);
	}
}
