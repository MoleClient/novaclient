package com.profps.client.ui.nova;

import com.profps.client.config.NickEntry;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Declarative catalogue of every NovaClient module, bound straight to
 * {@link ProFPSConfig} fields. Both the GUI and the in-game keybind handler
 * work off this model, so toggling from either place behaves identically
 * (including the one real cross-module dependency: Stash Pinger feeds off the
 * Storage ESP container scan, so enabling it pulls Storage ESP on — Mob ESP and
 * Hole/Tunnel/Stairs ESP are independent of both).
 */
public final class NovaModules {
	/** Mode module ids. These match the keybind ids the Modes panel used, so existing binds survive. */
	public static final String MODE_SWORD = "combat_mode_sword";
	public static final String MODE_AXE = "combat_mode_axe";
	public static final String MODE_MACE = "combat_mode_mace";

	/**
	 * Standalone modules each mode actually drives, mapped to the mode-side switch that stands in
	 * for them — and ONLY those. A mode owning the mace does not own your Triggerbot: anything not
	 * listed here keeps running from its own module while the mode is on, which is exactly what
	 * {@code CombatModePolicy.enabled} now falls through to.
	 */
	private static final Map<Integer, Map<String, String>> MANAGED = Map.of(
			1, Map.of("aim", "swordModeAim", "strafe", "swordModeStrafe",
					"hit", "swordModeTrigger", "swordai", "swordModeAiBot"),
			2, Map.of("aim", "axeModeAim", "axestun", "axeModeStun",
					"autoaim", "axeModeProjectileAim", "hit", "axeModeTrigger"),
			3, Map.of("automace", "maceModeAutoMace", "autobreachswap", "maceModeBreachSwap"));

	private static final Map<Integer, String> MODE_NAMES =
			Map.of(1, "Sword Mode", 2, "Axe Mode", 3, "Mace Mode");

	private NovaModules() {}

	/**
	 * Whether the ACTIVE combat mode has taken this module over, and if so whether the mode is
	 * currently running it. Null means the module is the player's own to toggle. Drives three
	 * things: the UI locks the card, paints it in mode colours instead of the accent, and the HUD
	 * leaves it out so only the mode itself is listed. Modules the mode does not drive return null
	 * and stay entirely yours — Triggerbot is still yours to run while Mace mode is on.
	 */
	public static Boolean managedState(ProFPSConfig cfg, String moduleId) {
		if (cfg == null || moduleId == null) return null;
		Map<String, String> managed = MANAGED.get(cfg.combatMode);
		if (managed == null) return null;
		String field = managed.get(moduleId);
		return field == null ? null : modeSwitch(cfg, field);
	}

	/** Display name of the mode that owns this module, or null if nothing owns it. */
	public static String managedBy(ProFPSConfig cfg, String moduleId) {
		return managedState(cfg, moduleId) == null ? null : MODE_NAMES.get(cfg.combatMode);
	}

	private static boolean modeSwitch(ProFPSConfig cfg, String field) {
		return switch (field) {
			case "swordModeAim" -> cfg.swordModeAim && !cfg.swordModeAiBot;
			case "swordModeStrafe" -> cfg.swordModeStrafe && !cfg.swordModeAiBot;
			case "swordModeTrigger" -> cfg.swordModeTrigger;
			case "swordModeAiBot" -> cfg.swordModeAiBot;
			case "axeModeAim" -> cfg.axeModeAim;
			case "axeModeStun" -> cfg.axeModeStun;
			case "axeModeProjectileAim" -> cfg.axeModeProjectileAim;
			case "axeModeTrigger" -> cfg.axeModeTrigger;
			case "maceModeAutoMace" -> cfg.maceModeAutoMace;
			case "maceModeBreachSwap" -> cfg.maceModeBreachSwap;
			default -> false;
		};
	}

	public static final class Category {
		public final String name;
		public final ItemStack icon;
		public final List<Module> modules;

		Category(String name, Item icon, List<Module> modules) {
			this.name = name;
			this.icon = new ItemStack(icon);
			this.modules = modules;
		}
	}

	public static final class Module {
		public final String id;
		public final String name;
		public final ItemStack icon;
		public final Supplier<Boolean> get;
		public final Consumer<Boolean> set;
		public final List<Setting> settings;
		public boolean momentary; // keybind-only action (no persistent toggle) — UI hides the toggle pill
		/** 0 for an ordinary module; 1/2/3 mark the Sword/Axe/Mace combat modes, which the UI draws
		 *  as hero cards and which take over the standalone modules listed in {@link #MANAGED}. */
		public int combatMode;

		Module(String id, String name, Item icon, Supplier<Boolean> get, Consumer<Boolean> set, Setting... settings) {
			this(id, name, new ItemStack(icon), get, set, settings);
		}

		Module(String id, String name, ItemStack icon, Supplier<Boolean> get, Consumer<Boolean> set, Setting... settings) {
			this.id = id;
			this.name = name;
			this.icon = icon;
			this.get = get;
			this.set = set;
			this.settings = List.of(settings);
		}

		/** Mark this as a keybind-fired one-shot: no toggle pill, triggered only by its bound key. */
		Module momentary() {
			this.momentary = true;
			return this;
		}

		/** Mark this as a combat mode: exclusive with the other modes, drawn as a hero card. */
		Module mode(int combatMode) {
			this.combatMode = combatMode;
			return this;
		}

		public boolean isMode() {
			return combatMode > 0;
		}
	}

	public abstract static sealed class Setting permits BoolSetting, IntSetting, ChoiceSetting, BlockPickerSetting, StringSetting, ButtonSetting, NickListSetting, TierSetting {
		public final String label;
		private Supplier<Boolean> visible = () -> true;

		Setting(String label) {
			this.label = label;
		}

		Setting when(Supplier<Boolean> visible) {
			this.visible = visible == null ? () -> true : visible;
			return this;
		}

		public boolean isVisible() {
			return visible.get();
		}
	}

	/** An ordinary inline mode selector, rendered with the same dropdown language as combat tiers. */
	public static final class ChoiceSetting extends Setting {
		public final List<String> options;
		public final IntSupplier get;
		public final IntConsumer set;

		ChoiceSetting(String label, List<String> options, IntSupplier get, IntConsumer set) {
			super(label);
			if (options == null || options.isEmpty()) throw new IllegalArgumentException("ChoiceSetting needs options");
			this.options = List.copyOf(options);
			this.get = get;
			this.set = set;
		}

		ChoiceSetting(String label, String[] options, IntSupplier get, IntConsumer set) {
			this(label, List.of(options), get, set);
		}
	}

	/** A momentary action button (e.g. Advanced ESP "Reload"). */
	public static final class ButtonSetting extends Setting {
		public final String caption;
		public final Runnable action;

		ButtonSetting(String label, String caption, Runnable action) {
			super(label);
			this.caption = caption;
			this.action = action;
		}
	}

	/**
	 * A dynamic list of "real name → shown name (+ skin)" rows, for Nick Other. The
	 * UI edits {@link #entries} in place (add via +, remove via −) and calls
	 * {@link #onChange} to persist.
	 */
	public static final class NickListSetting extends Setting {
		public final List<NickEntry> entries;
		public final Runnable onChange;

		NickListSetting(String label, List<NickEntry> entries, Runnable onChange) {
			super(label);
			this.entries = entries;
			this.onChange = onChange;
		}
	}

	/** A free-text field (e.g. the Spam message) — click to focus, type to edit. */
	public static final class StringSetting extends Setting {
		public final String placeholder;
		public final Supplier<String> get;
		public final Consumer<String> set;

		StringSetting(String label, String placeholder, Supplier<String> get, Consumer<String> set) {
			super(label);
			this.placeholder = placeholder;
			this.get = get;
			this.set = set;
		}
	}

	/**
	 * A searchable multi-select block list (e.g. BreakOn's "Certain Blocks"): an
	 * on/off toggle that, when on, expands a dropdown of block suggestions you
	 * tick. The selected list is the live config list, mutated in place by the UI.
	 */
	public static final class BlockPickerSetting extends Setting {
		public final Supplier<Boolean> enabledGet;
		public final Consumer<Boolean> enabledSet;
		public final List<String> selected;

		BlockPickerSetting(String label, Supplier<Boolean> enabledGet, Consumer<Boolean> enabledSet, List<String> selected) {
			super(label);
			this.enabledGet = enabledGet;
			this.enabledSet = enabledSet;
			this.selected = selected;
		}
	}

	public static final class BoolSetting extends Setting {
		public final Supplier<Boolean> get;
		public final Consumer<Boolean> set;
		/** Dependency gate — false greys the row out and refuses the click (e.g. "requires AI Bot"). */
		public final Supplier<Boolean> available;

		BoolSetting(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
			this(label, get, set, () -> true);
		}

		BoolSetting(String label, Supplier<Boolean> get, Consumer<Boolean> set, Supplier<Boolean> available) {
			super(label);
			this.get = get;
			this.set = set;
			this.available = available;
		}
	}

