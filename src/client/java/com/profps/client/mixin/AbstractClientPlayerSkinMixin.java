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

/**
 * Applies the NovaClient / Nickname skin override to a player's OWN rendered model.
 *
 * <p>The in-world model, the held-item hand and the first-person view read the skin from
 * {@link AbstractClientPlayerEntity#getSkin()} — NOT from {@code PlayerListEntry.getSkinTextures()}.
 * So overriding only the list entry (as before) changed the tab head but left your actual
 * body wearing your real skin. Hooking {@code getSkin()} here is what makes "wear this
 * player's skin" actually show on you everywhere, client-side.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerSkinMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void profps$skinOverride(CallbackInfoReturnable<SkinTextures> cir) {
		AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
		SkinTextures override;
		if (MinecraftClient.getInstance().player == self) {
			// Your own body / hand / first-person: key by the SESSION name, exactly how
			// NicknameManager.update() stored it. The entity profile name can differ and miss.
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
