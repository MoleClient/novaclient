package com.profps.client.packet;

import com.profps.ProFPS;
import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.nova.NovaRender;
import com.profps.client.ui.nova.NovaScreenV2;
import com.profps.client.ui.nova.NovaTheme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/** In-GUI Packet Utils toolbar rendered over open screens; packet work lives in {@link PacketManager}. */
public final class PacketOverlay {
	public static final PacketOverlay INSTANCE = new PacketOverlay();

	private static final Style FONT = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova")));
	private static final Style FONT_BOLD = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova_bold")));

	private static final int PANEL_BG_TOP = 0xF2101216;
	private static final int PANEL_BG_BOT = 0xF2070809;
	private static final int CARD_BG = 0xFF0D0F13;
	private static final int CARD_HOVER = 0xFF141A22;
	private static final int BORDER = 0x1EFFFFFF;
	private static final int TEXT = 0xFFEAF1F7;
	private static final int MUTED = 0xFF8B94A2;
	private static final int FAINT = 0xFF6A7382;
	private static final int TRACK_OFF = 0xFF1B212B;

	private static final float MARGIN = 6.0F;
	private static final float PANEL_W = 160.0F;
	private static final float PAD = 9.0F;
	private static final float TITLE_H = 30.0F;
	private static final float STATUS_H = 10.0F;
	private static final float SECTION_GAP = 8.0F;
	private static final float BTN_H = 19.0F;
	private static final float BTN_GAP = 4.0F;
	private static final float SW_H = 18.0F;
	private static final float SW_GAP = 4.0F;
	private static final float CHAT_H = 20.0F;
	private static final float FAB_LABEL_H = 12.0F;
	private static final float FAB_ROW_H = 18.0F;
	private static final float FAB_GAP = 4.0F;

	private static final String[] FAB_ACTION_NAMES = {"PICKUP", "QUICK_MOVE", "THROW", "SWAP", "CLONE"};
	private static final SlotActionType[] FAB_ACTIONS = {
			SlotActionType.PICKUP, SlotActionType.QUICK_MOVE, SlotActionType.THROW,
			SlotActionType.SWAP, SlotActionType.CLONE
	};

	private static final int G_CLOSE = 0, G_DESYNC = 1, G_SAVE = 2, G_DISCONNECT = 3, G_FABRICATE = 4, G_COPY = 5;

	private enum Focus { NONE, CHAT, FAB_SLOT, FAB_BUTTON }

	private final MinecraftClient mc = MinecraftClient.getInstance();
	private final java.util.Map<String, Float> anims = new HashMap<>();
	private final List<Zone> zones = new ArrayList<>();

	private long lastNanos;
	private float frameDelta;

	private float panelX, panelY, panelW, panelH;

	private Focus focus = Focus.NONE;
	private String chatText = "";
	private boolean fabOpen;
	private String fabSlot = "0";
	private String fabButton = "0";
	private int fabAction = 0;

	private String status = "";
	private long statusUntil;

	private int accSoft = 0xFF7FD8FF, accBase = 0xFF38BDF8, accDeep = 0xFF0EA5E9;

	private PacketOverlay() {}

	public static PacketOverlay get() {
		return INSTANCE;
	}

	/** Registers the per-screen render and input hooks; call once at client init. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen == null) return;
			ScreenEvents.afterRender(screen).register((scr, ctx, mouseX, mouseY, delta) -> {
				if (shouldShow(scr)) INSTANCE.render(ctx, mouseX, mouseY);
			});
			ScreenMouseEvents.allowMouseClick(screen).register((scr, click) -> {
				if (!shouldShow(scr)) return true;
				return !INSTANCE.mouseClicked(click);   // returning false cancels the vanilla click
			});
			ScreenKeyboardEvents.allowKeyPress(screen).register((scr, keyInput) -> {
				if (!shouldShow(scr)) return true;
				return !INSTANCE.keyPressed(keyInput);
			});
		});
	}

	/** True for any in-world screen except the Nova UIs and the chat screen. */
	public static boolean shouldShow(Screen screen) {
		if (!PacketManager.INSTANCE.active()) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return false;
		return !(screen instanceof NovaScreenV2)
				&& !(screen instanceof ChatScreen);
	}

