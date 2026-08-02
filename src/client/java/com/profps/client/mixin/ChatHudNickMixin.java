package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Rewrites names in chat at the source — when a message is added — so the stored
 * message (and every line it later wraps into) already carries the nickname, with
 * its server formatting preserved through the {@code Text} tree rebuild. The
 * TextRenderer mixin would still catch it at draw time, but doing it here keeps rich
 * server chat styling intact instead of flattening replaced lines.
 */
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
