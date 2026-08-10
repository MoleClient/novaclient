package com.profps.client.ui.nova;

import com.profps.ProFPS;
import com.profps.client.config.NickEntry;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.packet.PacketManager;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NovaClient control panel — experimental column layout ("Use Experimental UI").
 *
 * <p>Every category is its own column: a header pill on top, a scrollable panel of
 * module rows below, and one search field centred under the grid. Combat modes sit
 * at the very top of their category, above a hairline, so the thing that takes over
 * half the category is read first.
 *
 * <p>The palette is deliberately monochrome — graphite surfaces, white for "on".
 * Colour is spent only where it means something: the LT5→HT1 tier ramp on a combat
 * mode, and the tier colour bleeding onto the modules that mode has taken over.
 * Multi-choice settings (the tier ramp, the block picker) drop down inline as an
 * option list with a dot on the right rather than opening a popup, so a column
 * never covers its neighbours.
 */
public final class NovaScreenV2 extends Screen {
	// ── Fonts ────────────────────────────────────────────────────────────────
	private static final Style FONT = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova")));
	private static final Style FONT_BOLD = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova_bold")));
	private static final Style FONT_TITLE = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova_title")));

	// ── Palette ────────────────────────────────────────────────────────────────
	// One graphite ladder with real distance between the rungs: the column body sits
	// well below the world, a live row lifts clearly off it, and the category header
	// caps the stack. White is the only "on" colour.
	private static final int PANEL_TOP = 0xF1121317;   // column body, barely-there top light
	private static final int PANEL_BOT = 0xF10C0D10;
	private static final int PANEL_LINE = 0x14FFFFFF;  // hairline around a column
	private static final int HEAD_TOP = 0xF7343842;    // category header pill
	private static final int HEAD_BOT = 0xF72A2D34;
	private static final int HEAD_TOP_HI = 0xF73F444E;
	private static final int HEAD_BOT_HI = 0xF7343841;
	private static final int HEAD_LINE = 0x22FFFFFF;
	private static final int SURFACE = 0xFF2A2D34;     // a live (on or open) module row
	private static final int SURFACE_HI = 0xFF32363E;
	private static final int ROW_HOVER = 0xFF1F2126;   // hover on a resting row
	private static final int ROW_LINE = 0x16FFFFFF;
	private static final int WELL = 0xFF0B0C0F;        // fields, key chips, dropdown wells
	private static final int DIVIDER = 0x14FFFFFF;
	private static final int TEXT = 0xFFF5F6F8;
	private static final int TEXT_OFF = 0xFF9DA2AC;    // a resting module name
	private static final int FAINT = 0xFF757A84;       // setting labels, values
	private static final int GHOST = 0xFF4B505A;       // grips, empty dots
	private static final int TRACK = 0xFF313540;       // toggle / slider track
	private static final int TRACK_ON = 0xFF545A66;
	private static final int KNOB_OFF = 0xFF757B86;
	private static final int LOCKED = 0xFF585D67;      // a module the running mode owns
	private static final int BRAND = 0xFFFFFFFF;

	// Type scale — every label in the grid uses one of these.
	private static final float T_HEAD = 0.84F;  // category header
	private static final float T_MOD = 0.82F;   // module name
	private static final float T_SET = 0.75F;   // setting label
	private static final float T_VAL = 0.72F;   // setting value, chips
	private static final float T_OPT = 0.70F;   // dropdown option

	// ── Layout ───────────────────────────────────────────────────────────────
	private static final float COL_W = 132.0F;
	private static final float COL_GAP = 6.0F;
	private static final float TITLE_H = 30.0F;
	private static final float TITLE_GAP = 17.0F;
	private static final float HEAD_H = 32.0F;
	private static final float HEAD_GAP = 11.0F;
	private static final float SEARCH_GAP = 24.0F;
	private static final float SEARCH_H = 26.0F;
	private static final float SEARCH_W = 200.0F;
	private static final float PILL_W = 17.0F;
	private static final float PILL_H = 9.0F;
	private static final float PAD = 5.0F;       // column inner padding
	private static final float ROW_H = 21.0F;
	/** A module whose name needs two lines gets a taller row rather than a clipped name. */
	private static final float ROW_H_WRAP = 30.0F;
	private static final float ROW_GAP = 2.0F;
	private static final float SCROLL_STEP = 24.0F;
	private static final float MODE_GAP = 10.0F; // space + hairline under the mode band
	/** Design height the grid is fitted to; the width side comes from the column count. */
	private static final float DESIGN_H = 596.0F;

	/** Wordmark suffix. One line to bump when the build rolls over. */
	private static final String BUILD_LABEL = "Beta06 General";
	/** How long "Client" holds each theme colour, and how much of that is the crossfade. */
	private static final long CYCLE_MS = 10_000L;
	private static final float CYCLE_FADE_MS = 1_400.0F;

	/** Settings panel, unfolded from under the wordmark. */
	private static final float PANEL_SETTINGS_W = 436.0F;

	// Expanded-setting row metrics.
	private static final float SET_H = 14.0F;
	private static final float SLIDER_H = 9.0F;
	private static final float OPT_H = 11.0F;
	private static final float FIELD_H = 18.0F;
	private static final float NICK_H = 15.0F;
	private static final float NICK_ADD_H = 15.0F;
	private static final float PICKER_H = 80.0F;
	private static final float PICKER_ROW_H = 12.0F;

	private static final String[] MODE_TIERS = {
			"LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "LT2", "HT2", "LT1", "HT1"
	};
	private static final int[] MODE_TIER_COLORS = {
			0xFF3A3D45, 0xFFC6CAD1, 0xFF456D4D, 0xFF176B3A, 0xFF98703A,
			0xFFE2B84F, 0xFF8A5CC2, 0xFFF0C85B, 0xFFF28A35, 0xFFEF3340
	};

	/**
	 * Categories that carry a logo PNG instead of an item icon in their header. The pixel size
	 * matters: the draw samples a full-texture region, so a wrong size crops the logo down to its
	 * top-left corner — which is what turned SubTiers (28px, not 256) into a blank square.
	 */
	private record CategoryLogo(Identifier texture, int size) {}

	private static final Map<String, CategoryLogo> CATEGORY_LOGOS = Map.of(
			"Mace & Spear", new CategoryLogo(Identifier.of(ProFPS.MOD_ID, "textures/gui/cat_mace.png"), 256),
			"DonutSMP", new CategoryLogo(Identifier.of(ProFPS.MOD_ID, "textures/gui/cat_donut.png"), 256),
			"Hypixel", new CategoryLogo(Identifier.of(ProFPS.MOD_ID, "textures/gui/cat_hypixel.png"), 256),
			"SubTiers", new CategoryLogo(Identifier.of(ProFPS.MOD_ID, "textures/gui/cat_subtiers.png"), 28));

	private final ProFPSConfig config;
	private final List<NovaModules.Category> categories;

	// ── UI state ─────────────────────────────────────────────────────────────
	private final Map<String, Float> anims = new HashMap<>();
	/** Keys already stepped this frame — see {@link #anim(String, float, float)}. */
	private final Map<String, Float> frameAnims = new HashMap<>();
	private final List<Zone> zones = new ArrayList<>();
	private final Map<String, Text> textCache = new HashMap<>();
	private final Map<NickEntry, NovaModules.StringSetting[]> nickFieldCache = new IdentityHashMap<>();
	/** Module ids whose settings are dropped down. */
	private final Set<String> expanded = new HashSet<>();
	private final Map<String, String[]> nameLineCache = new HashMap<>();
	/** Category indices whose column is rolled up to just its header. */
	private final Set<Integer> rolled = new HashSet<>();
	/** Inline dropdowns that are open, keyed {@code moduleId|label}. Multi-choice settings start open. */
	private final Set<String> closedDrops = new HashSet<>();

	private float[] scroll = new float[0];
	private float[] scrollTarget = new float[0];
	/** How far each column can actually scroll — the wheel is clamped to it on the way in. */
	private float[] scrollMax = new float[0];

	private String query = "";
	private boolean searchFocused;
	private String listeningId;
	/**
	 * The GLFW key whose keystroke was spent on a keybind, or -1. Held by key
	 * identity rather than as a one-shot flag so that holding the key — which
	 * repeats both callbacks — cannot leak the repeats into a text field either.
	 */
	private int bindingConsumedKey = -1;
	private NovaModules.IntSetting activeSlider;
	private float sliderTrackX, sliderTrackW;
	private NovaModules.StringSetting activeString;

	// The one block picker in the catalogue (BreakOn "Certain Blocks").
	private String pickerQuery = "";
	private boolean pickerFocused;
	private float pickerScroll;
	private final List<Block> pickerMatches = new ArrayList<>();
	private String pickerMatchesFor;
	private float pickerListX, pickerListY, pickerListW, pickerListH;

	private String noticeText;
	private long noticeUntilNanos;

	private String hoverId;
	private String frameHover;
	private long hoverSinceNanos;
	private String tipText;
	private float tipX, tipY;

	private long openedMs;
	private long lastFrameNanos;
	private float frameDelta;

	// Theme, reloaded each frame from config.guiAccent so both panels always agree.
	private int accentBase = 0xFF38BDF8;
	private int accentSoft = 0xFF7FD8FF;
	private int accentDeep = 0xFF0EA5E9;
	/** True while the settings panel is up; its reveal is eased from the wordmark anchor below. */
	private boolean settingsOpen;
	private SettingsPage settingsPage = SettingsPage.SETTINGS;
	private float themeAnchorX, themeAnchorY;
	private float settingsScroll, settingsScrollTarget, settingsScrollMax;
	private float settingsBodyX, settingsBodyY, settingsBodyW, settingsBodyH;
	private String configNameInput = "";
	private boolean configNameFocused;
	private String configStatus = "";
	private List<String> profileCache = new ArrayList<>();

	// Geometry, recomputed every frame in virtual (pre-scale) units.
	private float uiScale = 1.0F;
	private float vw, vh;
	private float gridX, titleY, gridTop, panelY, maxPanelH;
	private float searchX, searchY;
	/** Drawn height of each column this frame — every column ends where its own list ends. */
	private float[] colH = new float[0];
	// Clip rect applied to zones registered right now (w <= 0 = unclipped).
	private float zcx, zcy, zcw, zch;

	public NovaScreenV2(ProFPSConfig config, List<NovaModules.Category> categories) {
		super(Text.literal("NovaClient"));
		this.config = config;
		this.categories = categories;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		openedMs = System.currentTimeMillis();
		lastFrameNanos = System.nanoTime();
		if (scroll.length != categories.size()) {
			scroll = new float[categories.size()];
			scrollTarget = new float[categories.size()];
			scrollMax = new float[categories.size()];
			colH = new float[categories.size()];
		}
		NovaSprites.ensureRegistered();
	}

