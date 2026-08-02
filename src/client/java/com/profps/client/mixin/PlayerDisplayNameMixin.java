package com.profps.client.mixin;

import com.profps.client.classics.NicknameManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spoofs a player's name at the SOURCE — {@code getName()} and {@code getDisplayName()}.
 *
 * <p>In 1.21.11 the floating nametag and most GUI text are batched/deferred, so they no
 * longer pass through the {@code TextRenderer.draw} overloads the old text mixin hooked —
 * which is why only chat changed. The nametag render state captures {@code getDisplayName()},
 * so rewriting the name here makes the name above the head (and anywhere else derived from
 * it) show the Nickname / Nick Other value. Client-only, cosmetic; {@link NicknameManager#spoof}
 * returns the input unchanged when nothing matches.
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