	private void render(DrawContext ctx, int mouseX, int mouseY) {
		try {
			long now = System.nanoTime();
			frameDelta = MathHelper.clamp((now - lastNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
			lastNanos = now;
			NovaRender.setAlpha(1.0F);
			zones.clear();

			ProFPSConfig cfg = ProFPSClient.config();
			int[] theme = NovaTheme.accent(cfg == null ? NovaTheme.DEFAULT : cfg.guiAccent);
			accSoft = theme[0];
			accBase = theme[1];
			accDeep = theme[2];

			panelW = PANEL_W;
			panelH = computeHeight();
			panelX = MARGIN;
			panelY = MARGIN;

			NovaRender.glow(ctx, panelX, panelY, panelW, panelH, 12, 0x000000, 70);
			NovaRender.roundRectGradient(ctx, panelX, panelY, panelW, panelH, 11, PANEL_BG_TOP, PANEL_BG_BOT);
			NovaRender.roundRectBorder(ctx, panelX, panelY, panelW, panelH, 11, BORDER);
			ctx.fill((int) (panelX + 12), (int) (panelY + 1), (int) (panelX + panelW - 12), (int) (panelY + 2), 0x0AFFFFFF);

			float x = panelX + PAD;
			float w = panelW - PAD * 2;
			float y = panelY + PAD;

			y = drawTitle(ctx, x, y, w);
			y = drawStatus(ctx, x, y, w);
			y += SECTION_GAP;

			y = drawButton(ctx, mouseX, mouseY, x, y, w, "Close (no packet)", G_CLOSE, false, click -> {
				PacketManager.INSTANCE.closeWithoutPacket();
				setStatus("Closed — no packet");
			});
			y += BTN_GAP;
			boolean desync = PacketManager.INSTANCE.desyncActive;
			y = drawButton(ctx, mouseX, mouseY, x, y, w, desync ? "Re-sync (blink)" : "De-sync (blink)", G_DESYNC, desync, click -> {
				PacketManager.INSTANCE.toggleDesync();
				setStatus(PacketManager.INSTANCE.desyncActive ? "De-synced — holding" : "Re-synced — flushed");
			});
			y += BTN_GAP;
			y = drawButton(ctx, mouseX, mouseY, x, y, w, "Save GUI", G_SAVE, false, click -> {
				PacketManager.INSTANCE.saveGui();
				setStatus("Saved this GUI");
			});
			y += BTN_GAP;
			y = drawButton(ctx, mouseX, mouseY, x, y, w, "Disconnect & flush", G_DISCONNECT, false, click -> {
				PacketManager.INSTANCE.disconnectAndSend();
			});
			y += BTN_GAP;
			y = drawButton(ctx, mouseX, mouseY, x, y, w, "Fabricate packet", G_FABRICATE, fabOpen, click -> {
				fabOpen = !fabOpen;
				if (!fabOpen && (focus == Focus.FAB_SLOT || focus == Focus.FAB_BUTTON)) focus = Focus.NONE;
				sound(1.05F);
			});
			y += BTN_GAP;
			y = drawButton(ctx, mouseX, mouseY, x, y, w, "Copy title JSON", G_COPY, false, click -> {
				String json = PacketManager.INSTANCE.currentTitleJson();
				mc.keyboard.setClipboard(json);
				setStatus("Copied title JSON");
			});
			y += SECTION_GAP;

			y = drawSwitch(ctx, mouseX, mouseY, x, y, w, "Send packets", PacketManager.INSTANCE.sendPackets, click -> {
				PacketManager.INSTANCE.setSendPackets(!PacketManager.INSTANCE.sendPackets);
				setStatus(PacketManager.INSTANCE.sendPackets ? "Sending packets" : "Silenced — dropping");
			});
			y += SW_GAP;
			boolean delay = PacketManager.INSTANCE.delayPackets;
			y = drawSwitch(ctx, mouseX, mouseY, x, y, w, "Delay packets", delay, click -> {
				PacketManager.INSTANCE.setDelayPackets(!PacketManager.INSTANCE.delayPackets);
				setStatus(PacketManager.INSTANCE.delayPackets ? "Delaying — holding" : "Released queue");
			});
			y += SECTION_GAP;

			if (fabOpen) {
				y = drawFabricator(ctx, mouseX, mouseY, x, y, w);
			}

			drawChatField(ctx, mouseX, mouseY, x, y, w);
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Packet Utils overlay render failed.", exception);
		}
	}

	private float computeHeight() {
		float buttons = 6 * BTN_H + 5 * BTN_GAP;
		float switches = 2 * SW_H + SW_GAP;
		float fab = fabOpen ? (FAB_LABEL_H + FAB_ROW_H + FAB_GAP + FAB_ROW_H + FAB_GAP) : 0.0F;
		return PAD + TITLE_H + STATUS_H + SECTION_GAP + buttons + SECTION_GAP + switches + SECTION_GAP + fab + CHAT_H + PAD;
	}

	private float drawTitle(DrawContext ctx, float x, float y, float w) {
		drawPaperGlyph(ctx, x, y + 1);
		text(ctx, "Packet Utils", x + 15, y + 2, TEXT, true);
		int held = PacketManager.INSTANCE.heldCount();
		String sub = "Sync " + PacketManager.INSTANCE.currentSyncId()
				+ " · Rev " + PacketManager.INSTANCE.currentRevision()
				+ (held > 0 ? " · " + held + " held" : "");
		textScaled(ctx, sub, x + 1, y + 16, held > 0 ? accSoft : FAINT, 0.74F, false);
		return y + TITLE_H;
	}

	private float drawStatus(DrawContext ctx, float x, float y, float w) {
		if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
			textScaled(ctx, status, x + 1, y, accSoft, 0.78F, false);
		}
		return y + STATUS_H;
	}

	private float drawButton(DrawContext ctx, int mx, int my, float x, float y, float w,
			String label, int glyph, boolean active, Consumer<Click> onClick) {
		boolean hovered = inside(mx, my, x, y, w, BTN_H);
		float hov = anim("btn_" + label, hovered ? 1.0F : 0.0F, 14.0F);
		float act = anim("btna_" + label, active ? 1.0F : 0.0F, 12.0F);

		if (act > 0.05F) NovaRender.glow(ctx, x, y, w, BTN_H, 6, accBase & 0xFFFFFF, (int) (26 * act));
		int bg = NovaRender.lerpColor(hov, CARD_BG, CARD_HOVER);
		bg = NovaRender.lerpColor(act, bg, withA(accDeep, 0xF0));
		NovaRender.roundRect(ctx, x, y, w, BTN_H, 6, bg);
		NovaRender.roundRectBorder(ctx, x, y, w, BTN_H, 6,
				NovaRender.lerpColor(Math.max(act, hov * 0.6F), BORDER, withA(accBase, 0xCC)));

		int glyphColor = act > 0.5F ? 0xFFFFFFFF : (hovered ? TEXT : accSoft);
		drawGlyph(ctx, glyph, x + 11, y + BTN_H / 2.0F, glyphColor);
		int labelColor = act > 0.5F ? 0xFFFFFFFF : (hovered ? TEXT : MUTED);
		textScaled(ctx, label, x + 22, y + (BTN_H - 6) / 2.0F - 0.5F, labelColor, 0.82F, true);

		zones.add(new Zone(x, y, w, BTN_H, click -> {
			onClick.accept(click);
			sound(1.05F);
		}));
		return y + BTN_H;
	}

	private float drawSwitch(DrawContext ctx, int mx, int my, float x, float y, float w,
			String label, boolean value, Consumer<Click> onToggle) {
		boolean hovered = inside(mx, my, x, y, w, SW_H);
		float en = anim("sw_" + label, value ? 1.0F : 0.0F, 11.0F);
		int bg = NovaRender.lerpColor(hovered ? 1.0F : 0.0F, CARD_BG, CARD_HOVER);
		NovaRender.roundRect(ctx, x, y, w, SW_H, 6, bg);
		NovaRender.roundRectBorder(ctx, x, y, w, SW_H, 6, NovaRender.lerpColor(en, BORDER, withA(accBase, 0x55)));
		textScaled(ctx, label, x + 9, y + (SW_H - 6) / 2.0F - 0.5F, value ? TEXT : MUTED, 0.82F, true);

		float pillW = 26, pillH = 13;
		float pillX = x + w - 9 - pillW;
		float pillY = y + (SW_H - pillH) / 2.0F;
		drawPill(ctx, en, pillX, pillY, pillW, pillH);

		zones.add(new Zone(x, y, w, SW_H, click -> {
			onToggle.accept(click);
			sound(value ? 0.85F : 1.15F);
		}));
		return y + SW_H;
	}

	private void drawPill(DrawContext ctx, float en, float x, float y, float w, float h) {
		if (en > 0.05F) NovaRender.glow(ctx, x, y, w, h, h / 2, accBase & 0xFFFFFF, (int) (40 * en));
		NovaRender.roundRectGradient(ctx, x, y, w, h, h / 2,
				NovaRender.lerpColor(en, TRACK_OFF, accSoft), NovaRender.lerpColor(en, TRACK_OFF, accDeep));
		float knobX = x + h / 2.0F + en * (w - h);
		NovaRender.fillCircle(ctx, knobX, y + h / 2.0F, h / 2.0F - 2.2F, 0xFFFFFFFF);
	}

	private float drawFabricator(DrawContext ctx, int mx, int my, float x, float y, float w) {
		textScaled(ctx, "FABRICATE SLOT CLICK", x + 1, y, FAINT, 0.72F, true);
		y += FAB_LABEL_H;

		float half = (w - 6) / 2.0F;
		drawMiniField(ctx, mx, my, x, y, half, "Slot", fabSlot, Focus.FAB_SLOT);
		drawMiniField(ctx, mx, my, x + half + 6, y, half, "Btn", fabButton, Focus.FAB_BUTTON);
		y += FAB_ROW_H + FAB_GAP;

		float actionW = w * 0.56F;
		boolean actionHover = inside(mx, my, x, y, actionW, FAB_ROW_H);
		NovaRender.roundRect(ctx, x, y, actionW, FAB_ROW_H, 5, actionHover ? CARD_HOVER : CARD_BG);
		NovaRender.roundRectBorder(ctx, x, y, actionW, FAB_ROW_H, 5, BORDER);
		textScaled(ctx, FAB_ACTION_NAMES[fabAction], x + 7, y + (FAB_ROW_H - 6) / 2.0F - 0.5F, accSoft, 0.76F, true);
		textScaled(ctx, "‹ ›", x + actionW - 15, y + (FAB_ROW_H - 6) / 2.0F - 0.5F, MUTED, 0.76F, false);
		zones.add(new Zone(x, y, actionW, FAB_ROW_H, click -> {
			int dir = click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1;
			fabAction = (fabAction + dir + FAB_ACTIONS.length) % FAB_ACTIONS.length;
			sound(1.0F);
		}));

		float sendX = x + actionW + 6;
		float sendW = w - actionW - 6;
		boolean sendHover = inside(mx, my, sendX, y, sendW, FAB_ROW_H);
		if (sendHover) NovaRender.glow(ctx, sendX, y, sendW, FAB_ROW_H, 5, accBase & 0xFFFFFF, 40);
		NovaRender.roundRectGradient(ctx, sendX, y, sendW, FAB_ROW_H, 5, accSoft, accDeep);
		String send = "Send";
		float sendTw = mc.textRenderer.getWidth(Text.literal(send).setStyle(FONT_BOLD)) * 0.82F;
		textScaled(ctx, send, sendX + (sendW - sendTw) / 2.0F, y + (FAB_ROW_H - 6) / 2.0F - 0.5F, 0xFF06131E, 0.82F, true);
		zones.add(new Zone(sendX, y, sendW, FAB_ROW_H, click -> {
			int slot = parseIntOr(fabSlot, 0);
			int button = parseIntOr(fabButton, 0);
			PacketManager.INSTANCE.fabricateClick(slot, button, FAB_ACTIONS[fabAction]);
			setStatus("Fabricated " + FAB_ACTION_NAMES[fabAction] + " s" + slot);
			sound(1.2F);
		}));
		return y + FAB_ROW_H + FAB_GAP;
	}

	private void drawMiniField(DrawContext ctx, int mx, int my, float x, float y, float w, String label, String value, Focus which) {
		boolean focused = focus == which;
		NovaRender.roundRect(ctx, x, y, w, FAB_ROW_H, 5, 0xFF10151C);
		NovaRender.roundRectBorder(ctx, x, y, w, FAB_ROW_H, 5, focused ? withA(accBase, 0xCC) : BORDER);
		textScaled(ctx, label, x + 6, y + (FAB_ROW_H - 6) / 2.0F - 0.5F, FAINT, 0.72F, false);
		String shown = value + (focused && blink() ? "_" : "");
		float vw = mc.textRenderer.getWidth(Text.literal(shown).setStyle(FONT)) * 0.78F;
		textScaled(ctx, shown, x + w - 8 - vw, y + (FAB_ROW_H - 6) / 2.0F - 0.5F, TEXT, 0.78F, false);
		zones.add(new Zone(x, y, w, FAB_ROW_H, click -> {
			focus = which;
			sound(1.05F);
		}));
	}

	private void drawChatField(DrawContext ctx, int mx, int my, float x, float y, float w) {
		boolean focused = focus == Focus.CHAT;
		NovaRender.roundRect(ctx, x, y, w, CHAT_H, 6, 0xFF0E1116);
		NovaRender.roundRectBorder(ctx, x, y, w, CHAT_H, 6, focused ? withA(accBase, 0xCC) : BORDER);
		NovaRender.roundRect(ctx, x + 6, y + 6, 8, 6, 2, focused ? accSoft : MUTED);
		ctx.fill((int) (x + 8), (int) (y + 12), (int) (x + 10), (int) (y + 14), focused ? accSoft : MUTED);

		boolean empty = chatText.isEmpty() && !focused;
		String shown = empty ? "Chat / command…" : chatText + (focused && blink() ? "_" : "");
		// Trim from the left so the caret end stays visible when the text overflows.
		while (mc.textRenderer.getWidth(Text.literal(shown).setStyle(FONT)) * 0.82F > w - 24 && shown.length() > 1) {
			shown = shown.substring(1);
		}
		textScaled(ctx, shown, x + 18, y + (CHAT_H - 6) / 2.0F - 0.5F, empty ? FAINT : TEXT, 0.82F, false);
		zones.add(new Zone(x, y, w, CHAT_H, click -> {
			focus = Focus.CHAT;
			sound(1.05F);
		}));
	}

	private void drawPaperGlyph(DrawContext ctx, float x, float y) {
		NovaRender.roundRect(ctx, x, y, 9, 11, 1.5F, 0xFFE7ECF3);
		ctx.fill((int) (x + 5.5F), (int) y, (int) (x + 9), (int) (y + 3.5F), withA(accBase, 0xFF));
		ctx.fill((int) (x + 2), (int) (y + 5), (int) (x + 7), (int) (y + 6), 0xFF9AA4B2);
		ctx.fill((int) (x + 2), (int) (y + 7), (int) (x + 7), (int) (y + 8), 0xFF9AA4B2);
	}

	private void drawGlyph(DrawContext ctx, int kind, float cx, float cy, int color) {
		switch (kind) {
			case G_CLOSE -> { // X
				Matrix3x2fStack m = ctx.getMatrices();
				m.pushMatrix();
				m.translate(cx, cy);
				m.rotate((float) (Math.PI / 4.0));
				ctx.fill(-4, -1, 4, 0, color);
				ctx.fill(-1, -4, 0, 4, color);
				m.popMatrix();
			}
			case G_DESYNC -> { // zigzag
				ctx.fill((int) (cx - 4), (int) (cy - 3), (int) (cx - 1), (int) (cy - 2), color);
				ctx.fill((int) (cx - 1), (int) (cy - 2), (int) cx, (int) (cy + 1), color);
				ctx.fill((int) cx, (int) (cy + 1), (int) (cx + 4), (int) (cy + 2), color);
			}
			case G_SAVE -> { // down arrow into a tray
				ctx.fill((int) (cx - 1), (int) (cy - 4), (int) (cx + 1), (int) (cy + 1), color);
				for (int i = 0; i < 3; i++) ctx.fill((int) (cx - 3 + i), (int) (cy - 1 + i), (int) (cx + 3 - i), (int) (cy + i), color);
				ctx.fill((int) (cx - 4), (int) (cy + 3), (int) (cx + 4), (int) (cy + 4), color);
			}
			case G_DISCONNECT -> { // plug: circle with two prongs
				NovaRender.ring(ctx, cx, cy, 3.4F, 1.3F, color);
				ctx.fill((int) (cx + 3), (int) (cy - 1), (int) (cx + 6), (int) cy, color);
				ctx.fill((int) (cx + 3), (int) (cy + 1), (int) (cx + 6), (int) (cy + 2), color);
			}
			case G_FABRICATE -> { // four-point spark
				ctx.fill((int) (cx - 1), (int) (cy - 4), (int) (cx + 1), (int) (cy + 4), color);
				ctx.fill((int) (cx - 4), (int) (cy - 1), (int) (cx + 4), (int) (cy + 1), color);
				NovaRender.fillCircle(ctx, cx, cy, 1.4F, color);
			}
			case G_COPY -> { // overlapping pages
				NovaRender.roundRectBorder(ctx, cx - 4, cy - 3, 6, 7, 1.5F, color);
				NovaRender.roundRect(ctx, cx - 1.5F, cy - 0.5F, 6, 7, 1.5F, CARD_BG);
				NovaRender.roundRectBorder(ctx, cx - 1.5F, cy - 0.5F, 6, 7, 1.5F, color);
			}
			default -> { }
		}
	}

	/** Returns true if the overlay consumed the click. */
	public boolean mouseClicked(Click click) {
		double mx = click.x(), my = click.y();
		for (int i = zones.size() - 1; i >= 0; i--) {
			Zone zone = zones.get(i);
			if (zone.hit(mx, my)) {
				zone.action.accept(click);
				return true;
			}
		}
		if (inside(mx, my, panelX, panelY, panelW, panelH)) {
			focus = Focus.NONE;
			return true;                    // swallow panel clicks so they do not reach the slots below
		}
		focus = Focus.NONE;
		return false;
	}

	/** Returns true if the overlay consumed the key. */
	public boolean keyPressed(KeyInput input) {
		if (focus == Focus.NONE) return false;
		int key = input.key();
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			focus = Focus.NONE;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			if (focus == Focus.CHAT) {
				PacketManager.INSTANCE.sendChat(chatText);
				setStatus(chatText.startsWith("/") ? "Ran command" : "Sent message");
				chatText = "";
			}
			focus = Focus.NONE;
			return true;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			switch (focus) {
				case CHAT -> { if (!chatText.isEmpty()) chatText = chatText.substring(0, chatText.length() - 1); }
				case FAB_SLOT -> { if (!fabSlot.isEmpty()) fabSlot = fabSlot.substring(0, fabSlot.length() - 1); }
				case FAB_BUTTON -> { if (!fabButton.isEmpty()) fabButton = fabButton.substring(0, fabButton.length() - 1); }
				default -> { }
			}
			return true;
		}
		// Swallow every other key while a field is focused.
		return true;
	}

