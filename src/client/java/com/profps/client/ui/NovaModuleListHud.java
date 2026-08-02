package com.profps.client.ui;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.nova.NovaModules;
import com.profps.client.ui.nova.NovaRender;
import com.profps.client.ui.nova.NovaTheme;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-screen enabled-modules list: a stack of small boxes hugging the screen
 * edge, one per active module, so you can see what's running without opening the
 * panel. Widest name first (ties alphabetical), so the stack tapers as it goes
 * down. Each box carries a thin theme-accent bar on its outer edge; the bar colour
 * grades soft → deep down the list.
 *
 * <p>Built to be effectively free per frame: enabled state is folded into one hash
 * and the visible list is only re-sorted when that hash changes; name Texts and
 * pixel widths are cached; steady-state rendering allocates nothing and draws a
 * handful of quads per row. Rows slide in/out horizontally and glide vertically on
 * reorder (both snap when GUI animations are off).
 */
public final class NovaModuleListHud implements HudRenderCallback {
	private static final Style FONT_BOLD = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova_bold")));

	private static final float TEXT_SCALE = 0.9F;
	private static final float ROW_H = 13.0F;
	private static final float ROW_GAP = 1.0F;
	private static final float BAR_W = 2.0F;
	private static final float PAD_TEXT = 4.0F;  // gap between the accent bar and the name
	private static final float PAD_END = 5.0F;   // padding after the name
	private static final float MARGIN = 4.0F;    // distance from the screen corner
	private static final int ROW_BG = 0xB40A0C10;
	private static final int TEXT_COLOR = 0xF0F4F9;

	/** One tracked row. Lives while its module is on, then slides out and is dropped. */
	private static final class Entry {
		final NovaModules.Module module;
		float slide;    // 0..1 in/out progress (drives the horizontal offset + fade)
		float y;        // animated top position
		float targetY;
		int rank;       // position in the sorted list (drives the accent gradient)
		boolean active;
		boolean positioned;

		Entry(NovaModules.Module module) {
			this.module = module;
		}
	}

	private final ProFPSConfig config;
	private final List<NovaModules.Module> modules;          // flat, deduped catalogue
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Map<String, Text> nameCache = new HashMap<>();
	private final Map<String, Float> widthCache = new HashMap<>();
	private final List<Entry> visible = new ArrayList<>();   // sorted actives, rebuilt on change only
	private int lastSignature;
	private long lastFrameNanos;

	public NovaModuleListHud(ProFPSConfig config, List<NovaModules.Category> categories) {
		this.config = config;
		Map<String, NovaModules.Module> flat = new LinkedHashMap<>();
		for (NovaModules.Category cat : categories) {
			for (NovaModules.Module mod : cat.modules) {
				flat.putIfAbsent(mod.id, mod);
			}
		}
		this.modules = List.copyOf(flat.values());
	}

	@Override
	public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		long now = System.nanoTime();
		float dt = MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
		lastFrameNanos = now;

		if (client.options.hudHidden || !config.enabled || !config.hudModuleList) {
			// Drop all state while hidden so re-enabling slides the list in fresh.
			if (!entries.isEmpty()) {
				entries.clear();
				visible.clear();
				lastSignature = 0;
			}
			return;
		}

		TextRenderer tr = client.textRenderer;

		// Cheap change detection: fold every module's enabled bit into one hash and
		// only re-sort when it moves.
		int signature = 1;
		for (NovaModules.Module mod : modules) {
			signature = signature * 31 + (listed(mod) ? 1 : 0);
		}
		if (signature != lastSignature) {
			lastSignature = signature;
			rebuildVisible(tr);
		}
		if (entries.isEmpty()) return;

		float easeSlide = config.guiAnimations ? Math.min(1.0F, dt * 12.0F) : 1.0F;
		float easeGlide = config.guiAnimations ? Math.min(1.0F, dt * 16.0F) : 1.0F;
		int[] accent = NovaTheme.accent(config.guiAccent);
		int count = Math.max(1, visible.size());
		boolean right = config.hudModuleListRight;
		int screenW = ctx.getScaledWindowWidth();

