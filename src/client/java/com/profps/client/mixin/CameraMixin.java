package com.profps.client.mixin;

import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPos(Vec3d pos);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void profps$applyFreecam(World area, Entity focusedEntity, boolean thirdPerson,
			boolean inverseView, float tickProgress, CallbackInfo ci) {
		if (!FreecamController.isActive()) return;
		setPos(FreecamController.cameraPosition(tickProgress));
		setRotation(FreecamController.cameraYaw(), FreecamController.cameraPitch());
	}
}
