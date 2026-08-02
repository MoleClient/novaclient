package com.profps.client.mixin;

import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While freecam flies, the audio listener stays with the player's body
 * instead of following the detached camera. Otherwise dipping the camera
 * into water/lava swapped the whole soundscape (underwater muffling,
 * "entering water" ambience) even though the player never moved.
 */
@Mixin(SoundManager.class)
public abstract class SoundListenerMixin {
	@Inject(method = "updateListenerPosition", at = @At("HEAD"), cancellable = true)
	private void profps$freezeListenerDuringFreecam(Camera camera, CallbackInfo ci) {
		if (FreecamController.isActive()) {
			ci.cancel();
		}
	}
}
