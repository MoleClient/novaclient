package com.profps.client.mixin;

import com.profps.client.aim.AutoAimController;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Auto Aim perfect a projectile shot the instant before it leaves. We hook the
 * two ways a projectile fires — releasing a drawn bow ({@code stopUsingItem}) and a
 * right-click that throws a fireball or fires a loaded crossbow ({@code interactItem})
 * — at HEAD, so the corrected rotation (and its look packet) reach the server before
 * the fire packet does, and the projectile launches along the solved aim.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class InteractionManagerAimMixin {

	@Inject(method = "stopUsingItem", at = @At("HEAD"))
	private void profps$aimBowRelease(PlayerEntity user, CallbackInfo ci) {
		AutoAimController controller = AutoAimController.get();
		if (controller != null) controller.onStopUsing();
	}

	@Inject(method = "interactItem", at = @At("HEAD"))
	private void profps$aimUse(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
		if (hand != Hand.MAIN_HAND) return;
		AutoAimController controller = AutoAimController.get();
		if (controller != null) controller.onInteractItem();
	}
}
