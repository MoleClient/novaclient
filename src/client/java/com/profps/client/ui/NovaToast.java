package com.profps.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Module alert popup drawn on the vanilla advancement toast frame. */
public final class NovaToast implements Toast {
	private static final Identifier TEXTURE = Identifier.ofVanilla("toast/advancement");
	private static final long DISPLAY_MS = 5000L;

	private final ItemStack icon;
	private final Text title;
	private final Text description;
	private final int titleColor;
	private final int width;
	private Visibility visibility = Visibility.SHOW;

	public NovaToast(ItemStack icon, Text title, Text description, int titleColor) {
		this.icon = icon;
		this.title = title;
		this.description = description;
		this.titleColor = titleColor;
		// Text starts at x=30, so widen the frame to fit the longest line.
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		int textWidth = Math.max(tr.getWidth(title), tr.getWidth(description));
		this.width = Math.max(Toast.BASE_WIDTH, 30 + textWidth + 8);
	}

	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public Visibility getVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long time) {
		visibility = time >= DISPLAY_MS * manager.getNotificationDisplayTimeMultiplier()
				? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, getWidth(), getHeight());
		context.drawText(textRenderer, title, 30, 7, titleColor, false);
		context.drawText(textRenderer, description, 30, 18, 0xFFFFFFFF, false);
		context.drawItem(icon, 8, 8);
	}
}