	// ── Frame ────────────────────────────────────────────────────────────────

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		Matrix3x2fStack matrices = ctx.getMatrices();
		boolean scaled = false;
		try {
			long now = System.nanoTime();
			frameDelta = MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
			lastFrameNanos = now;

			applyTheme();
			layout();
			matrices.pushMatrix();
			matrices.scale(uiScale, uiScale);
			scaled = true;

			float mx = mouseX / uiScale;
			float my = mouseY / uiScale;
			// Snaps open and settles — reads as instant rather than a symmetric fade.
			float open = easeOutCubic(MathHelper.clamp((System.currentTimeMillis() - openedMs) / 190.0F, 0.0F, 1.0F));
			zones.clear();
			frameAnims.clear();
			tipText = null;
			frameHover = null;
			// Cleared per frame: a stale rect would keep stealing the wheel after its module closed.
			pickerListW = 0.0F;
			NovaRender.setAlpha(open);
			// The wordmark leads, then the columns deal themselves in left to right — see
			// columnAppear(). The whole thing lands inside a third of a second.
			matrices.pushMatrix();
			matrices.translate(0.0F, (1.0F - open) * 8.0F);

			drawWordmark(ctx, mx, my);
			for (int i = 0; i < categories.size(); i++) {
				drawColumn(ctx, mx, my, i);
			}
			drawSearchBar(ctx, mx, my);
			drawSettingsPanel(ctx, mx, my);
			// Leaving every row restarts the dwell, so the tip does not reappear instantly next time.
			if (frameHover == null) hoverId = null;
			if (!settingsOpen) drawTooltip(ctx);
			drawNotice(ctx);

			matrices.popMatrix();
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Nova experimental GUI render failed; closing the screen to prevent a client crash.", exception);
			close();
		} finally {
			if (scaled) matrices.popMatrix();
			NovaRender.setAlpha(1.0F);
		}
	}

	/**
	 * Fits the whole grid — headers, columns and search — inside the window. The grid width is
	 * driven by the category count, so adding a category shrinks everything rather than pushing a
	 * column off the edge. A manual UI size only ever shrinks it further.
	 */
	private void layout() {
		float gridW = gridWidth();
		float fit = Math.min(width / (gridW + 60.0F), height / DESIGN_H);
		float desired = config.guiAutoScale
				? fit
				: fit * MathHelper.clamp(config.guiScalePct, NovaUiScale.MIN_MANUAL_PERCENT,
						NovaUiScale.MAX_MANUAL_PERCENT) / 100.0F;
		uiScale = MathHelper.clamp(Math.min(desired, fit), 0.15F, 4.0F);

		vw = width / uiScale;
		vh = height / uiScale;
		gridX = (vw - gridW) / 2.0F;

		// Search rides in the header, under the wordmark and above the category row. It used to sit
		// beneath the grid, which put it level with the bottom of the ONE tall column and stranded
		// it far below every short one. Up here it is level with the thing it filters and never
		// moves, whatever the columns do.
		float chrome = TITLE_H + TITLE_GAP + SEARCH_H + SEARCH_GAP + HEAD_H + HEAD_GAP;
		maxPanelH = MathHelper.clamp(vh * 0.66F, 120.0F, 468.0F);
		if (chrome + maxPanelH > vh - 20.0F) maxPanelH = Math.max(80.0F, vh - 20.0F - chrome);
		titleY = (vh - (chrome + maxPanelH)) / 2.0F;
		searchY = titleY + TITLE_H + TITLE_GAP;
		gridTop = searchY + SEARCH_H + SEARCH_GAP;
		panelY = gridTop + HEAD_H + HEAD_GAP;
		searchX = (vw - SEARCH_W) / 2.0F;
	}

	private float columnX(int index) {
		return gridX + index * (COL_W + COL_GAP);
	}

	/**
	 * "Client" drifts through the theme presets on a ten-second cycle, crossfading rather than
	 * cutting. It is the only thing on screen that moves on its own, which is what marks it as the
	 * handle for the theme picker without needing a label saying so.
	 */
	private int cyclingBrandColor() {
		int presets = NovaTheme.ACCENT_PRESETS.length;
		// Measured from when the panel opened, not from the epoch, so it always starts on the
		// default ice blue rather than wherever the wall clock happened to land.
		long since = Math.max(0L, System.currentTimeMillis() - openedMs);
		float t = (since % (CYCLE_MS * presets)) / (float) CYCLE_MS;
		int from = NovaTheme.DEFAULT + (int) t;
		float blend = MathHelper.clamp((t - from) * CYCLE_MS / CYCLE_FADE_MS, 0.0F, 1.0F);
		int a = 0xFF000000 | NovaTheme.ACCENT_PRESETS[from % presets][0];
		int b = 0xFF000000 | NovaTheme.ACCENT_PRESETS[(from + 1) % presets][0];
		return NovaRender.lerpColor(smooth(blend), a, b);
	}

	/** The wordmark over the grid: NovaClient set in the title face, with the build on a chip. */
	private void drawWordmark(DrawContext ctx, float mx, float my) {
		Text nova = cachedText("t:", "Nova", FONT_TITLE);
		Text client = cachedText("t:", "Client", FONT_TITLE);
		float novaW = textRenderer.getWidth(nova);
		float clientW = textRenderer.getWidth(client);

		Text build = bold(BUILD_LABEL.toUpperCase(Locale.ROOT));
		float buildW = textRenderer.getWidth(build) * T_VAL;
		float chipW = buildW + 13.0F;
		float chipH = 15.0F;
		float gap = 10.0F;

		float x = (vw - (novaW + clientW + gap + chipW)) / 2.0F;
		float cy = titleY + TITLE_H / 2.0F;
		float clientX = x + novaW;
		float chipX = x + novaW + clientW + gap;
		// "Client" and the build chip are one control: either one opens the settings panel.
		boolean hovered = inside(mx, my, clientX - 2.0F, cy - 9.0F, clientW + 4.0F, 18.0F)
				|| inside(mx, my, chipX, cy - chipH / 2.0F, chipW, chipH);
		float hov = anim("brand_hover", hovered || settingsOpen ? 1.0F : 0.0F, 14.0F);

		// The title face sits on the same baseline as everything else, so a 15px wordmark rides
		// high in its line box — nudge it down to sit optically centred on the chip beside it.
		float ty = cy - 5.0F;
		text(ctx, nova, x, ty, BRAND);
		int cycled = cyclingBrandColor();
		text(ctx, client, clientX, ty, NovaRender.lerpColor(hov * 0.55F, cycled, 0xFFFFFFFF));
		// An underline that draws itself in on hover — the click target, stated quietly.
		if (hov > 0.01F) {
			NovaRender.roundRect(ctx, clientX + clientW * (1.0F - hov) / 2.0F, cy + 8.0F,
					clientW * hov, 1.2F, 0.6F, NovaRender.withAlpha(cycled & 0xFFFFFF, (int) (0xCC * hov)));
		}

		NovaRender.roundRect(ctx, chipX, cy - chipH / 2.0F, chipW, chipH, chipH / 2.0F,
				NovaRender.lerpColor(hov, 0x14FFFFFF, accentA(0x3A)));
		NovaRender.roundRectBorder(ctx, chipX, cy - chipH / 2.0F, chipW, chipH, chipH / 2.0F,
				NovaRender.lerpColor(hov, 0x1CFFFFFF, accentA(0x8C)));
		textScaled(ctx, build, chipX + (chipW - buildW) / 2.0F, cy - 3.0F,
				NovaRender.lerpColor(hov, 0xFFAAB0BA, 0xFFFFFFFF), T_VAL);

		themeAnchorX = (clientX + chipX + chipW) / 2.0F;
		themeAnchorY = cy + 13.0F;
		zone(clientX - 3.0F, cy - 9.0F, (chipX + chipW) - clientX + 6.0F, 18.0F, click -> {
			settingsOpen = !settingsOpen;
			if (settingsOpen) profileCache = ProFPSConfig.listProfiles();
			else closeSettings();
			sound(settingsOpen ? 1.14F : 0.9F);
		});
	}

	private float gridWidth() {
		int n = Math.max(1, categories.size());
		return n * COL_W + (n - 1) * COL_GAP;
	}

	// ── Column ───────────────────────────────────────────────────────────────

	/** Per-column entrance: a short stagger so the grid deals itself in instead of blinking on. */
	private float columnAppear(int index) {
		if (!config.guiAnimations) return 1.0F;
		long elapsed = System.currentTimeMillis() - openedMs - index * 30L;
		return easeOutCubic(MathHelper.clamp(elapsed / 260.0F, 0.0F, 1.0F));
	}

	private void drawColumn(DrawContext ctx, float mx, float my, int index) {
		NovaModules.Category category = categories.get(index);
		List<NovaModules.Module> shown = visibleModules(category);
		float x = columnX(index);

		// A column with nothing left after the search recedes instead of sitting there empty.
		float previousAlpha = NovaRender.getAlpha();
		float dim = anim("coldim_" + index, shown.isEmpty() ? 0.34F : 1.0F, 13.0F);
		float appear = columnAppear(index);
		NovaRender.setAlpha(previousAlpha * dim * appear);

		Matrix3x2fStack m = ctx.getMatrices();
		boolean lifted = appear < 0.999F;
		if (lifted) {
			m.pushMatrix();
			m.translate(0.0F, (1.0F - appear) * 18.0F);
		}

		drawCategoryHeader(ctx, mx, my, index, category, x);

		// Each column is exactly as long as its own list — a five-module category has no business
		// being as tall as Combat — and it stops growing once it would run past the search field.
		// Height tracks the row heights directly rather than through a spring of its own: those
		// heights are already eased, so following them exactly is what keeps the panel edge locked
		// to the bottom of the list while a module drops down. A second spring would lag the
		// content and shave the last row off.
		float fitted = shown.isEmpty() ? 0.0F
				: MathHelper.clamp(listHeight(shown) + PAD * 2.0F, 30.0F, maxPanelH);
		float roll = anim("colroll_" + index, rolled.contains(index) ? 0.0F : 1.0F, 12.0F);
		float h = fitted * roll;
		colH[index] = h;
		if (h > 1.5F) {
			NovaRender.roundRectGradient(ctx, x, panelY, COL_W, h, 10, PANEL_TOP, PANEL_BOT);
			NovaRender.roundRectBorder(ctx, x, panelY, COL_W, h, 10, PANEL_LINE);
			drawModuleList(ctx, mx, my, index, shown, x, h);
		}

		if (lifted) m.popMatrix();
		NovaRender.setAlpha(previousAlpha);
	}

	/** Total height the rows want, including the mode split — what a column sizes itself to. */
	private float listHeight(List<NovaModules.Module> shown) {
		float acc = 0.0F;
		boolean previousWasMode = false;
		for (NovaModules.Module mod : shown) {
			if (previousWasMode && !mod.isMode()) acc += MODE_GAP;
			previousWasMode = mod.isMode();
			acc += blockHeight(mod) + ROW_GAP;
		}
		return Math.max(0.0F, acc - ROW_GAP);
	}

	/** The modules this category shows right now — everything, or the search matches. */
	private List<NovaModules.Module> visibleModules(NovaModules.Category category) {
		if (query.isEmpty()) return category.modules;
		String q = query.toLowerCase(Locale.ROOT);
		List<NovaModules.Module> out = new ArrayList<>();
		for (NovaModules.Module mod : category.modules) {
			if (mod.name.toLowerCase(Locale.ROOT).contains(q)) out.add(mod);
		}
		return out;
	}

	private void drawCategoryHeader(DrawContext ctx, float mx, float my, int index,
			NovaModules.Category category, float x) {
		boolean hovered = inside(mx, my, x, gridTop, COL_W, HEAD_H);
		float hov = anim("colhead_" + index, hovered ? 1.0F : 0.0F, 13.0F);
		NovaRender.roundRectGradient(ctx, x, gridTop, COL_W, HEAD_H, 9,
				NovaRender.lerpColor(hov, HEAD_TOP, HEAD_TOP_HI),
				NovaRender.lerpColor(hov, HEAD_BOT, HEAD_BOT_HI));
		NovaRender.roundRectBorder(ctx, x, gridTop, COL_W, HEAD_H, 9, HEAD_LINE);

		Text label = bold(category.name);
		float labelW = textRenderer.getWidth(label) * T_HEAD;
		float icon = 13.0F;
		float groupX = x + (COL_W - (icon + 6.0F + labelW)) / 2.0F;
		float cy = gridTop + HEAD_H / 2.0F;

		CategoryLogo logo = CATEGORY_LOGOS.get(category.name);
		if (NovaRender.getAlpha() > 0.35F) {
			if (logo != null) {
				ctx.drawTexture(RenderPipelines.GUI_TEXTURED, logo.texture(),
						Math.round(groupX), Math.round(cy - icon / 2.0F), 0.0F, 0.0F,
						Math.round(icon), Math.round(icon), logo.size(), logo.size(), logo.size(), logo.size(),
						NovaRender.withAlpha(0xFFFFFF, (int) (238 * NovaRender.getAlpha())));
			} else {
				drawItemIcon(ctx, category.icon, groupX, cy - icon / 2.0F, icon);
			}
		}
		textScaled(ctx, label, groupX + icon + 6.0F, cy - 4.0F,
				NovaRender.lerpColor(hov, 0xFFE7E9ED, 0xFFFFFFFF), T_HEAD);

		// A tally of what is live in this category, only once something is.
		int on = 0;
		for (NovaModules.Module mod : category.modules) {
			if (!mod.momentary && effectiveOn(mod)) on++;
		}
		float dotAlpha = anim("colon_" + index, on > 0 ? 1.0F : 0.0F, 10.0F);
		if (dotAlpha > 0.02F) {
			NovaRender.fillCircle(ctx, x + COL_W - 9.0F, gridTop + HEAD_H / 2.0F, 2.1F,
					NovaRender.withAlpha(accentSoft & 0xFFFFFF, (int) (0xE6 * dotAlpha)));
		}

		zone(x, gridTop, COL_W, HEAD_H, click -> {
			if (!rolled.remove(index)) rolled.add(index);
			sound(rolled.contains(index) ? 0.9F : 1.1F);
		});
	}

	/** The scrolling body of a column: mode band, hairline, then the ordinary modules. */
	private void drawModuleList(DrawContext ctx, float mx, float my, int index,
			List<NovaModules.Module> shown, float x, float panelDrawnH) {
		float inX = x + PAD;
		float inW = COL_W - PAD * 2.0F;
		float inY = panelY + PAD;
		float inH = panelDrawnH - PAD * 2.0F;
		if (inH < 6.0F) return;

		scroll[index] += (scrollTarget[index] - scroll[index]) * Math.min(1.0F, frameDelta * 15.0F);
		if (Math.abs(scrollTarget[index] - scroll[index]) < 0.05F) scroll[index] = scrollTarget[index];

		float[] previousClip = pushClip(inX, inY, inW, inH);
		ctx.enableScissor(floor(inX), floor(inY), ceil(inX + inW), ceil(inY + inH));

		float acc = 0.0F;
		boolean previousWasMode = false;
		for (NovaModules.Module mod : shown) {
			// Modes are the headline of their category: they sit on top, then a real gap and a
			// hairline before anything ordinary, so the split never depends on row size alone.
			if (previousWasMode && !mod.isMode()) {
				float dy = inY + acc - scroll[index] + MODE_GAP / 2.0F;
				NovaRender.roundRect(ctx, inX + 6.0F, dy, inW - 12.0F, 1.0F, 0.5F, DIVIDER);
				acc += MODE_GAP;
			}
			previousWasMode = mod.isMode();

			float blockH = blockHeight(mod);
			float ry = inY + acc - scroll[index];
			if (ry + blockH > inY - 20.0F && ry < inY + inH + 20.0F) {
				drawModuleBlock(ctx, mx, my, mod, inX, ry, inW, blockH);
			}
			acc += blockH + ROW_GAP;
		}

		ctx.disableScissor();
		popClip(previousClip);

		float max = Math.max(0.0F, acc - ROW_GAP - inH);
		scrollMax[index] = max;
		scrollTarget[index] = MathHelper.clamp(scrollTarget[index], 0.0F, max);
		if (max > 0.5F) {
			float thumbH = Math.max(16.0F, inH * (inH / (inH + max)));
			float thumbY = inY + (inH - thumbH) * MathHelper.clamp(scroll[index] / max, 0.0F, 1.0F);
			NovaRender.roundRect(ctx, x + COL_W - 3.5F, thumbY, 2.0F, thumbH, 1.0F, 0x26FFFFFF);
		}
	}

	/** Full height of a module: its row plus however much of its settings is currently revealed. */
	private float blockHeight(NovaModules.Module mod) {
		return rowHeight(mod)
				+ settingsHeight(mod) * anim("exp_" + mod.id, expanded.contains(mod.id) ? 1.0F : 0.0F, 14.0F);
	}

	private float rowHeight(NovaModules.Module mod) {
		return nameLines(mod).length > 1 ? ROW_H_WRAP : ROW_H;
	}

	/**
	 * The module name split to fit the row, one or two lines. Cached because it costs a handful of
	 * width measurements and the answer only changes when the column geometry does.
	 */
	private String[] nameLines(NovaModules.Module mod) {
		return nameLineCache.computeIfAbsent(mod.id, id -> {
			float avail = COL_W - PAD * 2.0F - 15.0F - 7.0F - PILL_W - 4.0F;
			int max = Math.max(8, (int) (avail / T_MOD));
			if (textRenderer.getWidth(mod.name) <= max) return new String[] {mod.name};

			// Break on the last word that still fits; a single over-long word just splits mid-word.
			int split = -1;
			for (int i = mod.name.indexOf(' '); i >= 0; i = mod.name.indexOf(' ', i + 1)) {
				if (textRenderer.getWidth(mod.name.substring(0, i)) > max) break;
				split = i;
			}
			if (split < 0) {
				String head = textRenderer.trimToWidth(mod.name, max);
				return new String[] {head, textRenderer.trimToWidth(mod.name.substring(head.length()), max)};
			}
			return new String[] {
					mod.name.substring(0, split),
					textRenderer.trimToWidth(mod.name.substring(split + 1), max)
			};
		});
	}

	private float settingsHeight(NovaModules.Module mod) {
		float h = 3.0F;
		for (NovaModules.Setting setting : mod.settings) if (setting.isVisible()) h += settingHeight(mod, setting);
		return h + SET_H + 6.0F; // + the keybind row and a bottom pad
	}

	private float settingHeight(NovaModules.Module mod, NovaModules.Setting setting) {
		if (setting instanceof NovaModules.IntSetting) return SET_H + SLIDER_H;
		if (setting instanceof NovaModules.ChoiceSetting choice) {
			return SET_H + dropOpen(mod, setting) * (choice.options.size() * OPT_H + 4.0F);
		}
		if (setting instanceof NovaModules.ButtonSetting) return FIELD_H;
		if (setting instanceof NovaModules.StringSetting) return FIELD_H;
		if (setting instanceof NovaModules.TierSetting) {
			return SET_H + dropOpen(mod, setting) * (MODE_TIERS.length * OPT_H + 4.0F);
		}
		if (setting instanceof NovaModules.BlockPickerSetting picker) {
			return SET_H + anim("pick_" + mod.id, picker.enabledGet.get() ? 1.0F : 0.0F, 11.0F) * PICKER_H;
		}
		if (setting instanceof NovaModules.NickListSetting list) {
			return list.entries.size() * NICK_H + NICK_ADD_H;
		}
		return SET_H;
	}

	/** How far an inline dropdown is open. Multi-choice settings start open, as they read best. */
	private float dropOpen(NovaModules.Module mod, NovaModules.Setting setting) {
		String key = mod.id + "|" + setting.label;
		return anim("drop_" + key, closedDrops.contains(key) ? 0.0F : 1.0F, 13.0F);
	}

	// ── Module row ───────────────────────────────────────────────────────────

	private void drawModuleBlock(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			float x, float y, float w, float blockH) {
		Boolean managed = NovaModules.managedState(config, mod.id);
		boolean locked = managed != null;
		boolean on = locked ? managed : mod.get.get();
		boolean isOpen = expanded.contains(mod.id);
		float rowH = rowHeight(mod);
		boolean hovered = inside(mx, my, x, y, w, rowH) && insideClip(mx, my);

		float en = anim("on_" + mod.id, on ? 1.0F : 0.0F, 12.0F);
		float hov = anim("hov_" + mod.id, hovered ? 1.0F : 0.0F, 14.0F);
		float live = anim("live_" + mod.id, on || isOpen ? 1.0F : 0.0F, 13.0F);

		// One surface behind the row and its settings, so an open module reads as a single card.
		float surfaceAlpha = Math.max(live, hov * 0.62F);
		if (surfaceAlpha > 0.01F) {
			int fill = NovaRender.lerpColor(live, ROW_HOVER, NovaRender.lerpColor(hov, SURFACE, SURFACE_HI));
			float base = NovaRender.getAlpha();
			NovaRender.setAlpha(base * surfaceAlpha);
			NovaRender.roundRect(ctx, x, y, w, blockH, 7, fill);
			NovaRender.roundRectBorder(ctx, x, y, w, blockH, 7, ROW_LINE);
			NovaRender.setAlpha(base);
		}

		int tierColor = locked ? activeModeColor() : mod.isMode() ? MODE_TIER_COLORS[modeTier(modeKeyOf(mod))] : 0;
		// A running mode (or a module it drives) carries a slim tier bar on its leading edge —
		// the only colour in the grid, and it only appears when it means something.
		if (tierColor != 0 && en > 0.01F) {
			NovaRender.roundRect(ctx, x + 2.0F, y + (rowH - 11.0F) / 2.0F, 2.0F, 11.0F, 1.0F,
					NovaRender.withAlpha(tierColor & 0xFFFFFF, (int) (0xF0 * en)));
		}

		drawGrip(ctx, mx, my, mod, x, y, rowH, hovered);

		float nameX = x + 15.0F;
		float pillX = x + w - 7.0F - PILL_W;
		int nameColor = locked
				? NovaRender.lerpColor(en, LOCKED, tierColor)
				: NovaRender.lerpColor(Math.max(en, hov * 0.7F), TEXT_OFF, TEXT);
		String[] lines = nameLines(mod);
		float lineH = 9.0F;
		float ny = y + (rowH - lines.length * lineH) / 2.0F + 1.0F;
		for (String line : lines) {
			textScaled(ctx, mod.isMode() ? bold(line) : regular(line), nameX, ny, nameColor, T_MOD);
			ny += lineH;
		}

		if (mod.momentary) {
			drawRunGlyph(ctx, pillX, y + rowH / 2.0F, PILL_W, hov);
		} else {
			drawTogglePill(ctx, en, pillX, y + (rowH - PILL_H) / 2.0F, PILL_W, PILL_H,
					tierColor == 0 ? accent() : tierColor);
		}

		if (hovered) captureTooltip(mod, mx, my);

		zone(x, y, w, rowH, click -> onModuleClick(mod, click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT));
		// After the row zone, so the grip wins the hit test it sits inside.
		zone(x, y, 15.0F, rowH, click -> {
			toggleExpanded(mod.id);
			sound(1.0F);
		});

		float reveal = anim("exp_" + mod.id, isOpen ? 1.0F : 0.0F, 14.0F);
		if (reveal < 0.004F) return;
		drawSettings(ctx, mx, my, mod, x, y, w, blockH, reveal);
	}

	/** The six-dot grip: also the disclosure handle, so it is an affordance rather than decoration. */
	private void drawGrip(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			float x, float y, float rowH, boolean rowHovered) {
		boolean gripHovered = inside(mx, my, x, y, 15.0F, rowH) && insideClip(mx, my);
		float g = anim("grip_" + mod.id, gripHovered ? 1.0F : rowHovered ? 0.45F : 0.0F, 14.0F);
		int color = NovaRender.lerpColor(g, GHOST, 0xFFB9BFC9);
		float cx = x + 6.0F;
		float cy = y + rowH / 2.0F;
		for (int col = 0; col < 2; col++) {
			for (int row = 0; row < 3; row++) {
				NovaRender.fillCircle(ctx, cx + col * 2.7F, cy - 2.7F + row * 2.7F, 0.7F, color);
			}
		}
	}

	/** Momentary modules have nothing to stay on, so they get a play glyph instead of a pill. */
	private void drawRunGlyph(DrawContext ctx, float x, float cy, float w, float hov) {
		int color = NovaRender.lerpColor(hov, KNOB_OFF, 0xFFFFFFFF);
		float bx = x + w / 2.0F - 2.0F;
		NovaRender.roundRect(ctx, bx, cy - 3.5F, 1.6F, 7.0F, 0.8F, color);
		NovaRender.roundRect(ctx, bx + 1.6F, cy - 2.4F, 1.6F, 4.8F, 0.8F, color);
		NovaRender.roundRect(ctx, bx + 3.2F, cy - 1.3F, 1.6F, 2.6F, 0.8F, color);
	}

	private void onModuleClick(NovaModules.Module mod, boolean right) {
		if (right) {
			// Every module drops down, settings or not — the keybind row lives in there too, and a
			// module without settings still needs somewhere to bind a key.
			toggleExpanded(mod.id);
			sound(1.0F);
			return;
		}
		Boolean managed = NovaModules.managedState(config, mod.id);
		if (managed != null) {
			String owner = NovaModules.managedBy(config, mod.id);
			String who = owner == null ? "the active mode" : owner;
			notice(Boolean.TRUE.equals(managed)
					? mod.name + " is run by " + who + "."
					: mod.name + " stays off while " + who + " is running.");
			sound(0.72F);
			return;
		}
		if (mod.momentary) {
			mod.set.accept(true);
			sound(1.1F);
			return;
		}
		boolean v = !mod.get.get();
		if (v && BlatantModuleWarning.requiresConfirmation(mod.id)) {
			BlatantModuleWarning.show(MinecraftClient.getInstance(), this, mod, () -> {
				mod.set.accept(true);
				config.save();
				sound(1.15F);
			});
			return;
		}
		mod.set.accept(v);
		config.save();
		sound(v ? (mod.isMode() ? 1.22F : 1.15F) : 0.85F);
	}

	private void toggleExpanded(String id) {
		if (!expanded.add(id)) expanded.remove(id);
	}

	// ── Settings drop-down ───────────────────────────────────────────────────

	/**
	 * The settings under a module row. While the reveal is mid-flight the whole block is clipped,
	 * so rows slide out from under the name instead of spilling over the module below.
	 */
	private void drawSettings(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			float x, float y, float w, float blockH, float reveal) {
		boolean clipping = reveal < 0.998F;
		float[] previousClip = null;
		if (clipping) {
			ctx.enableScissor(floor(x), floor(y), ceil(x + w), ceil(y + blockH));
			previousClip = pushClipIntersect(x, y, w, blockH);
		}

		float sx = x + 9.0F;
		float sw = w - 18.0F;
		float ry = y + rowHeight(mod) + 3.0F;
		for (NovaModules.Setting setting : mod.settings) {
			if (!setting.isVisible()) continue;
			float h = settingHeight(mod, setting);
			if (setting instanceof NovaModules.BoolSetting bool) {
				drawBoolRow(ctx, mx, my, mod, bool, sx, ry, sw);
			} else if (setting instanceof NovaModules.IntSetting num) {
				drawSliderRow(ctx, mx, my, num, sx, ry, sw);
			} else if (setting instanceof NovaModules.ChoiceSetting choice) {
				drawChoiceDrop(ctx, mx, my, mod, choice, sx, ry, sw, h);
			} else if (setting instanceof NovaModules.TierSetting tier) {
				drawTierDrop(ctx, mx, my, mod, tier, sx, ry, sw, h);
			} else if (setting instanceof NovaModules.ButtonSetting button) {
				drawButtonRow(ctx, mx, my, button, sx, ry, sw);
			} else if (setting instanceof NovaModules.StringSetting str) {
				drawStringRow(ctx, str, sx, ry, sw);
			} else if (setting instanceof NovaModules.BlockPickerSetting picker) {
				drawPicker(ctx, mx, my, mod, picker, sx, ry, sw, h);
			} else if (setting instanceof NovaModules.NickListSetting list) {
				drawNickList(ctx, mx, my, list, sx, ry, sw);
			}
			ry += h;
		}
		drawKeybindRow(ctx, mx, my, mod, sx, ry, sw);

		if (clipping) {
			popClip(previousClip);
			ctx.disableScissor();
		}
	}

	/** Standard module mode selector. Unlike the coloured combat tier selector, choices use the active accent. */
	private void drawChoiceDrop(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			NovaModules.ChoiceSetting setting, float x, float y, float w, float h) {
		int selected = MathHelper.clamp(setting.get.getAsInt(), 0, setting.options.size() - 1);
		String key = mod.id + "|" + setting.label;
		float open = dropOpen(mod, setting);
		boolean headHovered = inside(mx, my, x - 3.0F, y, w + 6.0F, SET_H) && insideClip(mx, my);
		float hov = anim("ch_" + key, headHovered ? 1.0F : 0.0F, 15.0F);

		textScaled(ctx, regular(setting.label), x, y + 3.5F, NovaRender.lerpColor(hov, FAINT, TEXT), T_SET);
		Text current = bold(setting.options.get(selected));
		float currentW = textRenderer.getWidth(current) * T_VAL;
		textScaled(ctx, current, x + w - currentW, y + 3.5F,
				NovaRender.lerpColor(hov, 0xFFB6BBC4, accentSoft), T_VAL);

		zone(x - 3.0F, y, w + 6.0F, SET_H, click -> {
			if (!closedDrops.remove(key)) closedDrops.add(key);
			sound(closedDrops.contains(key) ? 0.92F : 1.08F);
		});

		if (open < 0.01F) return;
		float listH = h - SET_H;
		ctx.enableScissor(floor(x - 4.0F), floor(y + SET_H), ceil(x + w + 4.0F), ceil(y + SET_H + listH));
		float[] previousClip = pushClipIntersect(x - 4.0F, y + SET_H, w + 8.0F, listH);
		float oy = y + SET_H + 2.0F;
		for (int i = 0; i < setting.options.size(); i++) {
			final int pick = i;
			drawOptionRow(ctx, mx, my, "choice_" + key + "_" + i, setting.options.get(i), i == selected,
					0, x, oy, w, () -> {
						setting.set.accept(pick);
						config.save();
						sound(0.96F + Math.min(0.18F, pick * 0.03F));
					});
			oy += OPT_H;
		}
		popClip(previousClip);
		ctx.disableScissor();
	}

	/** A sub-setting is a checkbox, not a pill — pills belong to modules, so the hierarchy reads. */
	private void drawBoolRow(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			NovaModules.BoolSetting setting, float x, float y, float w) {
		boolean on = setting.get.get();
		boolean available = setting.available.get();
		String key = mod.id + "_" + setting.label;
		float en = anim("b_" + key, on ? 1.0F : 0.0F, 13.0F);
		boolean hovered = available && inside(mx, my, x - 3.0F, y, w + 6.0F, SET_H) && insideClip(mx, my);
		float hov = anim("bh_" + key, hovered ? 1.0F : 0.0F, 15.0F);

		int color = !available ? 0xFF474C55 : NovaRender.lerpColor(Math.max(en, hov), FAINT, TEXT);
		textScaled(ctx, regular(trimToWidth(setting.label, (w - 16.0F) / T_SET)), x, y + 3.5F, color, T_SET);
		drawCheckbox(ctx, x + w - 9.0F, y + SET_H / 2.0F, en, available, hov);

		zone(x - 3.0F, y, w + 6.0F, SET_H, click -> {
			if (!setting.available.get()) {
				sound(0.72F);
				return;
			}
			setting.set.accept(!setting.get.get());
			config.save();
			sound(setting.get.get() ? 1.1F : 0.9F);
		});
	}

	private void drawSliderRow(DrawContext ctx, float mx, float my, NovaModules.IntSetting setting,
			float x, float y, float w) {
		String valueText = setting.divisor > 1
				? String.format(setting.divisor >= 100 ? "%.2f" : "%.1f",
						setting.get.getAsInt() / (float) setting.divisor) + setting.unit
				: setting.get.getAsInt() + setting.unit;
		Text value = regular(valueText);
		float valueW = textRenderer.getWidth(value) * T_VAL;

		boolean dragging = activeSlider == setting;
		boolean hovered = inside(mx, my, x - 3.0F, y, w + 6.0F, SET_H + SLIDER_H) && insideClip(mx, my);
		float hov = anim("sh_" + setting.label + "_" + (int) w + "_" + setting.min, hovered || dragging ? 1.0F : 0.0F, 15.0F);

		textScaled(ctx, regular(trimToWidth(setting.label, (w - valueW - 6.0F) / T_SET)), x, y + 3.5F,
				NovaRender.lerpColor(hov, FAINT, TEXT), T_SET);
		textScaled(ctx, value, x + w - valueW, y + 3.5F, NovaRender.lerpColor(hov, 0xFFB6BBC4, 0xFFFFFFFF), T_VAL);

		float cy = y + SET_H + SLIDER_H / 2.0F - 1.5F;
		float t = MathHelper.clamp((setting.get.getAsInt() - setting.min) / (float) (setting.max - setting.min), 0.0F, 1.0F);
		NovaRender.roundRect(ctx, x, cy - 1.0F, w, 2.0F, 1.0F, TRACK);
		if (t > 0.005F) {
			NovaRender.roundRect(ctx, x, cy - 1.0F, w * t, 2.0F, 1.0F,
					NovaRender.lerpColor(hov, accentDeep, accentSoft));
		}
		NovaRender.fillCircle(ctx, x + w * t, cy, dragging ? 3.6F : 2.6F + hov * 0.5F, 0xFFFFFFFF);

		final float trackX = x, trackW = w;
		zone(x - 4.0F, y, w + 8.0F, SET_H + SLIDER_H, click -> {
			activeSlider = setting;
			sliderTrackX = trackX;
			sliderTrackW = trackW;
			applySlider(click.x());
		});
	}

	/**
	 * The LT5→HT1 ramp as an inline option list: header shows the pick, the choices sit under it
	 * with a dot on the right. Same shape the block picker uses, so every multi-choice setting in
	 * the panel behaves identically.
	 */
	private void drawTierDrop(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			NovaModules.TierSetting setting, float x, float y, float w, float h) {
		int tier = MathHelper.clamp(setting.get.getAsInt(), 0, MODE_TIERS.length - 1);
		String key = mod.id + "|" + setting.label;
		float open = dropOpen(mod, setting);
		boolean headHovered = inside(mx, my, x - 3.0F, y, w + 6.0F, SET_H) && insideClip(mx, my);
		float hov = anim("th_" + key, headHovered ? 1.0F : 0.0F, 15.0F);

		textScaled(ctx, regular(setting.label), x, y + 3.5F, NovaRender.lerpColor(hov, FAINT, TEXT), T_SET);
		Text current = bold(MODE_TIERS[tier]);
		float currentW = textRenderer.getWidth(current) * T_VAL;
		textScaled(ctx, current, x + w - currentW, y + 3.5F, MODE_TIER_COLORS[tier], T_VAL);

		zone(x - 3.0F, y, w + 6.0F, SET_H, click -> {
			if (!closedDrops.remove(key)) closedDrops.add(key);
			sound(closedDrops.contains(key) ? 0.92F : 1.08F);
		});

		if (open < 0.01F) return;
		float listH = h - SET_H;
		ctx.enableScissor(floor(x - 4.0F), floor(y + SET_H), ceil(x + w + 4.0F), ceil(y + SET_H + listH));
		float[] previousClip = pushClipIntersect(x - 4.0F, y + SET_H, w + 8.0F, listH);
		float oy = y + SET_H + 2.0F;
		for (int i = 0; i < MODE_TIERS.length; i++) {
			final int pick = i;
			drawOptionRow(ctx, mx, my, "tier_" + key + "_" + i, MODE_TIERS[i], i == tier,
					MODE_TIER_COLORS[i], x, oy, w, () -> {
						setting.set.accept(pick);
						config.save();
						sound(0.94F + pick * 0.02F);
					});
			oy += OPT_H;
		}
		popClip(previousClip);
		ctx.disableScissor();
	}

	/** One choice in an inline dropdown: label left, filled dot right when it is the pick. */
	private void drawOptionRow(DrawContext ctx, float mx, float my, String key, String label,
			boolean selected, int tint, float x, float y, float w, Runnable onPick) {
		boolean hovered = inside(mx, my, x - 3.0F, y, w + 6.0F, OPT_H) && insideClip(mx, my);
		float hov = anim("opt_" + key, hovered ? 1.0F : 0.0F, 16.0F);
		float sel = anim("opts_" + key, selected ? 1.0F : 0.0F, 13.0F);

		if (hov > 0.01F) {
			NovaRender.roundRect(ctx, x - 3.0F, y + 0.5F, w + 6.0F, OPT_H - 1.0F, 4,
					NovaRender.withAlpha(0xFFFFFF, (int) (12 * hov)));
		}
		int color = selected
				? (tint == 0 ? accentSoft : tint)
				: NovaRender.lerpColor(hov, GHOST, 0xFF9AA0AA);
		textScaled(ctx, regular(trimToWidth(label, (w - 14.0F) / T_OPT)), x + 3.0F, y + 2.5F, color, T_OPT);

		float dx = x + w - 4.0F;
		float dy = y + OPT_H / 2.0F;
		// Fade the dot in on alpha alone — lerping from transparent black would drag it through grey.
		NovaRender.fillCircle(ctx, dx, dy, 2.6F,
				NovaRender.withAlpha((tint == 0 ? accent() : tint) & 0xFFFFFF, (int) (255 * sel)));
		if (sel < 0.98F) {
			NovaRender.ring(ctx, dx, dy, 2.6F, 0.9F,
					NovaRender.withAlpha(0xFFFFFF, (int) ((0x2E + 0x30 * hov) * (1.0F - sel))));
		}
		zone(x - 3.0F, y, w + 6.0F, OPT_H, click -> onPick.run());
	}

	private void drawButtonRow(DrawContext ctx, float mx, float my, NovaModules.ButtonSetting setting,
			float x, float y, float w) {
		float bh = FIELD_H - 4.0F;
		float by = y + 2.0F;
		boolean hovered = inside(mx, my, x, by, w, bh) && insideClip(mx, my);
		float hov = anim("bt_" + setting.label, hovered ? 1.0F : 0.0F, 15.0F);
		NovaRender.roundRect(ctx, x, by, w, bh, 5, NovaRender.lerpColor(hov, WELL, 0xFF31353D));
		NovaRender.roundRectBorder(ctx, x, by, w, bh, 5, NovaRender.lerpColor(hov, ROW_LINE, accentA(0x99)));
		Text caption = regular(setting.caption);
		float capW = textRenderer.getWidth(caption) * T_VAL;
		textScaled(ctx, caption, x + (w - capW) / 2.0F, by + (bh - 6.0F) / 2.0F,
				NovaRender.lerpColor(hov, FAINT, TEXT), T_VAL);
		zone(x, by, w, bh, click -> {
			setting.action.run();
			sound(1.2F);
		});
	}

	private void drawStringRow(DrawContext ctx, NovaModules.StringSetting setting, float x, float y, float w) {
		boolean focused = activeString == setting;
		float bh = FIELD_H - 4.0F;
		float by = y + 2.0F;
		NovaRender.roundRect(ctx, x, by, w, bh, 5, WELL);
		NovaRender.roundRectBorder(ctx, x, by, w, bh, 5, focused ? accentA(0xCC) : ROW_LINE);

		String value = setting.get.get();
		if (value == null) value = "";
		boolean empty = value.isEmpty();
		String shown = empty && !focused ? setting.placeholder : value;
		if (focused && (System.currentTimeMillis() / 480) % 2 == 0) shown = shown + "_";
		shown = trimLeftToWidth(shown, (w - 10.0F) / T_VAL);
		textScaled(ctx, regular(shown), x + 5.0F, by + (bh - 6.0F) / 2.0F,
				empty && !focused ? GHOST : TEXT, T_VAL);

		zone(x, by, w, bh, click -> {
			activeString = setting;
			searchFocused = false;
			pickerFocused = false;
			sound(1.05F);
		});
	}

	/** BreakOn's "Certain Blocks": a checkbox that drops a searchable block list into the column. */
	private void drawPicker(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			NovaModules.BlockPickerSetting picker, float x, float y, float w, float h) {
		boolean on = picker.enabledGet.get();
		float en = anim("pick_" + mod.id, on ? 1.0F : 0.0F, 11.0F);
		boolean headHovered = inside(mx, my, x - 3.0F, y, w + 6.0F, SET_H) && insideClip(mx, my);
		float hov = anim("pickh_" + mod.id, headHovered ? 1.0F : 0.0F, 15.0F);

		textScaled(ctx, regular(trimToWidth(picker.label, (w - 16.0F) / T_SET)), x, y + 3.5F,
				NovaRender.lerpColor(Math.max(en, hov), FAINT, TEXT), T_SET);
		drawCheckbox(ctx, x + w - 9.0F, y + SET_H / 2.0F, en, true, hov);
		zone(x - 3.0F, y, w + 6.0F, SET_H, click -> {
			picker.enabledSet.accept(!picker.enabledGet.get());
			config.save();
			sound(picker.enabledGet.get() ? 1.1F : 0.9F);
		});

		float dh = h - SET_H;
		if (dh < 1.0F) return;
		ctx.enableScissor(floor(x - 4.0F), floor(y + SET_H), ceil(x + w + 4.0F), ceil(y + SET_H + dh));
		float[] previousClip = pushClipIntersect(x - 4.0F, y + SET_H, w + 8.0F, dh);

		float wellY = y + SET_H + 2.0F;
		NovaRender.roundRect(ctx, x, wellY, w, PICKER_H - 6.0F, 6, WELL);

		float searchH = 14.0F;
		NovaRender.roundRect(ctx, x + 3.0F, wellY + 3.0F, w - 6.0F, searchH, 5, 0xFF1B1D22);
		NovaRender.roundRectBorder(ctx, x + 3.0F, wellY + 3.0F, w - 6.0F, searchH, 5, pickerFocused ? accentA(0xCC) : ROW_LINE);
		String shown = pickerQuery.isEmpty() && !pickerFocused
				? "Search blocks…"
				: pickerQuery + (pickerFocused && (System.currentTimeMillis() / 480) % 2 == 0 ? "_" : "");
		shown = trimLeftToWidth(shown, (w - 16.0F) / T_OPT);
		textScaled(ctx, regular(shown), x + 8.0F, wellY + 3.0F + (searchH - 6.0F) / 2.0F,
				pickerQuery.isEmpty() && !pickerFocused ? GHOST : TEXT, T_OPT);
		zone(x + 3.0F, wellY + 3.0F, w - 6.0F, searchH, click -> {
			pickerFocused = true;
			searchFocused = false;
			activeString = null;
		});

		ensurePickerMatches();
		float listX = x + 3.0F;
		float listY = wellY + searchH + 6.0F;
		float listW = w - 6.0F;
		float listH = wellY + PICKER_H - 6.0F - 3.0F - listY;
		pickerListX = listX;
		pickerListY = listY;
		pickerListW = listW;
		pickerListH = listH;

		float maxScrollPx = Math.max(0.0F, pickerMatches.size() * PICKER_ROW_H - listH);
		pickerScroll = MathHelper.clamp(pickerScroll, 0.0F, maxScrollPx);

		ctx.enableScissor(floor(listX), floor(listY), ceil(listX + listW), ceil(listY + listH));
		float[] listClip = pushClipIntersect(listX, listY, listW, listH);
		for (int i = 0; i < pickerMatches.size(); i++) {
			float ry = listY + i * PICKER_ROW_H - pickerScroll;
			if (ry + PICKER_ROW_H < listY || ry > listY + listH) continue;
			Block block = pickerMatches.get(i);
			String id = Registries.BLOCK.getId(block).toString();
			boolean selected = picker.selected.contains(id);
			drawOptionRow(ctx, mx, my, "blk_" + id, block.getName().getString(), selected, 0,
					listX + 3.0F, ry, listW - 6.0F, () -> {
						if (!picker.selected.remove(id)) picker.selected.add(id);
						config.save();
						sound(1.05F);
					});
		}
		popClip(listClip);
		ctx.disableScissor();

		if (maxScrollPx > 0.5F) {
			float thumbH = Math.max(10.0F, listH * (listH / (listH + maxScrollPx)));
			float thumbY = listY + (listH - thumbH) * (pickerScroll / maxScrollPx);
			NovaRender.roundRect(ctx, listX + listW - 2.0F, thumbY, 1.6F, thumbH, 0.8F, 0x26FFFFFF);
		}

		popClip(previousClip);
		ctx.disableScissor();
	}

	/** Rebuild the filtered block list only when the query changes. */
	private void ensurePickerMatches() {
		String q = pickerQuery.toLowerCase(Locale.ROOT);
		if (q.equals(pickerMatchesFor)) return;
		pickerMatchesFor = q;
		pickerScroll = 0.0F;
		pickerMatches.clear();
		for (Block block : Registries.BLOCK) {
			if (block == Blocks.AIR) continue;
			if (new ItemStack(block.asItem()).isEmpty()) continue;
			if (q.isEmpty()) {
				pickerMatches.add(block);
			} else {
				String id = Registries.BLOCK.getId(block).toString();
				if (block.getName().getString().toLowerCase(Locale.ROOT).contains(q) || id.contains(q)) {
					pickerMatches.add(block);
				}
			}
			if (pickerMatches.size() >= 400) break;
		}
	}

	// ── Nick Other list ──────────────────────────────────────────────────────

	private NovaModules.StringSetting[] nickFields(NickEntry entry) {
		return nickFieldCache.computeIfAbsent(entry, k -> new NovaModules.StringSetting[] {
				new NovaModules.StringSetting("", "real", () -> entry.target, v -> entry.target = v),
				new NovaModules.StringSetting("", "shown", () -> entry.nick, v -> entry.nick = v)
		});
	}

	private void drawNickList(DrawContext ctx, float mx, float my, NovaModules.NickListSetting list,
			float x, float y, float w) {
		for (NickEntry entry : new ArrayList<>(list.entries)) {
			drawNickRow(ctx, list, entry, x, y, w);
			y += NICK_H;
		}
		float bh = NICK_ADD_H - 3.0F;
		boolean hovered = inside(mx, my, x, y + 1.0F, w, bh) && insideClip(mx, my);
		float hov = anim("nickadd_" + System.identityHashCode(list), hovered ? 1.0F : 0.0F, 14.0F);
		NovaRender.roundRect(ctx, x, y + 1.0F, w, bh, 5, NovaRender.lerpColor(hov, WELL, 0xFF31353D));
		NovaRender.roundRectBorder(ctx, x, y + 1.0F, w, bh, 5, NovaRender.lerpColor(hov, ROW_LINE, accentA(0x99)));
		Text caption = regular("Add player");
		float capW = textRenderer.getWidth(caption) * T_VAL;
		textScaled(ctx, caption, x + (w - capW) / 2.0F, y + 1.0F + (bh - 6.0F) / 2.0F,
				NovaRender.lerpColor(hov, FAINT, TEXT), T_VAL);
		zone(x, y + 1.0F, w, bh, click -> {
			list.entries.add(new NickEntry());
			list.onChange.run();
			sound(1.15F);
		});
	}

	private void drawNickRow(DrawContext ctx, NovaModules.NickListSetting list, NickEntry entry,
			float x, float y, float w) {
		NovaModules.StringSetting[] fields = nickFields(entry);
		float fh = NICK_H - 4.0F;
		float fy = y + 2.0F;
		float cy = y + NICK_H / 2.0F;
		float removeCx = x + w - 4.5F;
		float skinCx = removeCx - 12.0F;
		float fieldsRight = skinCx - 7.0F;
		float fieldW = (fieldsRight - x - 5.0F) / 2.0F;

		drawMiniField(ctx, fields[0], x, fy, fieldW, fh);
		textScaled(ctx, regular(">"), x + fieldW + 1.5F, y + 4.0F, GHOST, T_OPT);
		drawMiniField(ctx, fields[1], x + fieldW + 5.0F, fy, fieldW, fh);

		float skin = anim("nickskin_" + System.identityHashCode(entry), entry.skin ? 1.0F : 0.0F, 13.0F);
		NovaRender.fillCircle(ctx, skinCx, cy, 3.6F, NovaRender.lerpColor(skin, TRACK, 0xFFFFFFFF));
		NovaRender.fillCircle(ctx, skinCx, cy, 1.4F, NovaRender.lerpColor(skin, 0xFF6A7482, 0xFF17181C));
		zone(skinCx - 5.0F, y, 10.0F, NICK_H, click -> {
			entry.skin = !entry.skin;
			list.onChange.run();
			sound(entry.skin ? 1.1F : 0.9F);
		});

		NovaRender.roundRect(ctx, removeCx - 4.5F, cy - 4.5F, 9.0F, 9.0F, 3, 0x2EF87171);
		NovaRender.roundRect(ctx, removeCx - 2.5F, cy - 0.5F, 5.0F, 1.0F, 0.5F, 0xFFF87171);
		zone(removeCx - 5.5F, y, 11.0F, NICK_H, click -> {
			list.entries.remove(entry);
			nickFieldCache.remove(entry);
			if (activeString == fields[0] || activeString == fields[1]) activeString = null;
			list.onChange.run();
			sound(0.85F);
		});
	}

	private void drawMiniField(DrawContext ctx, NovaModules.StringSetting field, float x, float y, float w, float h) {
		boolean focused = activeString == field;
		NovaRender.roundRect(ctx, x, y, w, h, 4, WELL);
		NovaRender.roundRectBorder(ctx, x, y, w, h, 4, focused ? accentA(0xCC) : ROW_LINE);
		String value = field.get.get();
		if (value == null) value = "";
		boolean empty = value.isEmpty();
		String shown = empty && !focused ? field.placeholder : value;
		if (focused && (System.currentTimeMillis() / 480) % 2 == 0) shown = shown + "_";
		shown = trimLeftToWidth(shown, (w - 7.0F) / T_OPT);
		textScaled(ctx, regular(shown), x + 3.5F, y + (h - 6.0F) / 2.0F, empty && !focused ? GHOST : TEXT, T_OPT);
		zone(x, y, w, h, click -> {
			activeString = field;
			searchFocused = false;
			pickerFocused = false;
			sound(1.05F);
		});
	}

	private void drawKeybindRow(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			float x, float y, float w) {
		boolean listening = mod.id.equals(listeningId);
		String label = listening ? "…" : keyName(config.moduleKeybinds.get(mod.id));
		Text chipText = regular(label);
		float chipTextW = textRenderer.getWidth(chipText) * T_VAL;
		float chipW = Math.max(24.0F, chipTextW + 10.0F);
		float chipX = x + w - chipW;
		float chipH = SET_H - 3.0F;
		float chipY = y + 1.5F;
		boolean hovered = inside(mx, my, chipX, y, chipW, SET_H) && insideClip(mx, my);
		float hov = anim("kb_" + mod.id, hovered || listening ? 1.0F : 0.0F, 15.0F);

		textScaled(ctx, regular("Bind"), x, y + 3.5F, NovaRender.lerpColor(hov, FAINT, TEXT), T_SET);
		NovaRender.roundRect(ctx, chipX, chipY, chipW, chipH, 4, WELL);
		NovaRender.roundRectBorder(ctx, chipX, chipY, chipW, chipH, 4, listening ? accentA(0xCC) : ROW_LINE);
		textScaled(ctx, chipText, chipX + (chipW - chipTextW) / 2.0F, chipY + (chipH - 6.0F) / 2.0F,
				listening ? 0xFFFFFFFF : NovaRender.lerpColor(hov, FAINT, TEXT), T_VAL);

		zone(chipX - 2.0F, y, chipW + 4.0F, SET_H, click -> {
			if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				config.moduleKeybinds.remove(mod.id);
				config.save();
				listeningId = null;
			} else {
				// Reaching for a bind means the search is no longer what you are
				// typing into, so it should not still be holding focus afterwards.
				listeningId = mod.id;
				searchFocused = false;
			}
			sound(1.0F);
		});
	}

	// ── Search bar + overlays ────────────────────────────────────────────────

	private void drawSearchBar(DrawContext ctx, float mx, float my) {
		boolean hovered = inside(mx, my, searchX, searchY, SEARCH_W, SEARCH_H);
		float hov = anim("search", hovered || searchFocused ? 1.0F : 0.0F, 14.0F);
		NovaRender.roundRect(ctx, searchX, searchY, SEARCH_W, SEARCH_H, 9,
				NovaRender.lerpColor(hov, 0xF01D1F24, 0xF0272A31));
		NovaRender.roundRectBorder(ctx, searchX, searchY, SEARCH_W, SEARCH_H, 9,
				searchFocused ? accentA(0xCC) : NovaRender.lerpColor(hov, PANEL_LINE, HEAD_LINE));

		boolean empty = query.isEmpty();
		String shown = empty && !searchFocused
				? "Search for module..."
				: query + (searchFocused && (System.currentTimeMillis() / 480) % 2 == 0 ? "_" : "");
		shown = trimLeftToWidth(shown, (SEARCH_W - 40.0F) / T_MOD);
		textScaled(ctx, regular(shown), searchX + 13.0F, searchY + (SEARCH_H - 7.0F) / 2.0F,
				empty && !searchFocused ? GHOST : TEXT, T_MOD);

		float gcx = searchX + SEARCH_W - 17.0F;
		float gcy = searchY + SEARCH_H / 2.0F - 0.5F;
		int glass = searchFocused ? accentSoft : NovaRender.lerpColor(hov, GHOST, 0xFFCED3DB);
		NovaRender.ring(ctx, gcx, gcy - 1.0F, 3.6F, 1.0F, glass);
		strokeLine(ctx, gcx + 2.4F, gcy + 1.4F, gcx + 4.6F, gcy + 3.6F, 1.3F, glass);

		zone(searchX, searchY, SEARCH_W, SEARCH_H, click -> {
			searchFocused = true;
			pickerFocused = false;
			activeString = null;
			sound(1.05F);
		});
	}

	// ── Settings panel ───────────────────────────────────────────────────────
	// Everything the old sidebar held: client and interface preferences, the accent theme, saved
	// configs, every module keybind, and Packet Utils. It unfolds from the wordmark rather than
	// opening as a centred modal, so the thing you clicked stays the thing you are looking at and
	// the grid stays visible (dimmed) behind it.

	private enum SettingsPage {
		SETTINGS("Settings"), THEME("Theme"), CONFIGS("Configs"), KEYBINDS("Keybinds"),
		PACKET("Packet Utils"), DATA("Data");

		final String label;

		SettingsPage(String label) {
			this.label = label;
		}
	}

	private void drawSettingsPanel(DrawContext ctx, float mx, float my) {
		float open = anim("settingspop", settingsOpen ? 1.0F : 0.0F, 15.0F);
		if (open < 0.004F) return;
		float eased = easeOutCubic(open);
		boolean live = eased > 0.86F;

		float w = Math.min(PANEL_SETTINGS_W, vw - 16.0F);
		float y = themeAnchorY;
		float h = MathHelper.clamp(vh - y - 20.0F, 190.0F, 348.0F);
		float x = MathHelper.clamp(themeAnchorX - w / 2.0F, 8.0F, Math.max(8.0F, vw - w - 8.0F));

		// Dim and swallow the grid: a stray click closes the panel instead of flipping whatever
		// module happens to sit behind it.
		float base = NovaRender.getAlpha();
		NovaRender.roundRect(ctx, 0, 0, vw, vh, 0, NovaRender.withAlpha(0x05070A, (int) (0xB4 * eased)));
		zone(0, 0, vw, vh, click -> closeSettings());

		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		float scale = 0.92F + 0.08F * eased;
		m.translate(themeAnchorX, y);
		m.scale(scale, scale);
		m.translate(-themeAnchorX, -y);
		NovaRender.setAlpha(base * eased);

		NovaRender.roundRectGradient(ctx, x, y, w, h, 12, 0xFA1B1E24, 0xFA131519);
		NovaRender.roundRectBorder(ctx, x, y, w, h, 12, 0x26FFFFFF);
		NovaRender.roundRect(ctx, themeAnchorX - 4.0F, y - 2.5F, 8.0F, 6.0F, 2, 0xFA1B1E24);

		float railW = 104.0F;
		float railX = x + 9.0F;
		float bodyX = railX + railW + 13.0F;
		float bodyW = x + w - 14.0F - bodyX;

		textScaled(ctx, bold(settingsPage.label), bodyX, y + 15.0F, TEXT, T_MOD);
		drawSettingsClose(ctx, mx, my, x + w - 24.0F, y + 12.0F, live);
		NovaRender.roundRect(ctx, bodyX, y + 30.0F, bodyW, 1.0F, 0.5F, DIVIDER);

		float ry = y + 14.0F;
		for (SettingsPage page : SettingsPage.values()) {
			drawSettingsNavRow(ctx, mx, my, page, railX, ry, railW, live);
			ry += 26.0F;
		}

		float bodyY = y + 38.0F;
		float bodyH = y + h - 12.0F - bodyY;
		settingsScroll += (settingsScrollTarget - settingsScroll) * Math.min(1.0F, frameDelta * 15.0F);
		settingsBodyX = bodyX;
		settingsBodyY = bodyY;
		settingsBodyW = bodyW;
		settingsBodyH = bodyH;

		ctx.enableScissor(floor(bodyX - 4.0F), floor(bodyY), ceil(bodyX + bodyW + 4.0F), ceil(bodyY + bodyH));
		float[] previousClip = pushClip(bodyX - 4.0F, bodyY, bodyW + 8.0F, bodyH);
		float cy = bodyY + 6.0F - settingsScroll;
		float bottom = switch (settingsPage) {
			case SETTINGS -> drawPrefsPage(ctx, mx, my, bodyX, cy, bodyW, live);
			case THEME -> drawThemePage(ctx, mx, my, bodyX, cy, bodyW, live);
			case CONFIGS -> drawConfigsPage(ctx, mx, my, bodyX, cy, bodyW, live);
			case KEYBINDS -> drawKeybindsPage(ctx, mx, my, bodyX, cy, bodyW, live);
			case PACKET -> drawPacketPage(ctx, mx, my, bodyX, cy, bodyW, live);
			case DATA -> drawDataPage(ctx, mx, my, bodyX, cy, bodyW, live);
		};
		popClip(previousClip);
		ctx.disableScissor();

		settingsScrollMax = Math.max(0.0F, (bottom + settingsScroll - bodyY) - bodyH + 6.0F);
		settingsScrollTarget = MathHelper.clamp(settingsScrollTarget, 0.0F, settingsScrollMax);
		if (settingsScrollMax > 0.5F) {
			float thumbH = Math.max(16.0F, bodyH * (bodyH / (bodyH + settingsScrollMax)));
			float thumbY = bodyY + (bodyH - thumbH) * MathHelper.clamp(settingsScroll / settingsScrollMax, 0.0F, 1.0F);
			NovaRender.roundRect(ctx, x + w - 6.0F, thumbY, 2.0F, thumbH, 1.0F, 0x26FFFFFF);
		}

		NovaRender.setAlpha(base);
		m.popMatrix();
	}

	private void drawSettingsNavRow(DrawContext ctx, float mx, float my, SettingsPage page,
			float x, float y, float w, boolean live) {
		boolean selected = settingsPage == page;
		boolean hovered = live && inside(mx, my, x, y, w, 24.0F);
		float sel = anim("nav_" + page.name(), selected ? 1.0F : 0.0F, 14.0F);
		float hov = anim("navh_" + page.name(), hovered ? 1.0F : 0.0F, 15.0F);

		if (Math.max(sel, hov) > 0.01F) {
			NovaRender.roundRect(ctx, x, y, w, 24.0F, 7,
					NovaRender.lerpColor(sel, NovaRender.withAlpha(0xFFFFFF, (int) (12 * hov)), accentA(0x26)));
		}
		if (sel > 0.01F) {
			NovaRender.roundRect(ctx, x + 2.0F, y + (24.0F - 12.0F * sel) / 2.0F, 2.0F, 12.0F * sel, 1.0F,
					NovaRender.withAlpha(accentBase & 0xFFFFFF, (int) (255 * sel)));
		}
		textScaled(ctx, regular(page.label), x + 11.0F, y + 8.5F,
				NovaRender.lerpColor(Math.max(sel, hov), FAINT, TEXT), T_SET);
		zone(x, y, w, 24.0F, click -> {
			if (settingsPage != page) {
				settingsPage = page;
				settingsScroll = 0.0F;
				settingsScrollTarget = 0.0F;
				if (page == SettingsPage.CONFIGS) profileCache = ProFPSConfig.listProfiles();
				sound(1.05F);
			}
		});
	}

	private void drawSettingsClose(DrawContext ctx, float mx, float my, float x, float y, boolean live) {
		boolean hovered = live && inside(mx, my, x, y, 14.0F, 14.0F);
		float hov = anim("setclose", hovered ? 1.0F : 0.0F, 16.0F);
		int color = NovaRender.lerpColor(hov, FAINT, 0xFFFFFFFF);
		float cx = x + 7.0F;
		float cyy = y + 7.0F;
		strokeLine(ctx, cx - 3.4F, cyy - 3.4F, cx + 3.4F, cyy + 3.4F, 1.4F, color);
		strokeLine(ctx, cx + 3.4F, cyy - 3.4F, cx - 3.4F, cyy + 3.4F, 1.4F, color);
		zone(x - 4.0F, y - 4.0F, 22.0F, 22.0F, click -> closeSettings());
	}

	private void closeSettings() {
		settingsOpen = false;
		configNameFocused = false;
		listeningId = null;
		sound(0.9F);
	}

	// ── Settings pages ───────────────────────────────────────────────────────

	private float drawPrefsPage(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		y = sLabel(ctx, "CLIENT", x, y);
		y = sToggle(ctx, mx, my, "Master Enable", "Turn the whole client on or off.",
				config.enabled, () -> config.enabled = !config.enabled, x, y, w, live);
		y = sLabel(ctx, "INTERFACE", x, y + 5.0F);
		y = sToggle(ctx, mx, my, "Automatic UI Size", "Fit the panel to the current window.",
				config.guiAutoScale, () -> {
					if (config.guiAutoScale) {
						config.guiScalePct = MathHelper.clamp(Math.round(uiScale * 20.0F) * 5,
								NovaUiScale.MIN_MANUAL_PERCENT, NovaUiScale.MAX_MANUAL_PERCENT);
					}
					config.guiAutoScale = !config.guiAutoScale;
				}, x, y, w, live);
		y = sScaleRow(ctx, mx, my, x, y, w, live);
		y = sToggle(ctx, mx, my, "Animations", "Eased motion on columns, rows and toggles.",
				config.guiAnimations, () -> config.guiAnimations = !config.guiAnimations, x, y, w, live);
		y = sToggle(ctx, mx, my, "Glow", "Soft accent glow behind active elements.",
				config.guiGlow, () -> config.guiGlow = !config.guiGlow, x, y, w, live);
		y = sToggle(ctx, mx, my, "Click Sounds", "Play a soft click when you toggle things.",
				config.guiSounds, () -> config.guiSounds = !config.guiSounds, x, y, w, live);
		y = sLabel(ctx, "HUD", x, y + 5.0F);
		y = sToggle(ctx, mx, my, "Module List", "Show your enabled modules at the screen edge.",
				config.hudModuleList, () -> config.hudModuleList = !config.hudModuleList, x, y, w, live);
		y = sToggle(ctx, mx, my, "List on Right Side", "Anchor the list to the right edge instead.",
				config.hudModuleListRight, () -> config.hudModuleListRight = !config.hudModuleListRight, x, y, w, live);
		return y;
	}

	/**
	 * The only place the contribution switches live. Both ship on, so this page is the whole of
	 * the disclosure — it spells out what actually leaves the machine rather than leaning on the
	 * word "anonymous", and keeps location on its own switch since that is the part with a real
	 * cost to the contributor.
	 */
	private float drawDataPage(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		y = sLabel(ctx, "CONTRIBUTE", x, y);
		y = sToggle(ctx, mx, my, "Share Movement Data",
				"Tick-by-tick movement, sent in the background to train the AI.",
				config.dataContribution, () -> config.dataContribution = !config.dataContribution,
				x, y, w, live);
		y = drawContributionStatus(ctx, x, y, w);

		y += 2.0F;
		for (String line : wrapText("What goes: your position relative to where the session started, "
				+ "speed, look angles, which keys you are holding, nearby players as offsets from you, "
				+ "and the blocks around your feet. Recordings are tagged with a random id, not your "
				+ "account.", (int) (w / T_VAL))) {
			textScaled(ctx, regular(line), x + 1.0F, y, FAINT, T_VAL);
			y += 10.0F;
		}
		y += 4.0F;
		for (String line : wrapText("What never goes: your username or UUID, chat, and inventory "
				+ "contents. Nothing is recorded at all while a NovaClient module is running — only "
				+ "real, unassisted play.", (int) (w / T_VAL))) {
			textScaled(ctx, regular(line), x + 1.0F, y, FAINT, T_VAL);
			y += 10.0F;
		}

		y = sLabel(ctx, "LOCATION", x, y + 8.0F);
		y = sToggle(ctx, mx, my, "Include Location Data",
				"Your real coordinates and the server address, on top of the above.",
				config.dataContributionLocation,
				() -> config.dataContributionLocation = !config.dataContributionLocation, x, y, w, live);
		y += 2.0F;
		for (String line : wrapText("This one says where you play, base included. Turn it off and "
				+ "you still contribute everything the movement model actually learns from — "
				+ "positions stay relative to wherever the session began.", (int) (w / T_VAL))) {
			textScaled(ctx, regular(line), x + 1.0F, y, GHOST, T_VAL);
			y += 10.0F;
		}
		return y + 4.0F;
	}

	/**
	 * Live recording state. Worth the space: the filter refuses any tick a module could have
	 * touched, so somebody with Flight bound and forgotten would otherwise see a switch that says
	 * on and wonder why they never contribute anything. This says which module is holding it.
	 */
	private float drawContributionStatus(DrawContext ctx, float x, float y, float w) {
		var recorder = com.profps.client.data.DataContribution.instance();
		String paused = recorder == null ? null : recorder.pausedReason();
		boolean recording = config.dataContribution && paused == null;
		float h = 22.0F;

		NovaRender.roundRect(ctx, x, y, w, h, 6, WELL);
		NovaRender.roundRectBorder(ctx, x, y, w, h, 6, recording ? accentA(0x4A) : ROW_LINE);
		float dotX = x + 12.0F;
		NovaRender.fillCircle(ctx, dotX, y + h / 2.0F, 2.6F, recording ? accent() : GHOST);
		String label = recording ? "Recording" : "Paused" + (paused == null ? "" : " · " + paused);
		textScaled(ctx, regular(trimToWidth(label, (w - 30.0F) / T_VAL)), dotX + 8.0F,
				y + h / 2.0F - 3.0F, recording ? TEXT : FAINT, T_VAL);
		return y + h + 6.0F;
	}

	private float drawThemePage(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		y = sLabel(ctx, "ACCENT COLOUR", x, y);
		int cols = 2;
		float sw = (w - (cols - 1) * 7.0F) / cols;
		float sh = 40.0F;
		for (int i = 0; i < NovaTheme.count(); i++) {
			drawSwatch(ctx, mx, my, i, x + (i % cols) * (sw + 7.0F), y + (i / cols) * (sh + 7.0F), sw, sh, live);
		}
		int rows = (NovaTheme.count() + cols - 1) / cols;
		y += rows * (sh + 7.0F) + 4.0F;
		y = sLabel(ctx, "WORDMARK", x, y);
		for (String line : wrapText("\"Client\" drifts through every preset on a ten-second cycle, "
				+ "whichever accent you pick here.", (int) (w / T_VAL))) {
			textScaled(ctx, regular(line), x + 1.0F, y, FAINT, T_VAL);
			y += 10.0F;
		}
		return y + 4.0F;
	}

	private void drawSwatch(DrawContext ctx, float mx, float my, int index,
			float x, float y, float w, float h, boolean live) {
		int[] preset = NovaTheme.accent(index);
		boolean selected = config.guiAccent == index;
		boolean hovered = live && inside(mx, my, x, y, w, h) && insideClip(mx, my);
		float hov = anim("sw_" + index, hovered ? 1.0F : 0.0F, 15.0F);
		float sel = anim("swsel_" + index, selected ? 1.0F : 0.0F, 13.0F);

		NovaRender.roundRect(ctx, x, y, w, h, 8, NovaRender.lerpColor(hov, WELL, 0xFF23262C));
		NovaRender.roundRectBorder(ctx, x, y, w, h, 8,
				NovaRender.lerpColor(sel, NovaRender.lerpColor(hov, ROW_LINE, 0x2EFFFFFF),
						NovaRender.withAlpha(preset[1] & 0xFFFFFF, 0xC8)));
		// The preset as a soft→deep bar: reads the whole ramp, not just the base colour.
		NovaRender.roundRectGradient(ctx, x + 8.0F, y + 8.0F, 14.0F, h - 16.0F, 5, preset[0], preset[2]);
		textScaled(ctx, bold(NovaTheme.name(index)), x + 29.0F, y + h / 2.0F - 3.5F,
				NovaRender.lerpColor(Math.max(sel, hov), FAINT, 0xFFFFFFFF), T_SET);
		if (sel > 0.02F) {
			float tx = x + w - 13.0F;
			float ty = y + h / 2.0F;
			int tick = NovaRender.withAlpha(preset[0] & 0xFFFFFF, (int) (255 * sel));
			strokeLine(ctx, tx - 2.4F, ty + 0.1F, tx - 0.8F, ty + 1.9F, 1.4F, tick);
			strokeLine(ctx, tx - 0.8F, ty + 1.9F, tx + 2.5F, ty - 2.0F, 1.4F, tick);
		}
		zone(x, y, w, h, click -> {
			config.guiAccent = index;
			config.save();
			sound(1.0F + index * 0.03F);
		});
	}

	private float drawConfigsPage(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		y = sLabel(ctx, "NEW CONFIG", x, y);
		float btnW = 54.0F;
		float fieldW = w - btnW - 7.0F;
		float fieldH = 24.0F;
		NovaRender.roundRect(ctx, x, y, fieldW, fieldH, 6, WELL);
		NovaRender.roundRectBorder(ctx, x, y, fieldW, fieldH, 6, configNameFocused ? accentA(0xCC) : ROW_LINE);
		boolean empty = configNameInput.isEmpty() && !configNameFocused;
		String shown = empty ? "Config name…"
				: configNameInput + (configNameFocused && (System.currentTimeMillis() / 480) % 2 == 0 ? "_" : "");
		textScaled(ctx, regular(trimLeftToWidth(shown, (fieldW - 16.0F) / T_SET)), x + 8.0F,
				y + (fieldH - 7.0F) / 2.0F, empty ? GHOST : TEXT, T_SET);
		zone(x, y, fieldW, fieldH, click -> {
			configNameFocused = true;
			searchFocused = false;
			listeningId = null;
		});
		sButton(ctx, mx, my, "Save", x + fieldW + 7.0F, y, btnW, fieldH, true, live, () -> {
			String saved = config.saveProfile(configNameInput);
			configStatus = saved != null ? "Saved \"" + saved + "\"" : "Enter a name first.";
			if (saved != null) {
				configNameInput = "";
				configNameFocused = false;
				profileCache = ProFPSConfig.listProfiles();
			}
			sound(1.1F);
		});
		y += fieldH + 8.0F;
		if (!configStatus.isEmpty()) {
			textScaled(ctx, regular(trimToWidth(configStatus, w / T_VAL)), x + 1.0F, y - 3.0F, accentSoft, T_VAL);
			y += 11.0F;
		}
		float half = (w - 7.0F) / 2.0F;
		sButton(ctx, mx, my, "Reset Defaults", x, y, half, 24.0F, false, live, () -> {
			config.resetToDefaults();
			configStatus = "Reset every setting to defaults.";
			sound(0.9F);
		});
		sButton(ctx, mx, my, "Save Current", x + half + 7.0F, y, half, 24.0F, false, live, () -> {
			config.save();
			configStatus = "Saved the live settings.";
			sound(1.1F);
		});
		y += 24.0F + 12.0F;

		y = sLabel(ctx, "SAVED CONFIGS", x, y);
		if (profileCache.isEmpty()) {
			textScaled(ctx, regular("Nothing saved yet — name one above."), x + 1.0F, y, FAINT, T_VAL);
			return y + 14.0F;
		}
		for (String name : profileCache) {
			float rh = 28.0F;
			boolean hovered = live && inside(mx, my, x, y, w, rh) && insideClip(mx, my);
			float hov = anim("cfg_" + name, hovered ? 1.0F : 0.0F, 14.0F);
			NovaRender.roundRect(ctx, x, y, w, rh, 7, NovaRender.lerpColor(hov, WELL, 0xFF23262C));
			NovaRender.roundRectBorder(ctx, x, y, w, rh, 7, NovaRender.lerpColor(hov, ROW_LINE, 0x2EFFFFFF));
			float bw = 42.0F;
			float bh = 18.0F;
			float by = y + (rh - bh) / 2.0F;
			textScaled(ctx, bold(trimToWidth(name, (w - bw * 2.0F - 26.0F) / T_SET)), x + 9.0F,
					y + (rh - 7.0F) / 2.0F, TEXT, T_SET);
			final String profile = name;
			sButton(ctx, mx, my, "Load", x + w - 9.0F - bw * 2.0F - 5.0F, by, bw, bh, true, live, () -> {
				configStatus = config.loadProfile(profile) ? "Loaded \"" + profile + "\"." : "Couldn't load that config.";
				sound(1.1F);
			});
			sButton(ctx, mx, my, "Delete", x + w - 9.0F - bw, by, bw, bh, false, live, () -> {
				ProFPSConfig.deleteProfile(profile);
				configStatus = "Deleted \"" + profile + "\".";
				profileCache = ProFPSConfig.listProfiles();
				sound(0.85F);
			});
			y += rh + 6.0F;
		}
		return y;
	}

	private float drawKeybindsPage(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		for (NovaModules.Category category : categories) {
			y = sLabel(ctx, category.name.toUpperCase(Locale.ROOT), x, y);
			for (NovaModules.Module mod : category.modules) {
				y = sKeybindRow(ctx, mx, my, mod, x, y, w, live);
			}
			y += 5.0F;
		}
		return y;
	}

	private float sKeybindRow(DrawContext ctx, float mx, float my, NovaModules.Module mod,
			float x, float y, float w, boolean live) {
		float h = 26.0F;
		Integer key = config.moduleKeybinds.get(mod.id);
		boolean bound = key != null && key > 0;
		boolean listening = mod.id.equals(listeningId);
		boolean hovered = live && inside(mx, my, x, y, w, h) && insideClip(mx, my);
		float hov = anim("kbr_" + mod.id, hovered ? 1.0F : 0.0F, 14.0F);

		NovaRender.roundRect(ctx, x, y, w, h, 7, NovaRender.lerpColor(hov, WELL, 0xFF23262C));
		NovaRender.roundRectBorder(ctx, x, y, w, h, 7,
				bound || listening ? accentA(0x66) : NovaRender.lerpColor(hov, ROW_LINE, 0x2EFFFFFF));

		Text chip = regular(listening ? "Press a key…" : keyName(key));
		float chipTextW = textRenderer.getWidth(chip) * T_VAL;
		float chipW = Math.max(34.0F, chipTextW + 12.0F);
		float chipX = x + w - 8.0F - chipW;
		float chipH = 16.0F;
		float chipY = y + (h - chipH) / 2.0F;
		textScaled(ctx, regular(trimToWidth(mod.name, (chipX - x - 16.0F) / T_SET)), x + 9.0F,
				y + (h - 7.0F) / 2.0F, bound ? TEXT : TEXT_OFF, T_SET);
		NovaRender.roundRect(ctx, chipX, chipY, chipW, chipH, 5, listening ? accentA(0x33) : 0xFF15171C);
		NovaRender.roundRectBorder(ctx, chipX, chipY, chipW, chipH, 5, listening ? accentA(0xCC) : ROW_LINE);
		textScaled(ctx, chip, chipX + (chipW - chipTextW) / 2.0F, chipY + (chipH - 6.0F) / 2.0F,
				listening ? accentSoft : bound ? TEXT : FAINT, T_VAL);

		zone(x, y, w, h, click -> {
			if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				config.moduleKeybinds.remove(mod.id);
				config.save();
				listeningId = null;
			} else {
				listeningId = mod.id;
				configNameFocused = false;
				searchFocused = false;
			}
			sound(1.0F);
		});
		return y + h + 5.0F;
	}

	private float drawPacketPage(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		PacketManager pm = PacketManager.INSTANCE;
		y = sLabel(ctx, "PACKET TOOLKIT", x, y);
		y = sToggle(ctx, mx, my, "Packet Utils", "Overlay packet controls on every GUI you open.",
				config.packetUtils, () -> config.packetUtils = !config.packetUtils, x, y, w, live);

		if (!config.packetUtils) {
			y += 2.0F;
			for (String line : wrapText("Turn this on, then open any container — a chest, an auction house, "
					+ "a villager — and a Nova toolbar appears with the packet controls.", (int) (w / T_VAL))) {
				textScaled(ctx, regular(line), x + 1.0F, y, FAINT, T_VAL);
				y += 10.0F;
			}
			return y;
		}

		y = sLabel(ctx, "LIVE STATE", x, y + 5.0F);
		y = sToggle(ctx, mx, my, "Send Packets", "Off = drop every outgoing packet.",
				pm.sendPackets, () -> pm.setSendPackets(!pm.sendPackets), x, y, w, live);
		y = sToggle(ctx, mx, my, "Delay Packets", "On = hold outgoing packets; off to blink.",
				pm.delayPackets, () -> pm.setDelayPackets(!pm.delayPackets), x, y, w, live);

		textScaled(ctx, regular(pm.heldCount() + " packet(s) queued"), x + 1.0F, y,
				pm.heldCount() > 0 ? accentSoft : FAINT, T_VAL);
		y += 14.0F;
		float half = (w - 7.0F) / 2.0F;
		sButton(ctx, mx, my, "Flush Queue", x, y, half, 24.0F, false, live, () -> {
			pm.flushHeld();
			sound(1.1F);
		});
		sButton(ctx, mx, my, pm.hasSavedGui() ? "Reopen GUI" : "No Saved GUI",
				x + half + 7.0F, y, half, 24.0F, false, live && pm.hasSavedGui(), () -> {
					pm.reopenSavedGui();
					sound(1.1F);
				});
		y += 24.0F + 10.0F;

		y = sLabel(ctx, "IN-GUI TOOLBAR", x, y);
		for (String line : wrapText("Inside any GUI: close without a packet, de-sync to blink, save the GUI to "
				+ "reopen it here, disconnect and flush, fabricate a slot-click, copy the title JSON.",
				(int) (w / T_VAL))) {
			textScaled(ctx, regular(line), x + 1.0F, y, FAINT, T_VAL);
			y += 10.0F;
		}
		return y;
	}

	// ── Settings widgets ─────────────────────────────────────────────────────

	private float sLabel(DrawContext ctx, String label, float x, float y) {
		textScaled(ctx, bold(label), x + 1.0F, y + 1.0F, GHOST, T_OPT);
		return y + 14.0F;
	}

	private float sToggle(DrawContext ctx, float mx, float my, String label, String desc,
			boolean value, Runnable onToggle, float x, float y, float w, boolean live) {
		float h = desc == null || desc.isEmpty() ? 26.0F : 34.0F;
		boolean hovered = live && inside(mx, my, x, y, w, h) && insideClip(mx, my);
		float hov = anim("st_" + label, hovered ? 1.0F : 0.0F, 14.0F);
		float en = anim("se_" + label, value ? 1.0F : 0.0F, 12.0F);

		NovaRender.roundRect(ctx, x, y, w, h, 7, NovaRender.lerpColor(hov, WELL, 0xFF23262C));
		NovaRender.roundRectBorder(ctx, x, y, w, h, 7,
				NovaRender.lerpColor(en, NovaRender.lerpColor(hov, ROW_LINE, 0x2EFFFFFF), accentA(0x5C)));
		boolean hasDesc = desc != null && !desc.isEmpty();
		textScaled(ctx, bold(label), x + 10.0F, hasDesc ? y + 7.0F : y + (h - 7.0F) / 2.0F, TEXT, T_SET);
		if (hasDesc) {
			textScaled(ctx, regular(trimToWidth(desc, (w - 52.0F) / T_VAL)), x + 10.0F, y + 19.0F, FAINT, T_VAL);
		}
		drawTogglePill(ctx, en, x + w - 10.0F - 22.0F, y + (h - 11.0F) / 2.0F, 22.0F, 11.0F, accent());
		zone(x, y, w, h, click -> {
			onToggle.run();
			config.save();
			sound(value ? 0.85F : 1.15F);
		});
		return y + h + 6.0F;
	}

	private void sButton(DrawContext ctx, float mx, float my, String label, float x, float y,
			float w, float h, boolean primary, boolean enabled, Runnable onClick) {
		boolean hovered = enabled && inside(mx, my, x, y, w, h) && insideClip(mx, my);
		float hov = anim("sb_" + label + "_" + (int) x + "_" + (int) y, hovered ? 1.0F : 0.0F, 15.0F);
		Text text = bold(label);
		float tw = textRenderer.getWidth(text) * T_VAL;
		if (primary && enabled) {
			NovaRender.roundRect(ctx, x, y, w, h, 6, NovaRender.lerpColor(hov, accentBase, accentSoft));
			textScaled(ctx, text, x + (w - tw) / 2.0F, y + (h - 6.0F) / 2.0F, 0xFF06121B, T_VAL);
		} else {
			NovaRender.roundRect(ctx, x, y, w, h, 6, enabled ? NovaRender.lerpColor(hov, WELL, 0xFF2C3038) : 0xFF141619);
			NovaRender.roundRectBorder(ctx, x, y, w, h, 6,
					enabled ? NovaRender.lerpColor(hov, ROW_LINE, accentA(0x99)) : 0x0EFFFFFF);
			textScaled(ctx, text, x + (w - tw) / 2.0F, y + (h - 6.0F) / 2.0F,
					enabled ? NovaRender.lerpColor(hov, FAINT, TEXT) : GHOST, T_VAL);
		}
		if (enabled) zone(x, y, w, h, click -> onClick.run());
	}

	/** Manual UI size: a −/value/+ stepper that only appears when Automatic is off. */
	private float sScaleRow(DrawContext ctx, float mx, float my, float x, float y, float w, boolean live) {
		float h = 34.0F;
		NovaRender.roundRect(ctx, x, y, w, h, 7, WELL);
		NovaRender.roundRectBorder(ctx, x, y, w, h, 7, ROW_LINE);
		textScaled(ctx, bold("UI Size"), x + 10.0F, y + 7.0F, TEXT, T_SET);

		int applied = Math.round(uiScale * 100.0F);
		String detail = config.guiAutoScale
				? "Automatic fit · " + applied + "% applied"
				: config.guiScalePct + "% requested · " + applied + "% window fit";
		textScaled(ctx, regular(trimToWidth(detail, (w - 108.0F) / T_VAL)), x + 10.0F, y + 19.0F, FAINT, T_VAL);

		float right = x + w - 10.0F;
		float controlY = y + (h - 18.0F) / 2.0F;
		if (config.guiAutoScale) {
			float chipW = 58.0F;
			NovaRender.roundRect(ctx, right - chipW, controlY, chipW, 18.0F, 6, accentA(0x2E));
			Text auto = bold("AUTO " + applied + "%");
			float tw = textRenderer.getWidth(auto) * T_OPT;
			textScaled(ctx, auto, right - chipW + (chipW - tw) / 2.0F, controlY + 6.0F, accentSoft, T_OPT);
		} else {
			float bw = 18.0F;
			float valueW = 38.0F;
			float plusX = right - bw;
			float valueX = plusX - 4.0F - valueW;
			float minusX = valueX - 4.0F - bw;
			sStep(ctx, mx, my, "−", minusX, controlY, bw, live && config.guiScalePct > NovaUiScale.MIN_MANUAL_PERCENT, -5);
			NovaRender.roundRect(ctx, valueX, controlY, valueW, 18.0F, 5, 0xFF15171C);
			NovaRender.roundRectBorder(ctx, valueX, controlY, valueW, 18.0F, 5, ROW_LINE);
			Text value = bold(config.guiScalePct + "%");
			float tw = textRenderer.getWidth(value) * T_VAL;
			textScaled(ctx, value, valueX + (valueW - tw) / 2.0F, controlY + 6.0F, TEXT, T_VAL);
			sStep(ctx, mx, my, "+", plusX, controlY, bw, live && config.guiScalePct < NovaUiScale.MAX_MANUAL_PERCENT, 5);
		}
		return y + h + 6.0F;
	}

	private void sStep(DrawContext ctx, float mx, float my, String label, float x, float y,
			float size, boolean enabled, int delta) {
		boolean hovered = enabled && inside(mx, my, x, y, size, 18.0F) && insideClip(mx, my);
		float hov = anim("sstep_" + delta, hovered ? 1.0F : 0.0F, 15.0F);
		NovaRender.roundRect(ctx, x, y, size, 18.0F, 5, enabled ? NovaRender.lerpColor(hov, WELL, 0xFF2C3038) : 0xFF141619);
		NovaRender.roundRectBorder(ctx, x, y, size, 18.0F, 5,
				enabled ? NovaRender.lerpColor(hov, ROW_LINE, accentA(0x99)) : 0x0EFFFFFF);
		Text text = bold(label);
		float tw = textRenderer.getWidth(text) * T_SET;
		textScaled(ctx, text, x + (size - tw) / 2.0F, y + (18.0F - 7.0F) / 2.0F, enabled ? TEXT : GHOST, T_SET);
		if (enabled) {
			zone(x, y, size, 18.0F, click -> {
				config.guiScalePct = MathHelper.clamp(config.guiScalePct + delta,
						NovaUiScale.MIN_MANUAL_PERCENT, NovaUiScale.MAX_MANUAL_PERCENT);
				config.save();
				sound(delta > 0 ? 1.12F : 0.92F);
			});
		}
	}

	private void captureTooltip(NovaModules.Module mod, float mx, float my) {
		frameHover = mod.id;
		if (!mod.id.equals(hoverId)) {
			hoverId = mod.id;
			hoverSinceNanos = System.nanoTime();
			return;
		}
		if (System.nanoTime() - hoverSinceNanos < 420_000_000L) return;
		String description = NovaModules.description(mod.id);
		if (description == null || description.isEmpty()) return;
		tipText = description;
		tipX = mx;
		tipY = my;
	}

	private void drawTooltip(DrawContext ctx) {
		if (tipText == null) return;
		float scale = T_VAL;
		float maxW = 168.0F;
		List<String> lines = wrapText(tipText, (int) (maxW / scale));
		float lineH = 9.0F * scale + 2.0F;
		float boxW = 0.0F;
		for (String line : lines) boxW = Math.max(boxW, textRenderer.getWidth(line) * scale);
		boxW += 12.0F;
		float boxH = lines.size() * lineH + 8.0F;
		float bx = MathHelper.clamp(tipX + 11.0F, 4.0F, vw - boxW - 4.0F);
		float by = MathHelper.clamp(tipY + 12.0F, 4.0F, vh - boxH - 4.0F);
		NovaRender.roundRect(ctx, bx, by, boxW, boxH, 6, 0xF60F1014);
		NovaRender.roundRectBorder(ctx, bx, by, boxW, boxH, 6, 0x22FFFFFF);
		float ty = by + 5.0F;
		for (String line : lines) {
			textScaled(ctx, regular(line), bx + 6.0F, ty, 0xFFD5D9E0, scale);
			ty += lineH;
		}
	}

	private void notice(String message) {
		noticeText = message;
		noticeUntilNanos = System.nanoTime() + 2_800_000_000L;
	}

	private void drawNotice(DrawContext ctx) {
		if (noticeText == null) return;
		long remaining = noticeUntilNanos - System.nanoTime();
		if (remaining <= 0L) {
			noticeText = null;
			return;
		}
		float fade = MathHelper.clamp(remaining / 320_000_000.0F, 0.0F, 1.0F);
		float rise = 1.0F - MathHelper.clamp((2_800_000_000.0F - remaining) / 150_000_000.0F, 0.0F, 1.0F);
		List<String> lines = wrapText(noticeText, (int) (280.0F / T_VAL));
		float bw = 0.0F;
		for (String line : lines) bw = Math.max(bw, textRenderer.getWidth(line) * T_VAL);
		bw += 22.0F;
		float bh = 14.0F + lines.size() * 10.0F;
		float bx = (vw - bw) / 2.0F;
		float by = vh - bh - 26.0F + rise * 6.0F;

		float base = NovaRender.getAlpha();
		NovaRender.setAlpha(base * fade);
		NovaRender.roundRect(ctx, bx, by, bw, bh, 8, 0xF814161A);
		NovaRender.roundRectBorder(ctx, bx, by, bw, bh, 8, NovaRender.withAlpha(activeModeColor() & 0xFFFFFF, 0x88));
		NovaRender.roundRect(ctx, bx + 1.5F, by + 7.0F, 2.0F, bh - 14.0F, 1.0F,
				NovaRender.withAlpha(activeModeColor() & 0xFFFFFF, 0xDC));
		float ly = by + 8.0F;
		for (String line : lines) {
			textScaled(ctx, regular(line), bx + 11.0F, ly, TEXT, T_VAL);
			ly += 10.0F;
		}
		NovaRender.setAlpha(base);
	}

	// ── Widgets ──────────────────────────────────────────────────────────────

	/** The one module toggle: a flat pill whose track takes the theme colour, knob runs white. */
	private void drawTogglePill(DrawContext ctx, float en, float x, float y, float w, float h, int onColor) {
		NovaRender.roundRect(ctx, x, y, w, h, h / 2.0F,
				NovaRender.lerpColor(en, TRACK, NovaRender.lerpColor(0.25F, onColor, TRACK_ON)));
		NovaRender.fillCircle(ctx, x + h / 2.0F + en * (w - h), y + h / 2.0F, h / 2.0F - 2.1F,
				NovaRender.lerpColor(en, KNOB_OFF, 0xFFFFFFFF));
	}

	/** The one sub-setting control: a checkbox that fills with the theme and takes a dark tick. */
	private void drawCheckbox(DrawContext ctx, float cx, float cy, float en, boolean available, float hov) {
		float s = 4.3F;
		NovaRender.roundRect(ctx, cx - s, cy - s, s * 2.0F, s * 2.0F, 2.5F,
				NovaRender.lerpColor(en, available ? NovaRender.lerpColor(hov, WELL, 0xFF2F333B) : 0xFF1A1C20,
						available ? accent() : 0xFF737A85));
		if (en < 0.97F) {
			NovaRender.roundRectBorder(ctx, cx - s, cy - s, s * 2.0F, s * 2.0F, 2.5F,
					NovaRender.withAlpha(0xFFFFFF, (int) ((0x24 + 0x28 * hov) * (1.0F - en))));
		}
		if (en > 0.15F) {
			int tick = NovaRender.withAlpha(0x15171B, (int) (255 * MathHelper.clamp((en - 0.15F) / 0.6F, 0.0F, 1.0F)));
			strokeLine(ctx, cx - 2.1F, cy + 0.1F, cx - 0.6F, cy + 1.7F, 1.35F, tick);
			strokeLine(ctx, cx - 0.6F, cy + 1.7F, cx + 2.2F, cy - 1.8F, 1.35F, tick);
		}
	}

	/** A stroke built from overlapping antialiased discs — crisper than integer fills at this size. */
	private void strokeLine(DrawContext ctx, float x1, float y1, float x2, float y2, float thickness, int color) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		int steps = Math.max(1, Math.round((float) Math.sqrt(dx * dx + dy * dy) / 0.55F));
		for (int i = 0; i <= steps; i++) {
			float t = i / (float) steps;
			NovaRender.fillCircle(ctx, x1 + dx * t, y1 + dy * t, thickness / 2.0F, color);
		}
	}

	private void drawItemIcon(DrawContext ctx, ItemStack stack, float x, float y, float size) {
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(size / 16.0F, size / 16.0F);
		ctx.drawItem(stack, 0, 0);
		m.popMatrix();
	}

	// ── Model helpers ────────────────────────────────────────────────────────

	private boolean effectiveOn(NovaModules.Module mod) {
		Boolean managed = NovaModules.managedState(config, mod.id);
		return managed != null ? managed : mod.get.get();
	}

	private String modeKeyOf(NovaModules.Module mod) {
		return switch (mod.combatMode) {
			case 1 -> "sword";
			case 2 -> "axe";
			case 3 -> "mace";
			default -> null;
		};
	}

	private int modeTier(String key) {
		int tier = switch (key == null ? "" : key) {
			case "sword" -> config.swordModeTier;
			case "axe" -> config.axeModeTier;
			case "mace" -> config.maceModeTier;
			default -> 0;
		};
		return MathHelper.clamp(tier, 0, MODE_TIERS.length - 1);
	}

	private int activeModeColor() {
		String key = switch (config.combatMode) {
			case 1 -> "sword";
			case 2 -> "axe";
			case 3 -> "mace";
			default -> null;
		};
		return key == null ? 0xFFFFFFFF : MODE_TIER_COLORS[modeTier(key)];
	}

	private String keyName(Integer key) {
		if (key == null || key <= 0) return "None";
		try {
			return InputUtil.fromKeyCode(new KeyInput(key, 0, 0)).getLocalizedText().getString().toUpperCase(Locale.ROOT);
		} catch (RuntimeException exception) {
			return "KEY" + key;
		}
	}

	// ── Text ─────────────────────────────────────────────────────────────────

	private Text regular(String s) {
		return cachedText("r:", s, FONT);
	}

	private Text bold(String s) {
		return cachedText("b:", s, FONT_BOLD);
	}

	private Text cachedText(String prefix, String s, Style style) {
		if (textCache.size() > 512) textCache.clear();
		return textCache.computeIfAbsent(prefix + s, key -> Text.literal(s).setStyle(style));
	}

	private void text(DrawContext ctx, Text t, float x, float y, int argb) {
		int a = (int) ((argb >>> 24) * NovaRender.getAlpha());
		if (a < 8) return;
		ctx.drawText(textRenderer, t, Math.round(x), Math.round(y), (a << 24) | (argb & 0xFFFFFF), false);
	}

	private void textScaled(DrawContext ctx, Text t, float x, float y, int argb, float scale) {
		int a = (int) ((argb >>> 24) * NovaRender.getAlpha());
		if (a < 8) return;
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.scale(scale, scale);
		ctx.drawText(textRenderer, t, Math.round(x / scale), Math.round(y / scale), (a << 24) | (argb & 0xFFFFFF), false);
		m.popMatrix();
	}

	private String trimToWidth(String s, float maxWidth) {
		return textRenderer.trimToWidth(s, Math.max(6, (int) maxWidth));
	}

	private String trimLeftToWidth(String s, float maxWidth) {
		while (s.length() > 1 && textRenderer.getWidth(s) > maxWidth) {
			s = s.substring(1);
		}
		return s;
	}

	private List<String> wrapText(String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String trial = line.isEmpty() ? word : line + " " + word;
			if (textRenderer.getWidth(trial) > maxWidth && !line.isEmpty()) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(trial);
			}
		}
		if (!line.isEmpty()) out.add(line.toString());
		return out;
	}

	// ── Motion / geometry ────────────────────────────────────────────────────

	private static float easeOutCubic(float t) {
		float u = 1.0F - t;
		return 1.0F - u * u * u;
	}

	private static float smooth(float v) {
		return v * v * (3.0F - 2.0F * v);
	}

	/**
	 * Eased approach towards {@code target}, stepped at most once per key per frame.
	 *
	 * <p>The once-per-frame guard matters here: a row's height is asked for while laying the column
	 * out and again while drawing it. Without the guard those calls each advance the same spring,
	 * so the row would ease at a multiple of the intended speed and — worse — be measured at one
	 * value and clipped at the next, which shows up as a one-frame shear during the reveal.
	 */
	private float anim(String key, float target, float speed) {
		if (!config.guiAnimations) {
			anims.put(key, target);
			return target;
		}
		Float settled = frameAnims.get(key);
		if (settled != null) return settled;
		float v = anims.computeIfAbsent(key, k -> target);
		v += (target - v) * Math.min(1.0F, frameDelta * speed);
		if (Math.abs(target - v) < 0.002F) v = target;
		anims.put(key, v);
		frameAnims.put(key, v);
		return v;
	}

	private int accent() {
		return accentBase;
	}

	private int accentA(int alpha) {
		return NovaRender.withAlpha(accentBase & 0xFFFFFF, alpha);
	}

	private void applyTheme() {
		int[] preset = NovaTheme.accent(config.guiAccent);
		accentSoft = preset[0];
		accentBase = preset[1];
		accentDeep = preset[2];
	}

	private boolean inside(double mx, double my, double x, double y, double w, double h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private boolean insideClip(double mx, double my) {
		return zcw <= 0.0F || inside(mx, my, zcx, zcy, zcw, zch);
	}

	private static int floor(float v) {
		return (int) Math.floor(v);
	}

	private static int ceil(float v) {
		return (int) Math.ceil(v);
	}

	private void sound(float pitch) {
		if (!config.guiSounds) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null) mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, pitch));
	}

	// ── Click zones ──────────────────────────────────────────────────────────

	private float[] pushClip(float x, float y, float w, float h) {
		float[] previous = {zcx, zcy, zcw, zch};
		zcx = x;
		zcy = y;
		zcw = w;
		zch = h;
		return previous;
	}

	private float[] pushClipIntersect(float x, float y, float w, float h) {
		if (zcw <= 0.0F) return pushClip(x, y, w, h);
		float nx = Math.max(zcx, x);
		float ny = Math.max(zcy, y);
		float nr = Math.min(zcx + zcw, x + w);
		float nb = Math.min(zcy + zch, y + h);
		return pushClip(nx, ny, Math.max(0.01F, nr - nx), Math.max(0.01F, nb - ny));
	}

	private void popClip(float[] previous) {
		if (previous == null) return;
		zcx = previous[0];
		zcy = previous[1];
		zcw = previous[2];
		zch = previous[3];
	}

	private void zone(float x, float y, float w, float h, ZoneAction action) {
		zones.add(new Zone(x, y, w, h, zcx, zcy, zcw, zch, action));
	}

	private interface ZoneAction {
		void run(Click click);
	}

	private record Zone(float x, float y, float w, float h,
			float cx, float cy, float cw, float ch, ZoneAction action) {
		boolean hit(double mx, double my) {
			if (mx < x || mx > x + w || my < y || my > y + h) return false;
			return cw <= 0.0F || (mx >= cx && mx <= cx + cw && my >= cy && my <= cy + ch);
		}
	}

	// ── Input ────────────────────────────────────────────────────────────────

	private void applySlider(double mouseX) {
		if (activeSlider == null || sliderTrackW <= 0.0F) return;
		float t = MathHelper.clamp((float) ((mouseX - sliderTrackX) / sliderTrackW), 0.0F, 1.0F);
		int raw = activeSlider.min
				+ Math.round(t * (activeSlider.max - activeSlider.min) / (float) activeSlider.step) * activeSlider.step;
		activeSlider.set.accept(MathHelper.clamp(raw, activeSlider.min, activeSlider.max));
	}

	private Click toUiClick(Click click) {
		return new Click(click.x() / uiScale, click.y() / uiScale, click.buttonInfo());
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (listeningId != null) {
			listeningId = null;
			return true;
		}
		searchFocused = false;
		pickerFocused = false;
		configNameFocused = false;
		activeString = null;
		Click uiClick = toUiClick(click);
		for (int i = zones.size() - 1; i >= 0; i--) {
			Zone zone = zones.get(i);
			if (!zone.hit(uiClick.x(), uiClick.y())) continue;
			zone.action.run(uiClick);
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (activeSlider != null) {
			applySlider(click.x() / uiScale);
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (activeSlider != null) {
			activeSlider = null;
			config.save();
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		double mx = mouseX / uiScale;
		double my = mouseY / uiScale;
		if (settingsOpen) {
			if (settingsBodyW > 0.0F && inside(mx, my, settingsBodyX, settingsBodyY, settingsBodyW, settingsBodyH)) {
				settingsScrollTarget = MathHelper.clamp(settingsScrollTarget - (float) verticalAmount * SCROLL_STEP,
						0.0F, settingsScrollMax);
			}
			return true; // never scroll the grid out from under an open panel
		}
		// A block list under the cursor takes the wheel before the column it lives in does.
		if (pickerListW > 0.0F && inside(mx, my, pickerListX, pickerListY, pickerListW, pickerListH)) {
			pickerScroll -= (float) verticalAmount * PICKER_ROW_H * 1.8F;
			return true;
		}
		for (int i = 0; i < categories.size(); i++) {
			if (colH[i] <= 1.5F) continue;
			if (!inside(mx, my, columnX(i), panelY, COL_W, colH[i])) continue;
			// Clamped here, not after the fact. Letting the target run past the end and correcting
			// it on the next frame is what made an over-scroll lurch and snap back.
			scrollTarget[i] = MathHelper.clamp(scrollTarget[i] - (float) verticalAmount * SCROLL_STEP,
					0.0F, scrollMax[i]);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();
		// A different key supersedes the pending one. Non-printable keys never
		// produce a character event at all, so without this the suppression could
		// outlive its keystroke and eat an unrelated letter later on.
		if (bindingConsumedKey != -1 && key != bindingConsumedKey) bindingConsumedKey = -1;
		if (settingsOpen && key == GLFW.GLFW_KEY_ESCAPE && listeningId == null && !configNameFocused) {
			closeSettings();
			return true;
		}
		if (listeningId != null) {
			// GLFW reports a keystroke as TWO independent events: the key callback
			// (this) and, for anything printable, a character callback a moment
			// later. Consuming the key does nothing to the character — so binding
			// "K" cleared listeningId here, and charTyped then found nothing
			// listening and filed the k as ordinary typing, into the module
			// search. Claim the other half of the keystroke explicitly, and keep
			// claiming it until the key is physically released, because a held key
			// repeats both callbacks.
			bindingConsumedKey = key;
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
				config.moduleKeybinds.remove(listeningId);
				config.save();
				listeningId = null;
			} else if (key != GLFW.GLFW_KEY_RIGHT_SHIFT) {
				config.moduleKeybinds.put(listeningId, key);
				config.save();
				listeningId = null;
				sound(1.2F);
			}
			return true;
		}
		if (activeString != null) {
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
				activeString = null;
				config.save();
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				String s = activeString.get.get();
				if (s != null && !s.isEmpty()) activeString.set.accept(s.substring(0, s.length() - 1));
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) return true;
		}
		if (configNameFocused) {
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
				configNameFocused = false;
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!configNameInput.isEmpty()) configNameInput = configNameInput.substring(0, configNameInput.length() - 1);
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) return true;
		}
		if (pickerFocused) {
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
				pickerFocused = false;
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!pickerQuery.isEmpty()) pickerQuery = pickerQuery.substring(0, pickerQuery.length() - 1);
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) return true;
		}
		if (searchFocused) {
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
				if (key == GLFW.GLFW_KEY_ESCAPE) clearSearch();
				searchFocused = false;
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!query.isEmpty()) {
					query = query.substring(0, query.length() - 1);
					resetColumnScroll();
				}
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && !query.isEmpty()) {
			clearSearch();
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			close();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyInput input) {
		// Letting go of the bound key is what ends the suppression: until then the
		// key is still repeating, and every repeat carries a character with it.
		if (input.key() == bindingConsumedKey) bindingConsumedKey = -1;
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		String s = input.asString();
		// The character half of a keystroke that was spent on a keybind — either
		// one still being captured, or the one just captured by keyPressed. It
		// belongs to the bind, not to any text field.
		if (listeningId != null || bindingConsumedKey != -1) return true;
		if (activeString != null) {
			if (!s.isBlank() || s.equals(" ")) {
				String current = activeString.get.get();
				if (current == null) current = "";
				if (current.length() < 100) activeString.set.accept(current + s);
			}
			return true;
		}
		if (configNameFocused) {
			if ((!s.isBlank() || s.equals(" ")) && configNameInput.length() < 40) configNameInput += s;
			return true;
		}
		if (pickerFocused) {
			if ((!s.isBlank() || s.equals(" ")) && pickerQuery.length() < 32) pickerQuery += s;
			return true;
		}
		// With the panel up, typing belongs to its fields — not to the search behind it.
		if (settingsOpen) return true;
		if (searchFocused) {
			if ((!s.isBlank() || s.equals(" ")) && query.length() < 28) {
				query += s;
				resetColumnScroll();
			}
			return true;
		}
		// Typing anywhere in the grid goes to the search — no need to hunt for the field first.
		if (!s.isBlank() && query.length() < 28) {
			searchFocused = true;
			query += s;
			resetColumnScroll();
			return true;
		}
		return super.charTyped(input);
	}

	private void clearSearch() {
		query = "";
		resetColumnScroll();
	}

	private void resetColumnScroll() {
		for (int i = 0; i < scrollTarget.length; i++) scrollTarget[i] = 0.0F;
	}

	@Override
	public void close() {
		config.save();
		if (client != null) {
			client.setScreen(client.world == null ? new net.minecraft.client.gui.screen.TitleScreen() : null);
		}
	}
}
