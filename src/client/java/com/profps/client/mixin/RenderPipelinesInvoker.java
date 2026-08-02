package com.profps.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Registers custom pipelines before Minecraft's resource reload compiles them. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesInvoker {
	@Invoker("register")
	static RenderPipeline profps$register(RenderPipeline pipeline) {
		throw new AssertionError();
	}
}
