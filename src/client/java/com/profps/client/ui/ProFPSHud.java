package com.profps.client.ui;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.nova.NovaRender;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * Top-left FPS readout, styled to match the Nova UI: "FPS" in orange and the number
 * in white, in the UI's own rounded font, sitting in a soft grey rounded pill like
 * the rest of the panel chrome.
 */
public final class ProFPSHud implements HudRenderCallback {
	private static final int ORANGE = 0xFFFF9D2E; // "FPS"
	private static final int WHITE = 0xFFFFFFFF;   // the number
	private static final int BOX = 0xD22D2D34;     // grayish translucent pill, like the UI
	private static final int BORDER = 0x1AFFFFFF;
	private static final Style FONT = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova_bold")));
	private static final float SCALE = 1.4F;

	private final ProFPSConfig config;

	public ProFPSHud(ProFPSConfig config) {
		this.config = config;
	}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden || !config.enabled) {
			return;
		}

		TextRenderer tr = client.textRenderer;
		Text label = Text.literal("FPS").setStyle(FONT);
		Text number = Text.literal(String.valueOf(client.getCurrentFps())).setStyle(FONT);

		float labelW = tr.getWidth(label) * SCALE;
		float numberW = tr.getWidth(number) * SCALE;
		float gap = 5.0F * SCALE;
		float padX = 8.0F;
		float padY = 5.0F;
		float textH = tr.fontHeight * SCALE;

		float x = 5.0F, y = 5.0F;
		float w = padX * 2 + labelW + gap + numberW;
		float h = padY * 2 + textH;
		float radius = h * 0.32F;

		NovaRender.setAlpha(1.0F);
		NovaRender.glow(context, x, y, w, h, radius, 0x000000, 55); // soft drop shadow
		NovaRender.roundRect(context, x, y, w, h, radius, BOX);
		NovaRender.roundRectBorder(context, x, y, w, h, radius, BORDER);

		drawScaled(context, tr, label, x + padX, y + padY, ORANGE);
		drawScaled(context, tr, number, x + padX + labelW + gap, y + padY, WHITE);
	}

	private void drawScaled(DrawContext context, TextRenderer tr, Text text, float x, float y, int color) {
		Matrix3x2fStack m = context.getMatrices();
		m.pushMatrix();
		m.scale(SCALE, SCALE);
		context.drawText(tr, text, Math.round(x / SCALE), Math.round(y / SCALE), color, false);
		m.popMatrix();
	}
}
