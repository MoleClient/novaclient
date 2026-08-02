package com.profps.client.mixin;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderLayer.class)
public interface RenderLayerInvoker {
	@Invoker("of")
	static RenderLayer profps$of(String name, RenderSetup setup) {
		throw new AssertionError();
	}
}
