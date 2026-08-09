package com.profps.client.mixin;

import com.profps.client.assists.HitImprovementsController;
import com.profps.client.assists.StrafeImprovementsController;
import com.profps.client.ai.SwordAiController;
import com.profps.client.aim.SilentAimController;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.extras.SchematicBuildController;
import com.profps.client.extras.ScaffoldController;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overrides the local player's movement AFTER the keyboard has been read.
 *
 * <ul>
 *   <li><b>Tunnel controlling</b> — drive the body from the bot's published
 *       {@link PlayerInput}, so it keeps tunnelling even while Freecam reads the
 *       raw keybindings to fly the camera (no WASD tug-of-war).</li>
 *   <li><b>Freecam (no tunnel)</b> — freeze the body so WASD only moves the
 *       camera.</li>
 * </ul>
 *
 * <p>Targets {@link KeyboardInput#tick()} — the override the player actually
 * runs. The previous {@code Input.tick} target never fired, because
 * KeyboardInput overrides tick without calling super, so the freeze was a no-op
 * and the body only stayed put by brute-force repositioning (which is what made
 * it — and the chunks streamed around it — glitch). The movement fields live on
 * the {@code Input} superclass, so they're written through {@link InputAccessor}
 * rather than a (illegal) cross-class field shadow.
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
			self.profps$setPlayerInput(PlayerInput.DEFAULT);
			self.profps$setMovementVector(Vec2f.ZERO);
		} else {
			PlayerInput input = self.profps$getPlayerInput();
			boolean overridden = false;
			PlayerInput scaffold = ScaffoldController.movementOverride(input);
			if (scaffold != null) {
				input = scaffold;
				overridden = true;
			}

			boolean schematicMoving = SchematicBuildController.isAutoMoving();
			boolean manualMovement = input.forward() || input.backward() || input.left() || input.right()
					|| input.jump() || input.sneak();
			if (schematicMoving) {
				// Any physical movement key immediately wins. Do not let a lower-priority
				// combat controller replace that manual escape input on this same frame.
				if (!manualMovement) {
					input = SchematicBuildController.movementInput();
					overridden = true;
				}
			} else if (SwordAiController.isControlling()) {
				PlayerInput ai = SwordAiController.movementInput(input);
				if (ai != null) {
					input = ai;
					overridden = true;
				}
			} else if (StrafeImprovementsController.isStrafing()) {
				// Layer a juke step onto the player's own input: drop forward, step to
				// the side (and back), keep their jump/sneak.
				PlayerInput juke = StrafeImprovementsController.strafeOverride(input);
				if (juke != null) {
					input = juke;
					overridden = true;
				}
			}

			// Sword mode may add only the vanilla sprint-key bit while the player is
			// already holding forward. It yields during the post-hit retreat and never
			// creates movement by itself.
			if (!schematicMoving) {
				PlayerInput swordSprint = StrafeImprovementsController.swordSprintOverride(input);
				if (swordSprint != null) {
					input = swordSprint;
					overridden = true;
				}

				// Triggerbot normal-hit prep is layered last, so its brief W+sprint tap
				// travels through the same real input path even when AI/Strafe supplied
				// the base movement. Manual retreat, jump, and sneak remain authoritative.
				PlayerInput normalHit = HitImprovementsController.normalHitOverride(input);
				if (normalHit != null) {
					input = normalHit;
					overridden = true;
				}

				// The crit W-tap comes after the sprint prep on purpose: a sprint
				// established for a ground hit must still be dropped once the
				// player is airborne, or the swing cannot crit at all.
				PlayerInput critTap = HitImprovementsController.critSprintOverride(input);
				if (critTap != null) {
					input = critTap;
					overridden = true;
				}

				// Axe Crit drops the sprint for the same reason and by the same
				// means, but it runs from its own switch, so it needs its own
				// layer: the Triggerbot's crit timing may well be off.
				PlayerInput axeCritTap = com.profps.client.instants.AxeCritController
						.critSprintOverride(input);
				if (axeCritTap != null) {
					input = axeCritTap;
					overridden = true;
				}

				// The stun-slam drops the sprint for its axe tap for a different
				// reason: a sprinting hit above 0.9 charge pays a knockback bonus,
				// and that bonus is what used to shove the target out of reach
				// before the mace could land.
				PlayerInput stunTap = com.profps.client.instants.AutoMaceController
						.stunSprintOverride(input);
				if (stunTap != null) {
					input = stunTap;
					overridden = true;
				}
			}

			// Silent aim turns the body away from where the player is looking, and
			// walking resolves against the body. Re-pick the keys last, on top of
			// whatever produced this input, so the player still travels the way
			// the view faces.
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
