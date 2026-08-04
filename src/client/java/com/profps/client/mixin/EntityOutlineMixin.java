package com.profps.client.mixin;

import com.profps.client.donutsmp.BasicEspRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes Mob ESP targets into vanilla's entity-outline pass.
 *
 * <p>Vanilla already knows how to trace an entity's exact rendered model and
 * show that silhouette through terrain — it is what a glowing mob looks like.
 * Marking our targets here reuses that whole pipeline, so the outline matches
 * the model's current pose instead of approximating it with a box, and it costs
 * no extra geometry.
 */
@Mixin(MinecraftClient.class)
public abstract class EntityOutlineMixin {
	@Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
	private void profps$outlineEspTargets(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		// Only ever add outlines. Returning false here would suppress a genuine
		// glowing entity that the game itself wants drawn.
		if (BasicEspRenderer.shouldOutline(entity)) cir.setReturnValue(true);
	}
}
