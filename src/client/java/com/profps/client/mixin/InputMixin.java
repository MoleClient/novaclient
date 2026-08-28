package com.profps.client.mixin;

import com.profps.client.assists.HitImprovementsController;
import com.profps.client.assists.StrafeImprovementsController;
import com.profps.client.ai.SwordAiController;
import com.profps.client.aim.SilentAimController;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.extras.ScaffoldController;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the module movement overrides after {@link KeyboardInput#tick()} has read
 * the keyboard.
 */
@Mixin(KeyboardInput.class)
public abstract class InputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void profps$overrideMovement(CallbackInfo ci) {
		InputAccessor self = (InputAccessor) (Object) this;
		if (TunnelController.isControlling()) {
			PlayerInput input = TunnelController.movementInput();
			self.profps$setPlayerInput(input);
			self.profps$setMovementVector(vectorFor(input));
		} else if (FreecamController.isActive()) {
			// Freecam owns WASD, so the body reads dead keys.
			self.profps$setPlayerInput(PlayerInput.DEFAULT);
			self.profps$setMovementVector(Vec2f.ZERO);
		} else if (com.profps.client.extras.AutoMoveController.isControlling()) {
			// Auto Move publishes ordinary input; its controller yields to manual keys itself.
			PlayerInput input = com.profps.client.extras.AutoMoveController.movementInput();
			self.profps$setPlayerInput(input);
			self.profps$setMovementVector(vectorFor(input));
		} else {
			PlayerInput input = self.profps$getPlayerInput();
			boolean overridden = false;
			PlayerInput scaffold = ScaffoldController.movementOverride(input);
			if (scaffold != null) {
				input = scaffold;
				overridden = true;
			}

			if (SwordAiController.isControlling()) {
				PlayerInput ai = SwordAiController.movementInput(input);
				if (ai != null) {
					input = ai;
					overridden = true;
				}
			} else if (StrafeImprovementsController.isStrafing()) {
				PlayerInput juke = StrafeImprovementsController.strafeOverride(input);
				if (juke != null) {
					input = juke;
					overridden = true;
				}
			}

			PlayerInput swordSprint = StrafeImprovementsController.swordSprintOverride(input);
			if (swordSprint != null) {
				input = swordSprint;
				overridden = true;
			}

			// Layered after AI/strafe so the hit prep tap goes through the same input path.
			PlayerInput normalHit = HitImprovementsController.normalHitOverride(input);
			if (normalHit != null) {
				input = normalHit;
				overridden = true;
			}

			// Must run after the sprint prep: an airborne swing cannot crit while sprinting.
			PlayerInput critTap = HitImprovementsController.critSprintOverride(input);
			if (critTap != null) {
				input = critTap;
				overridden = true;
			}

			// Separate layer because Axe Crit runs from its own toggle.
			PlayerInput axeCritTap = com.profps.client.instants.AxeCritController
					.critSprintOverride(input);
			if (axeCritTap != null) {
				input = axeCritTap;
				overridden = true;
			}

			// Sprinting hits above 0.9 charge add knockback, pushing the target out of mace range.
			PlayerInput stunTap = com.profps.client.instants.AutoMaceController
					.stunSprintOverride(input);
			if (stunTap != null) {
				input = stunTap;
				overridden = true;
			}

			// Applied last: movement resolves against the body rotation, not the view.
			PlayerInput silent = SilentAimController.movementOverride(input);
			if (silent != null) {
				input = silent;
				overridden = true;
			}

			if (overridden) {
				self.profps$setPlayerInput(input);
				self.profps$setMovementVector(vectorFor(input));
			}
		}
	}

	/** Mirror of vanilla KeyboardInput: Vec2f(sideways, forward), normalized. */
	private static Vec2f vectorFor(PlayerInput input) {
		float f = input.forward() == input.backward() ? 0.0F : (input.forward() ? 1.0F : -1.0F);
		float g = input.left() == input.right() ? 0.0F : (input.left() ? 1.0F : -1.0F);
		if (f == 0.0F && g == 0.0F) return Vec2f.ZERO;
		return new Vec2f(g, f).normalize();
	}
}