	/**
	 * The LT5→HT1 strength ramp for a combat mode. Its own type because it is neither a plain int
	 * slider nor a toggle: it renders as a ten-notch track carrying the tier colour ramp.
	 */
	public static final class TierSetting extends Setting {
		public final String modeKey;      // "sword" / "axe" / "mace"
		public final IntSupplier get;
		public final IntConsumer set;

		TierSetting(String label, String modeKey, IntSupplier get, IntConsumer set) {
			super(label);
			this.modeKey = modeKey;
			this.get = get;
			this.set = set;
		}
	}

	public static final class IntSetting extends Setting {
		public final String unit;
		public final int min;
		public final int max;
		public final int step;
		public final int divisor;   // display value = stored / divisor (1 = plain int; >1 shows a decimal)
		public final IntSupplier get;
		public final IntConsumer set;

		IntSetting(String label, String unit, int min, int max, int step, IntSupplier get, IntConsumer set) {
			this(label, unit, min, max, step, 1, get, set);
		}

		IntSetting(String label, String unit, int min, int max, int step, int divisor, IntSupplier get, IntConsumer set) {
			super(label);
			this.unit = unit;
			this.min = min;
			this.max = max;
			this.step = step;
			this.divisor = Math.max(1, divisor);
			this.get = get;
			this.set = set;
		}
	}

