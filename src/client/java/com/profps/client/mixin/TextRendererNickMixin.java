package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The catch-all for the Nickname / Nick Other modules: every piece of text the game
 * draws funnels through one of {@link TextRenderer}'s three public {@code draw}
 * overloads, so swapping names here covers chat, tab list, nametags, scoreboards,
 * titles, signs, GUIs — everything. {@link NicknameManager#spoof} returns the input
 * unchanged (no allocation, no rewrite) whenever the modules are off or the text
 * holds no target name, so this is effectively free when idle.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererNickMixin {

	@ModifyVariable(method = "draw(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
			at = @At("HEAD"), argsOnly = true)
	private String profps$nickString(String text) {
		return NicknameManager.spoof(text);
	}

	@ModifyVariable(method = "draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
			at = @At("HEAD"), argsOnly = true)
	private Text profps$nickText(Text text) {
		return NicknameManager.spoof(text);
	}

	@ModifyVariable(method = "draw(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
			at = @At("HEAD"), argsOnly = true)
	private OrderedText profps$nickOrdered(OrderedText text) {
		return NicknameManager.spoof(text);
	}
}
