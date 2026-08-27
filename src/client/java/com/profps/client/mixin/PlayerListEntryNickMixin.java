package com.profps.client.mixin;

import com.mojang.authlib.GameProfile;
import com.profps.client.classics.NicknameManager;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Serves the spoofed skin from {@link PlayerListEntry#getSkinTextures()}. */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryNickMixin {

	@Inject(method = "getSkinTextures()Lnet/minecraft/entity/player/SkinTextures;", at = @At("RETURN"), cancellable = true)
	private void profps$nickSkin(CallbackInfoReturnable<SkinTextures> cir) {
		GameProfile profile = ((PlayerListEntry) (Object) this).getProfile();
		if (profile == null) return;
		SkinTextures skin = NicknameManager.skinFor(profile.name());
		if (skin != null) {
			cir.setReturnValue(skin);
		}
	}
}
