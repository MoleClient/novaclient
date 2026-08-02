package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the melee auto-attack modules (triggerbot, AutoMace) at the EXACT point vanilla
 * processes a left-click: the tail of {@link MinecraftClient#handleInputEvents()}.
 *
 * <p>This runs before the player ticks and before {@code sendMovementPackets()}, so the
 * attack + swing packets are sent BEFORE this tick's flying packet (vanilla order) — which
 * is what defeats Grim's "Post" check. Just as important, it's the SAME tick phase vanilla
 * attacks in: before the player's movement is simulated for the tick, so the attack's
 * sprint-reset / knockback prediction lines up with what the server's movement checks
 * expect. The previous injection at {@code sendMovementPackets} HEAD fired a phase later
 * (after movement was already computed), which nudged that prediction and produced the odd
 * timer / simulation flags.
 */
@Mixin(MinecraftClient.class)
public abstract class TriggerbotPreMovementMixin {

	@Inject(method = "handleInputEvents", at = @At("TAIL"))
	private void profps$fireMeleeAtVanillaClickTime(CallbackInfo ci) {
		ProFPSClient.firePreMovement((MinecraftClient) (Object) this);
	}
}
