package com.profps.client.mixin;

import com.profps.client.assists.ExpandedHitboxController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures vanilla miss/block attacks that fall inside the Expanded Hitbox margin. */
@Mixin(MinecraftClient.class)
public abstract class ExpandedHitboxAttackMixin {

	@Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
	private void profps$expandedHitbox(CallbackInfoReturnable<Boolean> cir) {
		ExpandedHitboxController controller = ExpandedHitboxController.get();
		if (controller != null && controller.interceptAttack((MinecraftClient) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