		Iterator<Entry> it = entries.values().iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			e.slide += ((e.active ? 1.0F : 0.0F) - e.slide) * easeSlide;
			if (!e.active && e.slide < 0.01F) {
				it.remove();
				continue;
			}
			e.y += (e.targetY - e.y) * easeGlide;

			float p = smooth(MathHelper.clamp(e.slide, 0.0F, 1.0F));
			if (p <= 0.004F) continue;
			float textW = nameWidth(tr, e.module);
			float rowW = BAR_W + PAD_TEXT + textW + PAD_END;
			float off = (1.0F - p) * (rowW + 8.0F);
			float x = right ? screenW - MARGIN - rowW + off : MARGIN - off;
			float t = MathHelper.clamp(count <= 1 ? 0.0F : e.rank / (float) (count - 1), 0.0F, 1.0F);
			int barColor = NovaRender.lerpColor(t, accent[0], accent[2]);

			NovaRender.setAlpha(p);
			NovaRender.roundRect(ctx, x, e.y, rowW, ROW_H, 2.0F, ROW_BG);
			// Accent bar on the outer (screen-side) edge, in front of the name.
			float barX = right ? x + rowW - BAR_W : x;
			NovaRender.roundRect(ctx, barX, e.y, BAR_W, ROW_H, 1.0F, barColor);
			float textX = right ? x + PAD_END : x + BAR_W + PAD_TEXT;
			drawName(ctx, tr, name(e.module), textX, e.y + (ROW_H - 9.0F * TEXT_SCALE) / 2.0F, p);
		}
		NovaRender.setAlpha(1.0F);
	}

	/** Re-sorts the active rows (widest name first, ties A→Z) and reassigns stack slots. */
	private void rebuildVisible(TextRenderer tr) {
		visible.clear();
		for (NovaModules.Module mod : modules) {
			Entry e = entries.get(mod.id);
			if (listed(mod)) {
				if (e == null) {
					e = new Entry(mod);
					entries.put(mod.id, e);
				}
				e.active = true;
				visible.add(e);
			} else if (e != null) {
				e.active = false;
			}
		}
		visible.sort((a, b) -> {
			int cmp = Float.compare(nameWidth(tr, b.module), nameWidth(tr, a.module));
			return cmp != 0 ? cmp : a.module.name.compareTo(b.module.name);
		});
		for (int i = 0; i < visible.size(); i++) {
			Entry e = visible.get(i);
			e.rank = i;
			e.targetY = MARGIN + i * (ROW_H + ROW_GAP);
			if (!e.positioned) {
				e.y = e.targetY;
				e.positioned = true;
			}
		}
	}

	/**
	 * Whether this module gets its own row. A combat mode hides the standalone modules it has taken
	 * over, so the list reads "Mace Mode" rather than spelling out everything that mode is running.
	 */
	private boolean listed(NovaModules.Module mod) {
		return mod.get.get() && NovaModules.managedState(config, mod.id) == null;
	}

	private Text name(NovaModules.Module mod) {
		return nameCache.computeIfAbsent(mod.id, id -> Text.literal(mod.name).setStyle(FONT_BOLD));
	}

	private float nameWidth(TextRenderer tr, NovaModules.Module mod) {
		Float cached = widthCache.get(mod.id);
		if (cached != null) return cached;
		float w = tr.getWidth(name(mod)) * TEXT_SCALE;
		widthCache.put(mod.id, w);
		return w;
	}

	private void drawName(DrawContext ctx, TextRenderer tr, Text text, float x, float y, float alpha) {
		int a = (int) (alpha * 255.0F);
		if (a < 8) return;
		// Float translate keeps the name moving sub-pixel smooth with its box while sliding.
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(TEXT_SCALE, TEXT_SCALE);
		ctx.drawText(tr, text, 0, 0, (a << 24) | TEXT_COLOR, false);
		m.popMatrix();
	}

	private static float smooth(float v) {
		return v * v * (3.0F - 2.0F * v);
	}
}
