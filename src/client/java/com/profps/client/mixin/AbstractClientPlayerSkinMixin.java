package com.profps.client.mixin;

import com.mojang.authlib.GameProfile;
import com.profps.client.classics.NicknameManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the nickname skin override to the in-world player model, hand and first-person view. */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerSkinMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void profps$skinOverride(CallbackInfoReturnable<SkinTextures> cir) {
		AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
		SkinTextures override;
		if (MinecraftClient.getInstance().player == self) {
			// Keyed by session name: the entity profile name can differ and miss.
			override = NicknameManager.selfSkinOverride();
		} else {
			GameProfile profile = self.getGameProfile();
			override = profile == null ? null : NicknameManager.skinFor(profile.name());
		}
		if (override != null) {
			cir.setReturnValue(override);
		}
	}
}
