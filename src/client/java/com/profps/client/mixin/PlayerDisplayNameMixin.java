package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spoofs a player's name at {@code getName()} and {@code getDisplayName()}, which the
 * nametag render state and GUI text derive from.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerDisplayNameMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void profps$nickDisplayName(CallbackInfoReturnable<Text> cir) {
		Text original = cir.getReturnValue();
		if (original == null) return;
		Text spoofed = NicknameManager.spoof(original);
		if (spoofed != original) cir.setReturnValue(spoofed);
	}

	@Inject(method = "getName", at = @At("RETURN"), cancellable = true)
	private void profps$nickName(CallbackInfoReturnable<Text> cir) {
		Text original = cir.getReturnValue();
		if (original == null) return;
		Text spoofed = NicknameManager.spoof(original);
		if (spoofed != original) cir.setReturnValue(spoofed);
	}
}
