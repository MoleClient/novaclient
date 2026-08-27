package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Spoofs names in all three public {@link TextRenderer} draw overloads. */
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
