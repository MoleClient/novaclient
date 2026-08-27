package com.profps.client.ui.nova;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

/**
 * Shape renderer for the Nova GUI, drawing from the {@link NovaSprites} atlas.
 * All geometry is positioned in a 2x supersampled matrix and tinted by a global alpha multiplier.
 */
public final class NovaRender {
	private static final int S = 2;
	/** Maps an on-screen radius to atlas cell size; the disc fills 60/64 of its cell. */
	private static final float DISC_CELL_SCALE = NovaSprites.CELL / (2.0F * NovaSprites.DISC_RADIUS);
	private static float alphaMul = 1.0F;

	private NovaRender() {}

	public static void setAlpha(float alpha) {
		alphaMul = MathHelper.clamp(alpha, 0.0F, 1.0F);
	}

	public static float getAlpha() {
		return alphaMul;
	}

	/** Applies the global fade to a packed ARGB colour. */
	public static int alpha(int argb) {
		int a = (int) ((argb >>> 24) * alphaMul);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public static int withAlpha(int argb, int a) {
		return (MathHelper.clamp(a, 0, 255) << 24) | (argb & 0xFFFFFF);
	}

	public static int lerpColor(float t, int from, int to) {
		t = MathHelper.clamp(t, 0.0F, 1.0F);
		int a = (int) MathHelper.lerp(t, from >>> 24, to >>> 24);
		int r = (int) MathHelper.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
		int g = (int) MathHelper.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
		int b = (int) MathHelper.lerp(t, from & 0xFF, to & 0xFF);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** One tinted quad from the shape atlas, in S-scaled coordinates. */
	private static void sprite(DrawContext ctx, int x, int y, int w, int h, int u, int argb) {
		// Half-texel inset so bilinear sampling does not bleed the neighbouring cell.
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, NovaSprites.ATLAS_ID, x, y,
				u + 0.5F, 0.5F, w, h, NovaSprites.CELL - 1, NovaSprites.CELL - 1,
				NovaSprites.ATLAS_W, NovaSprites.ATLAS_H, argb);
	}

	public static void roundRect(DrawContext ctx, float x, float y, float w, float h, float r, int argb) {
		roundRectGradient(ctx, x, y, w, h, r, argb, argb);
	}

	/** Rounded rectangle with a vertical colour gradient. */
	public static void roundRectGradient(DrawContext ctx, float x, float y, float w, float h, float r, int top, int bottom) {
		top = alpha(top);
		bottom = alpha(bottom);
		if ((top >>> 24) == 0 && (bottom >>> 24) == 0) return;
		NovaSprites.ensureRegistered();

		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(1.0F / S, 1.0F / S);
		int bx = Math.round(x * S);
		int by = Math.round(y * S);
		int bw = Math.round(w * S);
		int bh = Math.round(h * S);
		int br = Math.round(Math.min(r * S, Math.min(bw, bh) / 2.0F));

		if (br <= 0) {
			band(ctx, bx, by, bx + bw, by + bh, top, bottom, bh, 0, bh);
		} else {
			// Corners are flat-tinted with the gradient colour at their band centre.
			int cTop = lerpColor(br * 0.5F / bh, top, bottom);
			int cBottom = lerpColor((bh - br * 0.5F) / bh, top, bottom);
			sprite(ctx, bx, by, br, br, NovaSprites.CORNER_TL_U, cTop);
			sprite(ctx, bx + bw - br, by, br, br, NovaSprites.CORNER_TR_U, cTop);
			sprite(ctx, bx, by + bh - br, br, br, NovaSprites.CORNER_BL_U, cBottom);
			sprite(ctx, bx + bw - br, by + bh - br, br, br, NovaSprites.CORNER_BR_U, cBottom);
			if (bw - 2 * br > 0) {
				band(ctx, bx + br, by, bx + bw - br, by + br, top, bottom, bh, 0, br);
				band(ctx, bx + br, by + bh - br, bx + bw - br, by + bh, top, bottom, bh, bh - br, bh);
			}
			if (bh - 2 * br > 0) {
				band(ctx, bx, by + br, bx + bw, by + bh - br, top, bottom, bh, br, bh - br);
			}
		}
		m.popMatrix();
	}

	/** One horizontal slice of a vertical gradient. */
	private static void band(DrawContext ctx, int x1, int y1, int x2, int y2,
			int top, int bottom, int totalH, int bandY1, int bandY2) {
		if (x2 <= x1 || y2 <= y1) return;
		int c1 = lerpColor(bandY1 / (float) totalH, top, bottom);
		int c2 = lerpColor(bandY2 / (float) totalH, top, bottom);
		if (c1 == c2) {
			ctx.fill(x1, y1, x2, y2, c1);
		} else {
			ctx.fillGradient(x1, y1, x2, y2, c1, c2);
		}
	}

	/** One-pixel rounded outline. */
	public static void roundRectBorder(DrawContext ctx, float x, float y, float w, float h, float r, int argb) {
		argb = alpha(argb);
		if ((argb >>> 24) == 0) return;

		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(1.0F / S, 1.0F / S);
		int bx = Math.round(x * S);
		int by = Math.round(y * S);
		int bw = Math.round(w * S);
		int bh = Math.round(h * S);
		int br = Math.round(Math.min(r * S, Math.min(bw, bh) / 2.0F));
		int t = S;

		ctx.fill(bx + br, by, bx + bw - br, by + t, argb);
		ctx.fill(bx + br, by + bh - t, bx + bw - br, by + bh, argb);
		ctx.fill(bx, by + br, bx + t, by + bh - br, argb);
		ctx.fill(bx + bw - t, by + br, bx + bw, by + bh - br, argb);

		for (int dy = 0; dy < br; dy++) {
			float k = br - dy - 0.5F;
			float inset = br - (float) Math.sqrt(Math.max(0.0F, (float) br * br - k * k));
			int ii = Math.round(inset);
			int seg = Math.max(t, Math.min(br - ii, t + 1));
			int top = by + dy;
			int bottom = by + bh - 1 - dy;
			ctx.fill(bx + ii, top, bx + ii + seg, top + 1, argb);
			ctx.fill(bx + bw - ii - seg, top, bx + bw - ii, top + 1, argb);
			ctx.fill(bx + ii, bottom, bx + ii + seg, bottom + 1, argb);
			ctx.fill(bx + bw - ii - seg, bottom, bx + bw - ii, bottom + 1, argb);
		}
		m.popMatrix();
	}

	/** Filled antialiased circle. */
	public static void fillCircle(DrawContext ctx, float cx, float cy, float r, int argb) {
		argb = alpha(argb);
		if ((argb >>> 24) == 0) return;
		NovaSprites.ensureRegistered();

		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(1.0F / S, 1.0F / S);
		float rr = r * S * DISC_CELL_SCALE;
		int d = Math.max(2, Math.round(rr * 2.0F));
		sprite(ctx, Math.round(cx * S - rr), Math.round(cy * S - rr), d, d, NovaSprites.DISC_U, argb);
		m.popMatrix();
	}

	/** Circle outline. */
	public static void ring(DrawContext ctx, float cx, float cy, float r, float thickness, int argb) {
		argb = alpha(argb);
		if ((argb >>> 24) == 0) return;

		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(1.0F / S, 1.0F / S);
		int bx = Math.round(cx * S);
		int by = Math.round(cy * S);
		int ro = Math.round(r * S);
		int ri = Math.max(0, Math.round((r - thickness) * S));

		for (int dy = -ro; dy < ro; dy++) {
			float k = dy + 0.5F;
			float outer = (float) Math.sqrt(Math.max(0.0F, (float) ro * ro - k * k));
			float inner = Math.abs(k) >= ri ? 0.0F : (float) Math.sqrt(Math.max(0.0F, (float) ri * ri - k * k));
			int ho = Math.round(outer);
			int hiI = Math.round(inner);
			if (ho <= 0) continue;
			if (hiI <= 0) {
				ctx.fill(bx - ho, by + dy, bx + ho, by + dy + 1, argb);
			} else {
				ctx.fill(bx - ho, by + dy, bx - hiI, by + dy + 1, argb);
				ctx.fill(bx + hiI, by + dy, bx + ho, by + dy + 1, argb);
			}
		}
		m.popMatrix();
	}

	/** Soft outer glow behind a rounded rect; draw before the rect itself. */
	public static void glow(DrawContext ctx, float x, float y, float w, float h, float r, int rgb, int strength) {
		roundRect(ctx, x - 3, y - 3, w + 6, h + 6, r + 3, withAlpha(rgb, strength / 3));
		roundRect(ctx, x - 1, y - 1, w + 2, h + 2, r + 1, withAlpha(rgb, strength / 2));
	}

	/** Radial halo behind small widgets. */
	public static void glowCircle(DrawContext ctx, float cx, float cy, float r, int rgb, int strength) {
		int argb = alpha(withAlpha(rgb, strength));
		if ((argb >>> 24) == 0) return;
		NovaSprites.ensureRegistered();

		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(1.0F / S, 1.0F / S);
		float rr = (r + 2.5F) * 1.7F * S * DISC_CELL_SCALE;
		int d = Math.max(2, Math.round(rr * 2.0F));
		sprite(ctx, Math.round(cx * S - rr), Math.round(cy * S - rr), d, d, NovaSprites.GLOW_U, argb);
		m.popMatrix();
	}
}
