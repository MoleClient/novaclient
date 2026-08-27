package com.profps.client.mixin;

import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity-side freecam hooks: mouse look, sneak pose and render culling. */
@Mixin(Entity.class)
public abstract class FreecamEntityMixin {
	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	private void profps$steerFreecamInstead(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (!FreecamController.isActive()) return;
		if ((Object) this != MinecraftClient.getInstance().player) return;
		FreecamController.onMouseLook(cursorDeltaX, cursorDeltaY);
		ci.cancel();
	}

	@Inject(method = "isSneaking", at = @At("HEAD"), cancellable = true)
	private void profps$noSneakPoseWhileFlying(CallbackInfoReturnable<Boolean> cir) {
		if (FreecamController.isActive() && (Object) this == MinecraftClient.getInstance().player) {
			cir.setReturnValue(false);
		}
	}

	// Vanilla culls against the player position, so entities near a detached camera need forcing.
	@Inject(method = "shouldRender(D)Z", at = @At("HEAD"), cancellable = true)
	private void profps$renderNearTheCamera(double distanceSq, CallbackInfoReturnable<Boolean> cir) {
		if (FreecamController.isActive() && distanceSq < FreecamController.FORCED_RENDER_DISTANCE_SQ) {
			cir.setReturnValue(true);
		}
	}
}
