package com.profps.client.ui.nova;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.profps.ProFPS;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Lazily-baked antialiased sprite atlas for the Nova GUI shapes, so each shape draws as one quad.
 * Cells (64px each): filled disc, radial glow, corner TL, TR, BL, BR.
 */
final class NovaSprites {
	static final Identifier ATLAS_ID = Identifier.of(ProFPS.MOD_ID, "nova_shapes");
	static final int CELL = 64;
	static final int ATLAS_W = CELL * 6;
	static final int ATLAS_H = CELL;
	static final int DISC_U = 0;
	static final int GLOW_U = CELL;
	static final int CORNER_TL_U = CELL * 2;
	static final int CORNER_TR_U = CELL * 3;
	static final int CORNER_BL_U = CELL * 4;
	static final int CORNER_BR_U = CELL * 5;
	/** Disc/glow radius inside the cell; the remaining margin stops bilinear bleed. */
	static final float DISC_RADIUS = 30.0F;

	private static boolean registered;

	private NovaSprites() {}

	static void ensureRegistered() {
		if (registered) return;
		registered = true;

		NativeImage image = new NativeImage(ATLAS_W, ATLAS_H, true);
		bakeDisc(image);
		bakeGlow(image);
		bakeCorners(image);

		NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "profps nova shape atlas", image) {
			@Override
			public GpuSampler getSampler() {
				// LINEAR keeps shapes smooth at any drawn size.
				return RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
			}
		};
		texture.upload();
		MinecraftClient.getInstance().getTextureManager().registerTexture(ATLAS_ID, texture);
	}

	private static void bakeDisc(NativeImage image) {
		float c = (CELL - 1) / 2.0F;
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				float d = (float) Math.hypot(x - c, y - c);
				float cov = MathHelper.clamp(DISC_RADIUS - d + 0.5F, 0.0F, 1.0F);
				image.setColorArgb(DISC_U + x, y, white(cov));
			}
		}
	}

	private static void bakeGlow(NativeImage image) {
		float c = (CELL - 1) / 2.0F;
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				float t = (float) Math.hypot(x - c, y - c) / DISC_RADIUS;
				float fall = MathHelper.clamp(1.0F - t, 0.0F, 1.0F);
				image.setColorArgb(GLOW_U + x, y, white(fall * fall));
			}
		}
	}

	/** Bakes the four quarter-disc corner cells; arc radius is the full cell, so a cell scaled to r x r is a radius-r corner. */
	private static void bakeCorners(NativeImage image) {
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				float d = (float) Math.hypot(CELL - (x + 0.5F), CELL - (y + 0.5F));
				int color = white(MathHelper.clamp(CELL - d + 0.5F, 0.0F, 1.0F));
				image.setColorArgb(CORNER_TL_U + x, y, color);
				image.setColorArgb(CORNER_TR_U + (CELL - 1 - x), y, color);
				image.setColorArgb(CORNER_BL_U + x, CELL - 1 - y, color);
				image.setColorArgb(CORNER_BR_U + (CELL - 1 - x), CELL - 1 - y, color);
			}
		}
	}

	private static int white(float coverage) {
		return (Math.round(coverage * 255.0F) << 24) | 0xFFFFFF;
	}
}
