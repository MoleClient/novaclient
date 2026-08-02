package com.profps.client.mixin;

import com.profps.client.classics.FullBrightController;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Enhances only the two brightness inputs written to Minecraft's lightmap UBO.
 * The ordinals mirror LightmapTextureManager.update's std140 layout:
 * night-vision is float #4 and gamma/brightness is float #7.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {
	@ModifyArg(
			method = "update",
			at = @At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;",
					ordinal = 3
			),
			index = 0
	)
	private float profps$fullBrightNightVision(float vanillaStrength) {
		return FullBrightController.adjustNightVision(vanillaStrength);
	}

	@ModifyArg(
			method = "update",
			at = @At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;",
					ordinal = 6
			),
			index = 0
	)
	private float profps$fullBrightGamma(float vanillaGamma) {
		return FullBrightController.adjustGamma(vanillaGamma);
	}
}