	/** Returns true if the overlay consumed the typed character. */
	public boolean charTyped(CharInput input) {
		if (focus == Focus.NONE) return false;
		String s = input.asString();
		if (s == null || s.isEmpty()) return true;
		char c = s.charAt(0);
		switch (focus) {
			case CHAT -> { if (chatText.length() < 256 && (c == ' ' || !Character.isISOControl(c))) chatText += s; }
			case FAB_SLOT -> { if (fabSlot.length() < 4 && (Character.isDigit(c) || c == '-')) fabSlot += s; }
			case FAB_BUTTON -> { if (fabButton.length() < 2 && Character.isDigit(c)) fabButton += s; }
			default -> { }
		}
		return true;
	}

	private void setStatus(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 1800L;
	}

	private boolean blink() {
		return (System.currentTimeMillis() / 480) % 2 == 0;
	}

	private static int parseIntOr(String s, int fallback) {
		try {
			return s == null || s.isEmpty() || s.equals("-") ? fallback : Integer.parseInt(s.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static int withA(int argb, int a) {
		return (MathHelper.clamp(a, 0, 255) << 24) | (argb & 0xFFFFFF);
	}

	private float anim(String key, float target, float speed) {
		ProFPSConfig cfg = ProFPSClient.config();
		if (cfg != null && !cfg.guiAnimations) {
			anims.put(key, target);
			return target;
		}
		float v = anims.computeIfAbsent(key, k -> target);
		v += (target - v) * Math.min(1.0F, frameDelta * speed);
		if (Math.abs(target - v) < 0.002F) v = target;
		anims.put(key, v);
		return v;
	}

	private boolean inside(double mx, double my, double x, double y, double w, double h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private void sound(float pitch) {
		ProFPSConfig cfg = ProFPSClient.config();
		if (cfg != null && !cfg.guiSounds) return;
		mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, pitch));
	}

	private void text(DrawContext ctx, String s, float x, float y, int color, boolean bold) {
		ctx.drawText(mc.textRenderer, Text.literal(s).setStyle(bold ? FONT_BOLD : FONT), (int) x, (int) y, color, false);
	}

	private void textScaled(DrawContext ctx, String s, float x, float y, int color, float scale, boolean bold) {
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(scale, scale);
		ctx.drawText(mc.textRenderer, Text.literal(s).setStyle(bold ? FONT_BOLD : FONT),
				Math.round(x / scale), Math.round(y / scale), color, false);
		m.popMatrix();
	}

	private record Zone(float x, float y, float w, float h, Consumer<Click> action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}
}