	/** Red splash potion icon for Auto Pot. */
	private static ItemStack healingSplashPotion() {
		ItemStack stack = new ItemStack(Items.SPLASH_POTION);
		stack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(Potions.HEALING));
		return stack;
	}

	/** One-line description per module id, shown as a tooltip in the panel. */
	private static final java.util.Map<String, String> DESCRIPTIONS = buildDescriptions();

	/** The blurb for a module id, or empty if none. */
	public static String description(String id) {
		return DESCRIPTIONS.getOrDefault(id, "");
	}

	private static java.util.Map<String, String> buildDescriptions() {
		java.util.Map<String, String> d = new java.util.HashMap<>();
		d.put("aim", "Eases your view toward your target.");
		d.put("strafe", "Side-steps you off their crosshair on hit.");
		d.put("hit", "Triggerbot with humanized reaction and real misses.");
		d.put("reach", "BLATANT FLAG · Extends multiplayer attack range beyond vanilla.");
		d.put("expandedhitbox", "Turns near-miss clicks into a real ray through the player's hitbox.");
		d.put("autoaim", "Aim assist for bows and fireballs.");
		d.put("jumpreset", "Auto-jumps when hit to cut knockback.");
		d.put("autopot", "Throws a healing potion when low.");
		d.put("velocity", "BLATANT FLAG · Alters server-authored knockback.");
		d.put("axestun", "Swaps to an axe to break a shield, then swaps back.");
		d.put("kbdisplace", "Keybind: sprint-reset hit that shoves a nearby player back.");
		d.put("automace", "Auto-attacks the nearest player with mace.");
		d.put("autobreachswap", "Server-ordered sword-to-Breach-mace jump-crit swap and restore.");
		d.put("autolunge", "Swaps a Lunge spear in on the attack frame for a fast movement burst.");
		d.put("autospear", "Times the spear charge so flying through a player lands the kinetic hit.");
		d.put("anchor", "Reliably places and charges an anchor, with optional detonation or safe-item finish.");
		d.put("totem", "Rapidly refills your offhand and prepares a hotbar backup after a pop.");
		d.put("autocrystal", "Right-click obsidian or bedrock to place and quickly break a crystal.");
		d.put("fastuse", "Removes the right-click use delay.");
		d.put("autoxp", "Throws XP bottles until your Mending armour is full.");
		d.put("autosign", "Writes your configured text onto every sign you place.");
		d.put("rtpfinder", "Spams an rtp command until it lands you near set coords.");
		d.put("hitboxes", "Shows player hitboxes through walls in your chosen color.");
		d.put("subtiers_autobed", "Detonates a bed shortly after your real placement in explosive dimensions.");
		d.put("subtiers_autocreeper", "Lines up and launches your placed creeper toward a nearby player with a KB II+ sword.");
		d.put("subtiers_autominecart", "Chains your rail placement into a TNT minecart and a fast aimed bow shot.");
		d.put("slow", "Slows your swing animation, full-speed packets.");
		d.put("mobesp", "Outlines living entities through walls.");
		d.put("advesp", "Finds dug shafts, tunnels, staircases and rooms.");
		d.put("storageesp", "Outlines every container and redstone build through walls.");
		d.put("suschunks", "Flags chunks holding base evidence you cannot see from above.");
		d.put("heatmap", "Marks where players were recently seen.");
		d.put("baseheat", "Colors chunks by player activity.");
		d.put("chunkfinder", "Colors chunks by how much has been happening in them.");
		d.put("stash", "Pings storage stashes the scan finds.");
		d.put("amethyst", "Highlights amethyst geodes through the ground.");
		d.put("portals", "Maps nether portals and their links.");
		d.put("tunnel", "Auto-mines a straight 1x2 tunnel.");
		d.put("freecam", "Flies the camera while you stay put.");
		d.put("novahome", "Custom NovaClient main menu. Off = classic Minecraft menu.");
		d.put("breakon", "Auto-swaps tool and mines what you see.");
		d.put("autoclicker", "Clicks autonomously at a naturally varied CPS; no mouse hold required.");
		d.put("fastbreak", "A modest block-break speed boost.");
		d.put("autotool", "Swaps to the best tool or weapon, with optional delay and restoration.");
		d.put("fastplace", "Lowers vanilla's right-click delay for all items, blocks, or projectiles.");
		d.put("autoarmor", "Equips the best armor through a paced, shared inventory click queue.");
		d.put("cheststeal", "Takes eligible chest items with filtering, scoring, shuffle, and click delay.");
		d.put("refill", "Keybind action: refills hotbar slots with healing pots or soup.");
		d.put("autohotbar", "Keeps weapons, blocks, healing, and pearls in configured hotbar slots.");
		d.put("invcleaner", "Keybind action: removes obvious junk while preserving configured item families.");
		d.put("bedbreaker", "Breaks a bed when your live crosshair reaches it.");
		d.put("autosprint", "Keeps you sprinting without holding the key.");
		d.put("autowalk", "Walks forward without holding the key.");
		d.put("scaffold", "God/Telly: hold Back, manually place the activation blocks, then keep Back held; Telly also uses right click.");
		d.put("clutch", "Places a block to catch your fall.");
		d.put("antifireball", "Deflects incoming fireballs straight back.");
		d.put("heightclutch", "Water or ladder save when falling.");
		d.put("pingspoof", "Reports whatever ping you choose.");
		d.put("flight", "BLATANT FLAG · Forces non-vanilla flight velocity.");
		d.put("spam", "Repeats a chat message on a timer.");
		d.put("waterwalk", "BLATANT FLAG · Walk on water; sneak to dive.");
		d.put("boatfly", "BLATANT FLAG · Fly a boat, even over land.");
		d.put("teleporter", "BLATANT FLAG · Client-position teleport.");
		d.put("fullbright", "Brightens dark areas entirely on your client.");
		d.put("pingequalizer", "Matches your ping to your opponent's.");
		d.put("nickname", "Changes your name everywhere on your client.");
		d.put("nickother", "Rewrites other players' names on your client.");
		d.put("remember", "Captures multiple builds as see-through ghosts; Delete removes the one you see.");
		d.put("schematicbuild", "Builds Remember or loaded Litematica schematics layer by layer, "
				+ "deepest interior cell first so a thick or wide layer never seals itself in. "
				+ "Auto Move walks and flies the build itself and sweeps every layer again until "
				+ "a whole sweep places nothing; off, it places whatever is under your crosshair.");
		d.put(MODE_SWORD, "Full sword kit at one strength — aim, strafe, sprint and trigger together.");
		d.put(MODE_AXE, "Full axe kit at one strength — shield stun, sword handoff and projectile aim.");
		d.put(MODE_MACE, "Full mace kit at one strength — smash aim, Breach swaps and stun slams.");
		d.put("swordai", "Built by Skorchekd — experimental local model for sword-combat movement.");
		return d;
	}

	public static List<Category> build(ProFPSConfig cfg) {
		// Every module is defined once here; the categories below just group them by id, so
		// re-categorising is a one-line change to an id list rather than moving code around.
		Map<String, Module> m = new java.util.LinkedHashMap<>();

		// ── Combat modes ─────────────────────────────────────────────────────────────
		// Modes are ordinary catalogue entries so they toggle, keybind and appear on the HUD like
		// anything else — but they are exclusive with each other and, while one is on, it takes over
		// the standalone modules in MANAGED (the UI locks those and shows them running in mode
		// colours; the HUD hides them so only "Sword Mode" is listed).
		m.put(MODE_SWORD, new Module(MODE_SWORD, "Sword Mode", Items.NETHERITE_SWORD,
				() -> cfg.combatMode == 1, v -> cfg.combatMode = v ? 1 : 0,
				new TierSetting("Tier", "sword", () -> cfg.swordModeTier, v -> cfg.swordModeTier = v),
				new BoolSetting("Aim Assist", () -> cfg.swordModeAim, v -> cfg.swordModeAim = v, () -> !cfg.swordModeAiBot),
				new BoolSetting("Strafe Assist", () -> cfg.swordModeStrafe, v -> cfg.swordModeStrafe = v, () -> !cfg.swordModeAiBot),
				new BoolSetting("Auto Sprint", () -> cfg.swordModeAutoSprint, v -> cfg.swordModeAutoSprint = v, () -> !cfg.swordModeAiBot),
				new BoolSetting("Triggerbot", () -> cfg.swordModeTrigger, v -> cfg.swordModeTrigger = v),
				new BoolSetting("AI Bot", () -> cfg.swordModeAiBot, v -> cfg.swordModeAiBot = v),
				new BoolSetting("AI Aim", () -> cfg.swordModeAiAim, v -> cfg.swordModeAiAim = v, () -> cfg.swordModeAiBot),
				new BoolSetting("AI Crit Jumps", () -> cfg.swordModeAiJump, v -> cfg.swordModeAiJump = v, () -> cfg.swordModeAiBot))
				.mode(1));
		m.put(MODE_AXE, new Module(MODE_AXE, "Axe Mode", Items.NETHERITE_AXE,
				() -> cfg.combatMode == 2, v -> cfg.combatMode = v ? 2 : 0,
				new TierSetting("Tier", "axe", () -> cfg.axeModeTier, v -> cfg.axeModeTier = v),
				new BoolSetting("Aim Assist", () -> cfg.axeModeAim, v -> cfg.axeModeAim = v),
				new BoolSetting("Axe Stun", () -> cfg.axeModeStun, v -> cfg.axeModeStun = v),
				new BoolSetting("Triggerbot", () -> cfg.axeModeTrigger, v -> cfg.axeModeTrigger = v),
				new BoolSetting("Sword Follow-up", () -> cfg.axeModeSwordFollowup, v -> cfg.axeModeSwordFollowup = v),
				new BoolSetting("Trigger Follow-up", () -> cfg.axeModeTriggerFollowup, v -> cfg.axeModeTriggerFollowup = v, () -> cfg.axeModeSwordFollowup),
				new BoolSetting("Projectile Aim", () -> cfg.axeModeProjectileAim, v -> cfg.axeModeProjectileAim = v),
				new BoolSetting("Bow Aim", () -> cfg.axeModeBowAim, v -> cfg.axeModeBowAim = v, () -> cfg.axeModeProjectileAim),
				new BoolSetting("Crossbow Aim", () -> cfg.axeModeCrossbowAim, v -> cfg.axeModeCrossbowAim = v, () -> cfg.axeModeProjectileAim))
				.mode(2));
		m.put(MODE_MACE, new Module(MODE_MACE, "Mace Mode", Items.MACE,
				() -> cfg.combatMode == 3, v -> cfg.combatMode = v ? 3 : 0,
				new TierSetting("Tier", "mace", () -> cfg.maceModeTier, v -> cfg.maceModeTier = v),
				new BoolSetting("Auto Mace", () -> cfg.maceModeAutoMace, v -> cfg.maceModeAutoMace = v),
				new BoolSetting("Auto Switch", () -> cfg.maceModeAutoSwitch, v -> cfg.maceModeAutoSwitch = v, () -> cfg.maceModeAutoMace),
				new BoolSetting("Mace Aim", () -> cfg.maceModeAim, v -> cfg.maceModeAim = v, () -> cfg.maceModeAutoMace),
				new BoolSetting("Auto Breach Swap", () -> cfg.maceModeBreachSwap, v -> cfg.maceModeBreachSwap = v),
				new BoolSetting("Stun Slam", () -> cfg.maceModeStunSlam, v -> cfg.maceModeStunSlam = v, () -> cfg.maceModeAutoMace))
				.mode(3));

		// ── Combat (aim / trigger / movement assists / crystal) ──────────────────────
		m.put("hit", new Module("hit", "Triggerbot", Items.NETHERITE_SWORD,
				() -> cfg.hitImprovements, v -> cfg.hitImprovements = v,
				new BoolSetting("Patient", () -> cfg.hitPatient, v -> cfg.hitPatient = v),
				new BoolSetting("Crit Timing", () -> cfg.hitCritTiming, v -> cfg.hitCritTiming = v),
				new BoolSetting("Crit Sprint Release", () -> cfg.hitCritSprintRelease,
						v -> cfg.hitCritSprintRelease = v),
				new BoolSetting("Sneak Disable", () -> cfg.hitDisableWhileSneaking, v -> cfg.hitDisableWhileSneaking = v),
				new BoolSetting("Sprint Aware", () -> cfg.hitSprintAwareCooldown, v -> cfg.hitSprintAwareCooldown = v),
				new IntSetting("Reaction Min", " ms", 0, 200, 1, () -> cfg.hitReactionMinMs, v -> {
					cfg.hitReactionMinMs = v;
					if (cfg.hitReactionMs < v) cfg.hitReactionMs = v;
				}),
				new IntSetting("Reaction Max", " ms", 5, 300, 5, () -> cfg.hitReactionMs, v -> {
					cfg.hitReactionMs = v;
					if (cfg.hitReactionMinMs > v) cfg.hitReactionMinMs = v;
				}),
				new IntSetting("Follow-up", " ms", 20, 200, 5, () -> cfg.hitFollowupMs, v -> cfg.hitFollowupMs = v),
				new IntSetting("Cooldown", "%", 60, 100, 1, () -> cfg.hitCooldownPct, v -> cfg.hitCooldownPct = v),
				new IntSetting("Axe Post Delay", " ms", 0, 300, 5, () -> cfg.hitAxePostDelayMs, v -> cfg.hitAxePostDelayMs = v),
				new IntSetting("Skip Rate", "%", 0, 15, 1, () -> cfg.hitSkipChancePct, v -> cfg.hitSkipChancePct = v)));
		m.put("aim", new Module("aim", "Aim Assist", Items.BOW,
				() -> cfg.aimImprovements, v -> cfg.aimImprovements = v,
				new BoolSetting("Frame Retarget", () -> cfg.aimRetargeting, v -> cfg.aimRetargeting = v),
				new IntSetting("Strength", "%", 15, 90, 1, () -> cfg.aimAssistStrength, v -> cfg.aimAssistStrength = v),
				new IntSetting("FOV", "°", 10, 90, 1, () -> cfg.aimFovDeg, v -> cfg.aimFovDeg = v),
				new IntSetting("Reaction", " ms", 0, 200, 5, () -> cfg.aimReactionMs, v -> cfg.aimReactionMs = v),
				new IntSetting("Duration", " ms", 250, 2200, 10, () -> cfg.aimAssistDurationMs, v -> cfg.aimAssistDurationMs = v)));
		m.put("strafe", new Module("strafe", "Strafe Assist", Items.FEATHER,
				() -> cfg.strafeImprovements, v -> cfg.strafeImprovements = v,
				new BoolSetting("Random Angle", () -> cfg.strafeRandomAngle, v -> cfg.strafeRandomAngle = v),
				new BoolSetting("Backward", () -> cfg.strafeBackstep, v -> cfg.strafeBackstep = v),
				new IntSetting("Strength", "%", 15, 90, 1, () -> cfg.strafeStrength, v -> cfg.strafeStrength = v),
				new IntSetting("Reach", " ms", 180, 720, 10, () -> cfg.strafeReachMs, v -> cfg.strafeReachMs = v),
				new IntSetting("Interval", " ms", 150, 800, 10, () -> cfg.strafeIntervalMs, v -> cfg.strafeIntervalMs = v),
				new IntSetting("Skip Rate", "%", 0, 30, 1, () -> cfg.strafeSkipPct, v -> cfg.strafeSkipPct = v)));
		m.put("autoaim", new Module("autoaim", "Auto Aim", Items.CROSSBOW,
				() -> cfg.autoAim, v -> cfg.autoAim = v,
				new IntSetting("Strength", "%", 10, 90, 1, () -> cfg.autoAimStrength, v -> cfg.autoAimStrength = v),
				new IntSetting("FOV", "°", 20, 120, 5, () -> cfg.autoAimFov, v -> cfg.autoAimFov = v)));
		m.put("reach", new Module("reach", "Reach", Items.IRON_SWORD,
				() -> cfg.reach, v -> cfg.reach = v,
				new IntSetting("Distance", " blk", 300, 600, 5, 100, () -> cfg.reachCm, v -> cfg.reachCm = v)));
		m.put("expandedhitbox", new Module("expandedhitbox", "Expanded Hitbox", Items.CREEPER_HEAD,
				() -> cfg.expandedHitbox, v -> cfg.expandedHitbox = v,
				new IntSetting("Expansion", " blk", 5, 150, 1, 100,
						() -> cfg.expandedHitboxAmountCm, v -> cfg.expandedHitboxAmountCm = v),
				new IntSetting("Turn Speed", "%", 10, 100, 1,
						() -> cfg.expandedHitboxTurnSpeed, v -> cfg.expandedHitboxTurnSpeed = v),
				new IntSetting("Reaction", " ms", 0, 180, 5,
						() -> cfg.expandedHitboxReactionMs, v -> cfg.expandedHitboxReactionMs = v)));
		m.put("jumpreset", new Module("jumpreset", "Auto Jump Reset", Items.RABBIT_FOOT,
				() -> cfg.jumpResetAssist, v -> cfg.jumpResetAssist = v,
				new IntSetting("Reaction", " ms", 0, 200, 5, () -> cfg.jumpResetReactionMs, v -> cfg.jumpResetReactionMs = v),
				new IntSetting("Skip Rate", "%", 0, 30, 1, () -> cfg.jumpResetSkipPct, v -> cfg.jumpResetSkipPct = v)));
		m.put("autopot", new Module("autopot", "Auto Pot", healingSplashPotion(),
				() -> cfg.autoPot, v -> cfg.autoPot = v,
				new ChoiceSetting("Type", new String[] {"Pots", "Soup", "Both"},
						() -> cfg.autoPotType, v -> cfg.autoPotType = v),
				new ChoiceSetting("Mode", new String[] {"Dynamic", "Single"},
						() -> cfg.autoPotMode, v -> cfg.autoPotMode = v),
				new IntSetting("Health", " HP", 1, 20, 1, () -> cfg.autoPotHealth, v -> cfg.autoPotHealth = v),
				new BoolSetting("Random", () -> cfg.autoPotRandom, v -> cfg.autoPotRandom = v),
				new BoolSetting("Flick To Player", () -> cfg.autoPotFlickToPlayer, v -> cfg.autoPotFlickToPlayer = v)));
		m.put("velocity", new Module("velocity", "Velocity", Items.PISTON,
				() -> cfg.velocity, v -> cfg.velocity = v,
				new IntSetting("Chance", "%", 0, 100, 1, () -> cfg.velocityChance, v -> cfg.velocityChance = v),
				new IntSetting("Horizontal", "%", 0, 100, 1, () -> cfg.velocityHorizontal, v -> cfg.velocityHorizontal = v),
				new IntSetting("Vertical", "%", 0, 100, 1, () -> cfg.velocityVertical, v -> cfg.velocityVertical = v),
				new IntSetting("Ticks", "", 0, 10, 1, () -> cfg.velocityTicks, v -> cfg.velocityTicks = v),
				new BoolSetting("Kite mode", () -> cfg.velocityKiteMode, v -> cfg.velocityKiteMode = v),
				new IntSetting("Kite horizontal", "%", 100, 300, 5,
						() -> cfg.velocityKiteHorizontal, v -> cfg.velocityKiteHorizontal = v)
						.when(() -> cfg.velocityKiteMode),
				new IntSetting("Kite vertical", "%", 100, 300, 5,
						() -> cfg.velocityKiteVertical, v -> cfg.velocityKiteVertical = v)
						.when(() -> cfg.velocityKiteMode),
				new BoolSetting("Always kite", () -> cfg.velocityAlwaysKite, v -> cfg.velocityAlwaysKite = v)
						.when(() -> cfg.velocityKiteMode),
				new BoolSetting("Only when targeting", () -> cfg.velocityOnlyWhenTargeting,
						v -> cfg.velocityOnlyWhenTargeting = v),
				new BoolSetting("Water check", () -> cfg.velocityWaterCheck, v -> cfg.velocityWaterCheck = v)));
		m.put("axestun", new Module("axestun", "Axe Stun", Items.DIAMOND_AXE,
				() -> cfg.axeStun, v -> cfg.axeStun = v,
				new BoolSetting("Restore Previous Slot",
						() -> cfg.axeStunRestorePrevious, v -> cfg.axeStunRestorePrevious = v),
				new IntSetting("Reaction", " ms", 0, 300, 5, () -> cfg.axeStunReactionMs, v -> cfg.axeStunReactionMs = v),
				new IntSetting("Switch Back", " ms", 30, 250, 5, () -> cfg.axeStunSwitchBackMs, v -> cfg.axeStunSwitchBackMs = v)));
		m.put("kbdisplace", new Module("kbdisplace", "Knockback Displace", Items.WIND_CHARGE,
				() -> false, v -> { if (v) cfg.kbDisplaceRequested = true; },
				new BoolSetting("Sprint Reset", () -> cfg.kbDisplaceReset, v -> cfg.kbDisplaceReset = v),
				new IntSetting("Aim Speed", "%", 20, 95, 1, () -> cfg.kbDisplaceAimSpeed, v -> cfg.kbDisplaceAimSpeed = v)).momentary());
		m.put("swordai", new Module("swordai", "Sword AI", Items.COPPER_SWORD,
				() -> cfg.swordAiEnabled, v -> cfg.swordAiEnabled = v,
				new BoolSetting("Aim (Pitch/Yaw)", () -> cfg.swordAiAim, v -> cfg.swordAiAim = v),
				new BoolSetting("Jump", () -> cfg.swordAiJump, v -> cfg.swordAiJump = v)));
		m.put("anchor", new Module("anchor", "Anchor Macro", Items.RESPAWN_ANCHOR,
				() -> cfg.anchorMacro, v -> cfg.anchorMacro = v,
				new ChoiceSetting("Mode", new String[] {"On bind", "On place"}, () -> cfg.anchorMode, v -> cfg.anchorMode = v),
				new BoolSetting("Detonate", () -> cfg.anchorDetonate, v -> cfg.anchorDetonate = v),
				new BoolSetting("Air place", () -> cfg.anchorAirPlace, v -> cfg.anchorAirPlace = v),
				new BoolSetting("Safe anchor", () -> cfg.anchorSafe, v -> cfg.anchorSafe = v),
				new BoolSetting("Use item whitelist", () -> cfg.anchorExplosionItemWhitelist,
						v -> cfg.anchorExplosionItemWhitelist = v),
				new StringSetting("Use / finish item", "minecraft:totem_of_undying",
						() -> String.join(", ", cfg.anchorExplosionItems), value -> {
							cfg.anchorExplosionItems.clear();
							for (String id : value.split(",")) {
								String clean = id.trim().toLowerCase(java.util.Locale.ROOT);
								if (!clean.isEmpty() && !cfg.anchorExplosionItems.contains(clean)) cfg.anchorExplosionItems.add(clean);
							}
						}).when(() -> cfg.anchorExplosionItemWhitelist),
				new BoolSetting("Aim assist", () -> cfg.anchorAimAssist, v -> cfg.anchorAimAssist = v),
				new BoolSetting("Silent aim", () -> cfg.anchorSilentAim, v -> cfg.anchorSilentAim = v)
						.when(() -> cfg.anchorAimAssist),
				new IntSetting("Aim speed", "", 10, 150, 1, 10,
						() -> cfg.anchorAimSpeedTenths, v -> cfg.anchorAimSpeedTenths = v)
						.when(() -> cfg.anchorAimAssist),
				new IntSetting("Delay min", " ms", 0, 500, 5, () -> cfg.anchorDelayMinMs, v -> cfg.anchorDelayMinMs = v),
				new IntSetting("Delay max", " ms", 0, 500, 5, () -> cfg.anchorDelayMaxMs, v -> cfg.anchorDelayMaxMs = v),
				new BoolSetting("Stop When No Totem", () -> cfg.anchorStopWhenNoTotem,
						v -> cfg.anchorStopWhenNoTotem = v).when(() -> cfg.anchorDetonate)));
		m.put("totem", new Module("totem", "Auto Totem", Items.TOTEM_OF_UNDYING,
				() -> cfg.totemTweaks, v -> cfg.totemTweaks = v,
				new BoolSetting("Open Inventory", () -> cfg.totemOpenInventory, v -> cfg.totemOpenInventory = v)));
		m.put("autocrystal", new Module("autocrystal", "Auto Crystal", Items.END_CRYSTAL,
				() -> cfg.autoCrystal, v -> cfg.autoCrystal = v));
		m.put("fastuse", new Module("fastuse", "Fast Use", Items.SUGAR,
				() -> cfg.fastUse, v -> cfg.fastUse = v,
				new IntSetting("Speed", "", 1, 10, 1, () -> cfg.fastUseLevel, v -> cfg.fastUseLevel = v)));
		m.put("autoxp", new Module("autoxp", "Auto XP", Items.EXPERIENCE_BOTTLE,
				() -> cfg.autoXpEnabled, v -> cfg.autoXpEnabled = v,
				new IntSetting("Delay", " ms", 0, 1000, 10, () -> cfg.autoXpDelayMs, v -> cfg.autoXpDelayMs = v)));

		// ── Mace & Spear ─────────────────────────────────────────────────────────────
		m.put("automace", new Module("automace", "AutoMace", Items.MACE,
				() -> cfg.autoMace, v -> cfg.autoMace = v,
				new BoolSetting("Auto Switch", () -> cfg.autoMaceAutoSwitch, v -> cfg.autoMaceAutoSwitch = v),
				new IntSetting("FOV", "°", 20, 90, 5, () -> cfg.autoMaceFov, v -> cfg.autoMaceFov = v),
				new IntSetting("Range", "m", 3, 7, 1, () -> cfg.autoMaceRange, v -> cfg.autoMaceRange = v),
				new IntSetting("Turn Speed", "%", 20, 90, 1, () -> cfg.autoMaceTurnSpeed, v -> cfg.autoMaceTurnSpeed = v),
				new IntSetting("Smash Speed", "%", 30, 95, 1, () -> cfg.autoMaceSmashSpeed, v -> cfg.autoMaceSmashSpeed = v),
				new IntSetting("Settle", " ms", 0, 150, 5, () -> cfg.autoMaceSettleMs, v -> cfg.autoMaceSettleMs = v),
				new BoolSetting("Silent Aim", () -> cfg.maceSilentAim, v -> cfg.maceSilentAim = v),
				new BoolSetting("Stun Slam (Axe)", () -> cfg.autoMaceShieldBreak, v -> cfg.autoMaceShieldBreak = v),
				new IntSetting("Stun Gap", " ms", 0, 200, 5, () -> cfg.autoMaceShieldBreakMs, v -> cfg.autoMaceShieldBreakMs = v)));
		m.put("autobreachswap", new Module("autobreachswap", "Auto Breach Swap", Items.MACE,
				() -> cfg.autoBreachSwap, v -> cfg.autoBreachSwap = v,
				new IntSetting("Mace Charge", "%", 50, 100, 1, () -> cfg.autoBreachSwapCharge, v -> cfg.autoBreachSwapCharge = v)));
		m.put("autolunge", new Module("autolunge", "Auto Lunge Swap", Items.NETHERITE_SPEAR,
				() -> false, v -> { if (v) cfg.autoLungeRequested = true; },
				new BoolSetting("Jump Launch", () -> cfg.lungeSwapJump, v -> cfg.lungeSwapJump = v),
				new BoolSetting("Humanize", () -> cfg.lungeSwapHumanize, v -> cfg.lungeSwapHumanize = v),
				new BoolSetting("Spam Scaling", () -> cfg.lungeSpamScaling, v -> cfg.lungeSpamScaling = v),
				new BoolSetting("Spear → Mace", () -> cfg.lungeSpearMace, v -> cfg.lungeSpearMace = v)).momentary());
		m.put("autospear", new Module("autospear", "Auto Spear", Items.DIAMOND_SPEAR,
				() -> cfg.autoSpearEnabled, v -> cfg.autoSpearEnabled = v,
				new IntSetting("Range", "m", 8, 96, 2, () -> cfg.autoSpearRange, v -> cfg.autoSpearRange = v),
				new IntSetting("FOV", "°", 20, 140, 5, () -> cfg.autoSpearFov, v -> cfg.autoSpearFov = v),
				new IntSetting("Turn Speed", "%", 20, 90, 1, () -> cfg.autoSpearTurnSpeed, v -> cfg.autoSpearTurnSpeed = v),
				new BoolSetting("Auto Switch", () -> cfg.autoSpearAutoSwitch, v -> cfg.autoSpearAutoSwitch = v),
				new BoolSetting("Silent Aim", () -> cfg.autoSpearSilentAim, v -> cfg.autoSpearSilentAim = v)));

		// ── SubTiers ───────────────────────────────────────────────────────────────
		m.put("subtiers_autobed", new Module("subtiers_autobed", "Auto Bed", Items.RED_BED,
				() -> cfg.subTiersAutoBed, v -> cfg.subTiersAutoBed = v));
		m.put("subtiers_autocreeper", new Module("subtiers_autocreeper", "Auto Creeper", Items.CREEPER_SPAWN_EGG,
				() -> cfg.subTiersAutoCreeper, v -> cfg.subTiersAutoCreeper = v));
		m.put("subtiers_autominecart", new Module("subtiers_autominecart", "Auto Minecart", Items.TNT_MINECART,
				() -> cfg.subTiersAutoMinecart, v -> cfg.subTiersAutoMinecart = v,
				new IntSetting("Bow Fire Speed", "", 1, 10, 1,
						() -> cfg.subTiersMinecartBowSpeed, v -> cfg.subTiersMinecartBowSpeed = v)));

		// ── DonutSMP (finding + ESP + freecam) ───────────────────────────────────────
		m.put("mobesp", new Module("mobesp", "Mob ESP", Items.ZOMBIE_HEAD,
				() -> cfg.donutBasicEsp, v -> cfg.donutBasicEsp = v,
				new IntSetting("Range", "m", 32, 1024, 32, () -> cfg.donutBasicEspRange, v -> cfg.donutBasicEspRange = v),
				new BoolSetting("Players", () -> cfg.donutBasicShowPlayers, v -> cfg.donutBasicShowPlayers = v),
				new BoolSetting("Monsters", () -> cfg.donutBasicShowMonsters, v -> cfg.donutBasicShowMonsters = v),
				new BoolSetting("Passive", () -> cfg.donutBasicShowPassive, v -> cfg.donutBasicShowPassive = v),
				new BoolSetting("Aquatic", () -> cfg.donutBasicShowAquatic, v -> cfg.donutBasicShowAquatic = v)));
		m.put("advesp", new Module("advesp", "Hole/Tunnel/Stairs ESP", Items.SPYGLASS,
				() -> cfg.donutAdvancedEsp, v -> cfg.donutAdvancedEsp = v,
				new IntSetting("Range", "m", 48, 1024, 32, () -> cfg.donutAdvancedEspRange, v -> cfg.donutAdvancedEspRange = v),
				new BoolSetting("Shafts", () -> cfg.donutAdvancedShowShafts, v -> cfg.donutAdvancedShowShafts = v),
				new BoolSetting("Tunnels", () -> cfg.donutAdvancedShowTunnels, v -> cfg.donutAdvancedShowTunnels = v),
				new BoolSetting("Stairs", () -> cfg.donutAdvancedShowStairs, v -> cfg.donutAdvancedShowStairs = v),
				new BoolSetting("Pockets", () -> cfg.donutAdvancedShowPockets, v -> cfg.donutAdvancedShowPockets = v),
				new ButtonSetting("Reload", "Rescan area", () -> cfg.donutAdvancedEspReloadRequested = true)));
		m.put("storageesp", new Module("storageesp", "Storage ESP", Items.CHEST,
				() -> cfg.donutStorageEsp, v -> {
					cfg.donutStorageEsp = v;
					if (!v) cfg.donutStashPinger = false;
				},
				new IntSetting("Range", "m", 32, 512, 16, () -> cfg.donutStorageEspRange, v -> cfg.donutStorageEspRange = v),
				new IntSetting("Fill", "%", 5, 60, 1, () -> cfg.donutStorageEspOpacity, v -> cfg.donutStorageEspOpacity = v),
				new BoolSetting("Chests", () -> cfg.donutStorageShowChests, v -> cfg.donutStorageShowChests = v),
				new BoolSetting("Shulkers & Barrels", () -> cfg.donutStorageShowShulkers, v -> cfg.donutStorageShowShulkers = v),
				new BoolSetting("Redstone", () -> cfg.donutStorageShowRedstone, v -> cfg.donutStorageShowRedstone = v),
				new BoolSetting("Furnaces", () -> cfg.donutStorageShowFurnaces, v -> cfg.donutStorageShowFurnaces = v)));
		m.put("suschunks", new Module("suschunks", "Suspicious Chunks", Items.SCULK_SENSOR,
				() -> cfg.donutSuspiciousChunks, v -> cfg.donutSuspiciousChunks = v,
				new IntSetting("Range", "m", 48, 1024, 32, () -> cfg.donutSuspiciousChunksRange, v -> cfg.donutSuspiciousChunksRange = v),
				new IntSetting("Ceiling", "y", -64, 64, 4, () -> cfg.donutSuspiciousChunksCeiling, v -> cfg.donutSuspiciousChunksCeiling = v),
				new BoolSetting("Labels", () -> cfg.donutSuspiciousChunksLabels, v -> cfg.donutSuspiciousChunksLabels = v)));
		m.put("heatmap", new Module("heatmap", "Player Heatmap", Items.PLAYER_HEAD,
				() -> cfg.donutPlayerSightings, v -> cfg.donutPlayerSightings = v));
		m.put("baseheat", new Module("baseheat", "Base Heat", Items.CAMPFIRE,
				() -> cfg.donutChunkActivity, v -> cfg.donutChunkActivity = v,
				new IntSetting("Range", "m", 48, 1024, 32, () -> cfg.donutChunkActivityRange, v -> cfg.donutChunkActivityRange = v)));
		m.put("chunkfinder", new Module("chunkfinder", "Activity Chunks", Items.FILLED_MAP,
				() -> cfg.donutChunkFinder, v -> cfg.donutChunkFinder = v,
				new IntSetting("Range", "m", 48, 1024, 32, () -> cfg.donutChunkFinderRange, v -> cfg.donutChunkFinderRange = v),
				new BoolSetting("Tracers", () -> cfg.donutChunkFinderTracers, v -> cfg.donutChunkFinderTracers = v),
				new BoolSetting("Labels", () -> cfg.donutChunkFinderLabels, v -> cfg.donutChunkFinderLabels = v),
				new BoolSetting("Experimental", () -> cfg.donutChunkExperimental, v -> cfg.donutChunkExperimental = v)));
		m.put("stash", new Module("stash", "Stash Pinger", Items.ENDER_CHEST,
				() -> cfg.donutStashPinger && cfg.donutStorageEsp,
				v -> {
					cfg.donutStashPinger = v;
					if (v) cfg.donutStorageEsp = true;
				},
				new IntSetting("Range", "m", 48, 1024, 32, () -> cfg.donutStashPingerRange, v -> cfg.donutStashPingerRange = v),
				new BoolSetting("Bases", () -> cfg.donutStashShowBases, v -> cfg.donutStashShowBases = v),
				new BoolSetting("Spawners", () -> cfg.donutStashShowSpawners, v -> cfg.donutStashShowSpawners = v)));
		m.put("amethyst", new Module("amethyst", "Amethyst Finder", Items.AMETHYST_SHARD,
				() -> cfg.donutAmethystDetector, v -> cfg.donutAmethystDetector = v,
				new IntSetting("Range", "m", 48, 1024, 32, () -> cfg.donutAmethystDetectorRange, v -> cfg.donutAmethystDetectorRange = v)));
		m.put("portals", new Module("portals", "Nether Mapper", Items.CRYING_OBSIDIAN,
				() -> cfg.donutNetherPortalMapper, v -> cfg.donutNetherPortalMapper = v));
		m.put("freecam", new Module("freecam", "Freecam", Items.ENDER_PEARL,
				() -> cfg.donutFreecam, v -> cfg.donutFreecam = v,
				new IntSetting("Speed", "/10", 1, 10, 1, () -> cfg.donutFreecamSpeed, v -> cfg.donutFreecamSpeed = v)));
		m.put("tunnel", new Module("tunnel", "Tunnel", Items.IRON_PICKAXE,
				() -> cfg.donutTunnel, v -> cfg.donutTunnel = v,
				new IntSetting("Eat At", " hp", 4, 20, 1, () -> cfg.donutTunnelHpThreshold, v -> cfg.donutTunnelHpThreshold = v)));

		// ── Hypixel ──────────────────────────────────────────────────────────────────
		m.put("autoclicker", new Module("autoclicker", "Auto Clicker", Items.GOLDEN_HOE,
				() -> cfg.instantAutoClicker, v -> cfg.instantAutoClicker = v,
				new BoolSetting("Hold to click", () -> cfg.instantClickHoldToClick, v -> cfg.instantClickHoldToClick = v),
				new BoolSetting("Trigger mode", () -> cfg.instantClickTargetOnly, v -> cfg.instantClickTargetOnly = v),
				new IntSetting("CPS min", "", 1, 20, 1, () -> cfg.instantClickMinCps,
						v -> cfg.instantClickMinCps = Math.min(v, cfg.instantClickCps)),
				new IntSetting("CPS max", "", 1, 20, 1, () -> cfg.instantClickCps,
						v -> { cfg.instantClickCps = v; cfg.instantClickMinCps = Math.min(cfg.instantClickMinCps, v); }),
				new ChoiceSetting("Randomization", new String[] {"Normal", "Extra", "Extra+"},
						() -> cfg.instantClickRandomization, v -> cfg.instantClickRandomization = v),
				new BoolSetting("Jitter", () -> cfg.instantClickJitter, v -> cfg.instantClickJitter = v),
				new BoolSetting("Limit items", () -> cfg.instantClickLimitItems, v -> cfg.instantClickLimitItems = v),
				new StringSetting("Item whitelist", "minecraft:diamond_sword",
						() -> String.join(", ", cfg.instantClickAllowedItems), value -> replaceCsv(cfg.instantClickAllowedItems, value))
						.when(() -> cfg.instantClickLimitItems),
				new BoolSetting("Break blocks", () -> cfg.instantClickBreakBlocks, v -> cfg.instantClickBreakBlocks = v),
				new IntSetting("Break blocks delay", " ms", 0, 2000, 10,
						() -> cfg.instantClickBreakBlocksDelayMs, v -> cfg.instantClickBreakBlocksDelayMs = v)
						.when(() -> cfg.instantClickBreakBlocks),
				new BoolSetting("Right Click", () -> cfg.instantClickRight, v -> cfg.instantClickRight = v),
				new BoolSetting("Target Only", () -> cfg.instantClickTargetOnly, v -> cfg.instantClickTargetOnly = v)
						.when(() -> cfg.instantClickRight)));
		m.put("antifireball", new Module("antifireball", "Anti Fireball", Items.FIRE_CHARGE,
				() -> cfg.antiFireballAssist, v -> cfg.antiFireballAssist = v));
		m.put("heightclutch", new Module("heightclutch", "Height Clutch", Items.LADDER,
				() -> cfg.heightClutchAssist, v -> cfg.heightClutchAssist = v));
		m.put("clutch", new Module("clutch", "Clutch", Items.RED_WOOL,
				() -> cfg.clutchAssist, v -> cfg.clutchAssist = v));
		m.put("scaffold", new Module("scaffold", "Scaffold", Items.SCAFFOLDING,
				() -> cfg.scaffoldAssist, v -> cfg.scaffoldAssist = v,
				new ChoiceSetting("Mode", new String[] {"Legit", "GodBridge", "TellyBridge"},
						() -> cfg.scaffoldMode, v -> cfg.scaffoldMode = v),
				new BoolSetting("Block count", () -> cfg.scaffoldBlockCount, v -> cfg.scaffoldBlockCount = v),
				new BoolSetting("Pitch check", () -> cfg.scaffoldPitchCheck, v -> cfg.scaffoldPitchCheck = v),
				new IntSetting("Pitch", "°", 0, 90, 1, () -> cfg.scaffoldPitch, v -> cfg.scaffoldPitch = v)
						.when(() -> cfg.scaffoldPitchCheck),
				new BlockPickerSetting("Blacklist", () -> cfg.scaffoldBlacklist, v -> cfg.scaffoldBlacklist = v,
						cfg.scaffoldBlacklistBlocks),
				new BlockPickerSetting("Whitelist", () -> cfg.scaffoldWhitelist, v -> cfg.scaffoldWhitelist = v,
						cfg.scaffoldWhitelistBlocks),
				new IntSetting("Sneak delay min", " ms", 0, 500, 5,
						() -> cfg.scaffoldSneakDelayMinMs, v -> cfg.scaffoldSneakDelayMinMs = v)
						.when(() -> cfg.scaffoldMode == 0),
				new IntSetting("Sneak delay max", " ms", 0, 500, 5,
						() -> cfg.scaffoldSneakDelayMaxMs, v -> cfg.scaffoldSneakDelayMaxMs = v)
						.when(() -> cfg.scaffoldMode == 0),
				new BoolSetting("Require sneak", () -> cfg.scaffoldRequireSneak, v -> cfg.scaffoldRequireSneak = v)
						.when(() -> cfg.scaffoldMode == 0),
				new IntSetting("Activation blocks", "", 1, 4, 1,
						() -> cfg.scaffoldGodActivationBlocks, v -> cfg.scaffoldGodActivationBlocks = v)
						.when(() -> cfg.scaffoldMode == 1),
				new BoolSetting("Require right click", () -> cfg.scaffoldTellyRequireRightClick,
						v -> cfg.scaffoldTellyRequireRightClick = v).when(() -> cfg.scaffoldMode == 2),
				new IntSetting("Activation Blocks", "", 1, 4, 1,
						() -> cfg.scaffoldTellyActivationBlocks, v -> cfg.scaffoldTellyActivationBlocks = v)
						.when(() -> cfg.scaffoldMode == 2),
				new IntSetting("Y increase", "", 0, 3, 1,
						() -> cfg.scaffoldTellyYIncrease, v -> cfg.scaffoldTellyYIncrease = v)
						.when(() -> cfg.scaffoldMode == 2)));

		// ── Instants ─────────────────────────────────────────────────────────────────
		m.put("breakon", new Module("breakon", "BreakOn", Items.IRON_AXE,
				() -> cfg.instantBreakOn, v -> cfg.instantBreakOn = v,
				new BoolSetting("Hand Use", () -> cfg.instantBreakOnHandUse, v -> cfg.instantBreakOnHandUse = v),
				new BlockPickerSetting("Certain Blocks", () -> cfg.instantBreakOnCertain,
						v -> cfg.instantBreakOnCertain = v, cfg.instantBreakOnBlocks)));
		m.put("fastbreak", new Module("fastbreak", "Fastbreak", Items.NETHERITE_PICKAXE,
				() -> cfg.instantFastBreak, v -> cfg.instantFastBreak = v,
				new IntSetting("Speed", "", 1, 10, 1, () -> cfg.instantFastBreakLevel, v -> cfg.instantFastBreakLevel = v)));
		m.put("autotool", new Module("autotool", "AutoTool", Items.IRON_PICKAXE,
				() -> cfg.instantAutoTool, v -> cfg.instantAutoTool = v,
				new IntSetting("Swap to delay", " ms", 0, 500, 50,
						() -> cfg.instantAutoToolSwapToDelayMs, v -> cfg.instantAutoToolSwapToDelayMs = v),
				new BoolSetting("Swap weapon", () -> cfg.instantAutoToolSwapWeapon,
						v -> cfg.instantAutoToolSwapWeapon = v),
				new BoolSetting("Instant swap", () -> cfg.instantAutoToolInstantWeapon,
						v -> cfg.instantAutoToolInstantWeapon = v).when(() -> cfg.instantAutoToolSwapWeapon),
				new BoolSetting("Swap back", () -> cfg.instantAutoToolSwapBack,
						v -> cfg.instantAutoToolSwapBack = v),
				new IntSetting("Swap back delay", " ms", 50, 1000, 50,
						() -> cfg.instantAutoToolSwapBackDelayMs, v -> cfg.instantAutoToolSwapBackDelayMs = v)
						.when(() -> cfg.instantAutoToolSwapBack),
				new BoolSetting("Require mouse down", () -> cfg.instantAutoToolRequireMouseDown,
						v -> cfg.instantAutoToolRequireMouseDown = v),
				new BoolSetting("Only while sneaking", () -> cfg.instantAutoToolOnlySneaking,
						v -> cfg.instantAutoToolOnlySneaking = v)));
		m.put("fastplace", new Module("fastplace", "Fast Place", Items.BRICKS,
				() -> cfg.instantFastPlace, v -> cfg.instantFastPlace = v,
				new ChoiceSetting("Held Item", new String[] {"All", "Blocks", "Projectiles"},
						() -> cfg.instantFastPlaceHeldItem, v -> cfg.instantFastPlaceHeldItem = v),
				new IntSetting("Delay", "", 0, 4, 1,
						() -> cfg.instantFastPlaceDelay, v -> cfg.instantFastPlaceDelay = v)));
		m.put("autosprint", new Module("autosprint", "AutoSprint", Items.SUGAR,
				() -> cfg.instantAutoSprint, v -> cfg.instantAutoSprint = v));
		m.put("autowalk", new Module("autowalk", "AutoWalk", Items.LEATHER_BOOTS,
				() -> cfg.instantAutoWalk, v -> cfg.instantAutoWalk = v));

		// ── Misc (classics + utilities) ──────────────────────────────────────────────
		m.put("flight", new Module("flight", "Flight", Items.ELYTRA,
				() -> cfg.flightEnabled, v -> cfg.flightEnabled = v,
				new IntSetting("Speed", "/10", 1, 10, 1, () -> cfg.flightSpeed, v -> cfg.flightSpeed = v)));
		m.put("spam", new Module("spam", "Spam", Items.WRITABLE_BOOK,
				() -> cfg.spamEnabled, v -> cfg.spamEnabled = v,
				new StringSetting("Message", "Type a message…", () -> cfg.spamMessage, v -> cfg.spamMessage = v),
				new IntSetting("Speed", "/10", 1, 10, 1, () -> cfg.spamSpeed, v -> cfg.spamSpeed = v)));
		m.put("waterwalk", new Module("waterwalk", "Water Walker", Items.WATER_BUCKET,
				() -> cfg.waterWalkEnabled, v -> cfg.waterWalkEnabled = v));
		m.put("boatfly", new Module("boatfly", "Boat Fly", Items.OAK_BOAT,
				() -> cfg.boatFlyEnabled, v -> cfg.boatFlyEnabled = v,
				new IntSetting("Speed", "/10", 1, 10, 1, () -> cfg.boatFlySpeed, v -> cfg.boatFlySpeed = v)));
		m.put("teleporter", new Module("teleporter", "Teleporter", Items.ENDER_PEARL,
				() -> cfg.teleporterEnabled, v -> cfg.teleporterEnabled = v,
				new IntSetting("Range", "m", 8, 256, 4, () -> cfg.teleporterRange, v -> cfg.teleporterRange = v)));
		m.put("fullbright", new Module("fullbright", "Full Bright", Items.LIGHT,
				() -> cfg.fullBrightEnabled, v -> cfg.fullBrightEnabled = v,
				new IntSetting("Brightness", "/10", 1, 10, 1,
						() -> cfg.fullBrightLevel, v -> cfg.fullBrightLevel = v)));
		m.put("nickname", new Module("nickname", "Nickname", Items.NAME_TAG,
				() -> cfg.nicknameEnabled, v -> cfg.nicknameEnabled = v,
				new StringSetting("Username", "Your nickname…", () -> cfg.nicknameSelfName, v -> cfg.nicknameSelfName = v),
				new BoolSetting("Change Skin", () -> cfg.nicknameSelfSkin, v -> cfg.nicknameSelfSkin = v),
				new StringSetting("Skin From", "blank = nickname", () -> cfg.nicknameSelfSkinFrom, v -> cfg.nicknameSelfSkinFrom = v)));
		m.put("nickother", new Module("nickother", "Nick Other", Items.PLAYER_HEAD,
				() -> cfg.nickOtherEnabled, v -> cfg.nickOtherEnabled = v,
				new NickListSetting("Players", cfg.nickOtherEntries, cfg::save)));
		m.put("pingspoof", new Module("pingspoof", "Ping Spoofer", Items.COMPASS,
				() -> cfg.pingSpoofEnabled, v -> cfg.pingSpoofEnabled = v,
				new IntSetting("Ping", " ms", 20, 1000, 10, () -> cfg.pingSpoofMs, v -> cfg.pingSpoofMs = v)));
		m.put("pingequalizer", new Module("pingequalizer", "Ping Equalizer", Items.RECOVERY_COMPASS,
				() -> cfg.pingEqualizerEnabled, v -> cfg.pingEqualizerEnabled = v));
		m.put("remember", new Module("remember", "Remember", Items.YELLOW_WOOL,
				() -> cfg.rememberEnabled, v -> cfg.rememberEnabled = v));
		m.put("schematicbuild", new Module("schematicbuild", "Schematic Build", Items.FILLED_MAP,
				() -> cfg.schematicBuildEnabled, v -> cfg.schematicBuildEnabled = v,
				new BoolSetting("Auto Move", () -> cfg.schematicAutoMove,
						v -> cfg.schematicAutoMove = v),
				new BoolSetting("Temporary Blocks", () -> cfg.schematicTemporaryBlocks,
						v -> cfg.schematicTemporaryBlocks = v)));
		m.put("slow", new Module("slow", "Slow", Items.COBWEB,
				() -> cfg.slowAnimations, v -> cfg.slowAnimations = v,
				new IntSetting("Slowness", "x", 2, 8, 1, () -> cfg.slowAnimationStrength, v -> cfg.slowAnimationStrength = v)));
		m.put("hitboxes", new Module("hitboxes", "Hitboxes", Items.GLASS,
				() -> cfg.hitboxes, v -> cfg.hitboxes = v,
				new IntSetting("Red", "", 0, 255, 1, () -> cfg.hitboxRed, v -> cfg.hitboxRed = v),
				new IntSetting("Green", "", 0, 255, 1, () -> cfg.hitboxGreen, v -> cfg.hitboxGreen = v),
				new IntSetting("Blue", "", 0, 255, 1, () -> cfg.hitboxBlue, v -> cfg.hitboxBlue = v),
				new IntSetting("Outline Opacity", "%", 10, 100, 1, () -> cfg.hitboxOutlineOpacity, v -> cfg.hitboxOutlineOpacity = v),
				new IntSetting("Fill Opacity", "%", 0, 80, 1, () -> cfg.hitboxFillOpacity, v -> cfg.hitboxFillOpacity = v),
				new IntSetting("Line Width", "px", 1, 5, 1, () -> cfg.hitboxLineWidth, v -> cfg.hitboxLineWidth = v)));

		// ── Inventory automation ─────────────────────────────────────────────────
		m.put("autoarmor", new Module("autoarmor", "AutoArmor", Items.DIAMOND_CHESTPLATE,
				() -> cfg.inventoryAutoArmor, v -> cfg.inventoryAutoArmor = v,
				new BoolSetting("Open inventory", () -> cfg.inventoryAutoArmorOpen, v -> cfg.inventoryAutoArmorOpen = v),
				new BoolSetting("Inventory only", () -> cfg.inventoryAutoArmorOnly, v -> cfg.inventoryAutoArmorOnly = v),
				new BoolSetting("Check durability", () -> cfg.inventoryAutoArmorDurability, v -> cfg.inventoryAutoArmorDurability = v),
				new BoolSetting("Drop equipped", () -> cfg.inventoryAutoArmorDropEquipped, v -> cfg.inventoryAutoArmorDropEquipped = v),
				new BoolSetting("Combat check", () -> cfg.inventoryAutoArmorCombatCheck, v -> cfg.inventoryAutoArmorCombatCheck = v),
				new IntSetting("Delay min", " ms", 1, 200, 1,
						() -> cfg.inventoryAutoArmorDelayMinMs, v -> cfg.inventoryAutoArmorDelayMinMs = v),
				new IntSetting("Delay max", " ms", 1, 200, 1,
						() -> cfg.inventoryAutoArmorDelayMaxMs, v -> cfg.inventoryAutoArmorDelayMaxMs = v)));
		m.put("autosign", new Module("autosign", "AutoSign", Items.OAK_SIGN,
				() -> cfg.autoSignEnabled, v -> cfg.autoSignEnabled = v,
				new StringSetting("Line 1", "", () -> cfg.autoSignLine1, v -> cfg.autoSignLine1 = v),
				new StringSetting("Line 2", "", () -> cfg.autoSignLine2, v -> cfg.autoSignLine2 = v),
				new StringSetting("Line 3", "", () -> cfg.autoSignLine3, v -> cfg.autoSignLine3 = v),
				new StringSetting("Line 4", "", () -> cfg.autoSignLine4, v -> cfg.autoSignLine4 = v)));
		m.put("rtpfinder", new Module("rtpfinder", "RTPFinder", Items.ENDER_PEARL,
				() -> cfg.rtpFinderEnabled, v -> cfg.rtpFinderEnabled = v,
				new StringSetting("Target X", "0", () -> String.valueOf(cfg.rtpFinderTargetX),
						v -> cfg.rtpFinderTargetX = parseCoord(v, cfg.rtpFinderTargetX)),
				new StringSetting("Target Z", "0", () -> String.valueOf(cfg.rtpFinderTargetZ),
						v -> cfg.rtpFinderTargetZ = parseCoord(v, cfg.rtpFinderTargetZ)),
				new ChoiceSetting("Stop within", new String[] {"5k", "10k", "20k"},
						() -> cfg.rtpFinderRadius, v -> cfg.rtpFinderRadius = v),
				new StringSetting("Command", "/rtp", () -> cfg.rtpFinderCommand, v -> cfg.rtpFinderCommand = v),
				new IntSetting("Interval", " ms", 250, 10000, 250,
						() -> cfg.rtpFinderIntervalMs, v -> cfg.rtpFinderIntervalMs = v)));
		m.put("cheststeal", new Module("cheststeal", "ChestSteal", Items.CHEST,
				() -> cfg.inventoryChestSteal, v -> cfg.inventoryChestSteal = v,
				new BoolSetting("Check in menu", () -> cfg.inventoryChestStealCheckMenu, v -> cfg.inventoryChestStealCheckMenu = v),
				new BoolSetting("Best only", () -> cfg.inventoryChestStealBestOnly, v -> cfg.inventoryChestStealBestOnly = v),
				new BoolSetting("Keep open", () -> cfg.inventoryChestStealKeepOpen, v -> cfg.inventoryChestStealKeepOpen = v),
				new BoolSetting("Shuffle", () -> cfg.inventoryChestStealShuffle, v -> cfg.inventoryChestStealShuffle = v),
				new IntSetting("Click delay min", " ms", 50, 300, 5,
						() -> cfg.inventoryChestStealDelayMinMs, v -> cfg.inventoryChestStealDelayMinMs = v),
				new IntSetting("Click delay max", " ms", 50, 300, 5,
						() -> cfg.inventoryChestStealDelayMaxMs, v -> cfg.inventoryChestStealDelayMaxMs = v),
				new StringSetting("Blacklisted", "minecraft:stick",
						() -> String.join(", ", cfg.inventoryChestStealBlacklist),
						value -> replaceCsv(cfg.inventoryChestStealBlacklist, value))));
		m.put("refill", new Module("refill", "Refill", healingSplashPotion(),
				() -> false, v -> { if (v) cfg.inventoryRefillRequested = true; },
				new ChoiceSetting("Type", new String[] {"Both", "Pots", "Soup"},
						() -> cfg.inventoryRefillType, v -> cfg.inventoryRefillType = v),
				new BoolSetting("Vertical", () -> cfg.inventoryRefillVertical, v -> cfg.inventoryRefillVertical = v),
				new BoolSetting("Scatter", () -> cfg.inventoryRefillScatter, v -> cfg.inventoryRefillScatter = v),
				new BoolSetting("Hotbar clear", () -> cfg.inventoryRefillHotbarClear, v -> cfg.inventoryRefillHotbarClear = v),
				new StringSetting("Non junk items", "minecraft:diamond_sword",
						() -> String.join(", ", cfg.inventoryRefillAllowedItems),
						value -> replaceCsv(cfg.inventoryRefillAllowedItems, value)).when(() -> cfg.inventoryRefillHotbarClear),
				new IntSetting("Delay min", " ms", 50, 200, 5,
						() -> cfg.inventoryRefillDelayMinMs, v -> cfg.inventoryRefillDelayMinMs = v),
				new IntSetting("Delay max", " ms", 50, 200, 5,
						() -> cfg.inventoryRefillDelayMaxMs, v -> cfg.inventoryRefillDelayMaxMs = v)).momentary());
		m.put("autohotbar", new Module("autohotbar", "AutoHotbar", Items.HOPPER,
				() -> cfg.inventoryAutoHotbar, v -> cfg.inventoryAutoHotbar = v,
				new IntSetting("Delay", " ms", 0, 300, 10,
						() -> cfg.inventoryAutoHotbarDelayMs, v -> cfg.inventoryAutoHotbarDelayMs = v),
				new IntSetting("Weapon slot", "", 1, 9, 1,
						() -> cfg.inventoryAutoHotbarWeaponSlot, v -> cfg.inventoryAutoHotbarWeaponSlot = v),
				new IntSetting("Blocks slot", "", 1, 9, 1,
						() -> cfg.inventoryAutoHotbarBlocksSlot, v -> cfg.inventoryAutoHotbarBlocksSlot = v),
				new IntSetting("Heal slot", "", 1, 9, 1,
						() -> cfg.inventoryAutoHotbarHealSlot, v -> cfg.inventoryAutoHotbarHealSlot = v),
				new IntSetting("Pearl slot", "", 1, 9, 1,
						() -> cfg.inventoryAutoHotbarPearlSlot, v -> cfg.inventoryAutoHotbarPearlSlot = v)));
		m.put("invcleaner", new Module("invcleaner", "InvCleaner", Items.LAVA_BUCKET,
				() -> false, v -> { if (v) cfg.inventoryCleanerRequested = true; },
				new IntSetting("Delay", " ms", 0, 300, 10,
						() -> cfg.inventoryCleanerDelayMs, v -> cfg.inventoryCleanerDelayMs = v),
				new BoolSetting("Keep blocks", () -> cfg.inventoryCleanerKeepBlocks, v -> cfg.inventoryCleanerKeepBlocks = v),
				new BoolSetting("Keep food", () -> cfg.inventoryCleanerKeepFood, v -> cfg.inventoryCleanerKeepFood = v),
				new BoolSetting("Keep tools", () -> cfg.inventoryCleanerKeepTools, v -> cfg.inventoryCleanerKeepTools = v),
				new BoolSetting("Keep potions", () -> cfg.inventoryCleanerKeepPotions, v -> cfg.inventoryCleanerKeepPotions = v)).momentary());

		m.put("bedbreaker", new Module("bedbreaker", "Auto Bed Breaker", Items.RED_BED,
				() -> cfg.hypixelBedBreaker, v -> cfg.hypixelBedBreaker = v));

		List<Category> categories = new ArrayList<>();
		categories.add(new Category("Combat", Items.NETHERITE_SWORD, pick(m,
				MODE_SWORD, MODE_AXE, "swordai", "hit", "aim", "strafe", "autoaim", "reach", "expandedhitbox", "jumpreset", "autopot", "velocity",
				"hitboxes", "axestun", "kbdisplace", "anchor", "totem", "autocrystal", "fastuse", "autoxp")));
		categories.add(new Category("Mace & Spear", Items.MACE, pick(m,
				MODE_MACE, "automace", "autobreachswap", "autolunge", "autospear")));
		categories.add(new Category("SubTiers", Items.DIAMOND, pick(m,
				"subtiers_autobed", "subtiers_autocreeper", "subtiers_autominecart")));
		categories.add(new Category("DonutSMP", Items.ENDER_EYE, pick(m,
				"mobesp", "advesp", "storageesp", "heatmap", "baseheat", "chunkfinder", "suschunks", "stash", "amethyst", "portals", "freecam", "tunnel")));
		categories.add(new Category("Hypixel", Items.GOLD_INGOT, pick(m,
				"autoclicker", "antifireball", "heightclutch", "clutch", "scaffold", "bedbreaker", "remember", "schematicbuild")));
		categories.add(new Category("Instants", Items.CLOCK, pick(m,
				"breakon", "fastbreak", "autotool", "fastplace", "autosprint", "autowalk")));
		categories.add(new Category("Inventory", Items.CHEST, pick(m,
				"autoarmor", "cheststeal", "refill", "autohotbar", "invcleaner", "autosign")));
		categories.add(new Category("Misc", Items.COMPASS, pick(m,
				"fullbright", "flight", "spam", "waterwalk", "boatfly", "teleporter", "nickname", "nickother",
				"pingspoof", "pingequalizer", "slow", "rtpfinder")));
		return categories;
	}

	private static List<Module> pick(Map<String, Module> m, String... ids) {
		List<Module> out = new ArrayList<>();
		for (String id : ids) {
			Module mod = m.get(id);
			if (mod != null) out.add(mod);
		}
		return out;
	}

	/** Coordinates are typed, not dragged, so a bad keystroke keeps the previous value. */
	private static int parseCoord(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim().replace(",", "").replace("_", ""));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static void replaceCsv(java.util.List<String> target, String value) {
		target.clear();
		for (String raw : value.split(",")) {
			String clean = raw.trim().toLowerCase(java.util.Locale.ROOT);
			if (!clean.isEmpty() && !target.contains(clean)) target.add(clean);
		}
	}
}
