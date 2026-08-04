package com.profps.client.mixin;

import com.profps.client.donutsmp.BasicEspRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Colours the Mob ESP outline. Vanilla tints the outline pass with the entity's
 * team colour, so this is the supported way to choose it: red for hostile, green
 * for passive.
 *
 * <p>Scoped to entities Mob ESP is actually outlining, so a real scoreboard team
 * colour is never overwritten for anything else that reads this value.
 */
@Mixin(Entity.class)
public abstract class EntityTeamColorMixin {
	@Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
	private void profps$espOutlineColor(CallbackInfoReturnable<Integer> cir) {
		Entity self = (Entity) (Object) this;
		if (BasicEspRenderer.shouldOutline(self)) {
			cir.setReturnValue(BasicEspRenderer.outlineColor(self));
		}
	}
}
