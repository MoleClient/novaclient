package com.profps.client.mixin;

import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The freecam's entity-side seams:
 *
 * <ul>
 *   <li>Mouse deltas are intercepted where vanilla would turn the player
 *       ({@code changeLookDirection}) and steer the camera instead — the body's
 *       rotation never sees them, so nothing leaks into movement packets.</li>
 *   <li>The local player never reports sneaking, so the body cannot slip into
 *       the crouch pose mid-flight.</li>
 *   <li>Entities near the camera are forced to render. Vanilla distance-culls
 *       relative to render state that assumes the camera rides the player; a
 *       far-flung camera would otherwise watch mobs pop out of existence.</li>
 * </ul>
 */
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

	@Inject(method = "shouldRender(D)Z", at = @At("HEAD"), cancellable = true)
	private void profps$renderNearTheCamera(double distanceSq, CallbackInfoReturnable<Boolean> cir) {
		if (FreecamController.isActive() && distanceSq < FreecamController.FORCED_RENDER_DISTANCE_SQ) {
			cir.setReturnValue(true);
		}
	}
}
