package com.profps.client.mixin;

import com.profps.client.aim.SilentAimController;
import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void profps$applyCameraOverrides(World area, Entity focusedEntity, boolean thirdPerson,
			boolean inverseView, float tickProgress, CallbackInfo ci) {
		if (FreecamController.isActive()) {
			// The camera integrates its own movement on the render clock; this
			// hook is the frame pulse. The drawn transform interpolates the last
			// frame step so the flight stays glassy at any fps.
			FreecamController.frame();
			setPos(FreecamController.cameraX(tickProgress),
					FreecamController.cameraY(tickProgress),
					FreecamController.cameraZ(tickProgress));
			setRotation(FreecamController.cameraYaw(tickProgress),
					FreecamController.cameraPitch(tickProgress));
			return;
		}
		// Silent aim only ever changes which rotation is drawn. The body keeps
		// the aim rotation, so physics and every outbound packet are unchanged.
		if (SilentAimController.isActive()) {
			setRotation(SilentAimController.viewYaw(), SilentAimController.viewPitch());
		}
	}

	@Inject(method = "isThirdPerson", at = @At("HEAD"), cancellable = true)
	private void profps$freecamRendersOwnBody(CallbackInfoReturnable<Boolean> cir) {
		// A detached camera is third-person by definition: this is what makes
		// your own body visible (and keeps the first-person hand off screen).
		if (FreecamController.isActive()) {
			cir.setReturnValue(true);
		}
	}
}
