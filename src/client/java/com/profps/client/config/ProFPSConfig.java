package com.profps.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.profps.ProFPS;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ProFPSConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Resolve Fabric's directories only when doing actual I/O. Eager static path
	// resolution made this otherwise plain data object impossible to instantiate in
	// unit tests (and other tooling) before Fabric had installed a game provider.
	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("profps.json");
	}

	private static Path profileDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("profps-configs");
	}

	public boolean enabled = true;

	// ── Combat Modes ───────────────────────────────────────────────────
	// Exactly one mode can be active. Numeric values are kept deliberately simple for
	// backwards-compatible JSON: 0 Off, 1 Sword, 2 Axe, 3 Mace. Tier indices run in
	// display order: LT5, HT5, LT4, HT4, LT3, HT3, LT2, HT2, LT1, HT1.
	//
	// These settings are an overlay, not a rewrite of the standalone module fields
	// below. Turning Modes off therefore restores the user's manual module profile.
	public int combatMode = 0;
	public int swordModeTier = 3; // HT4: exactly the existing Sword-assist defaults
	public int axeModeTier = 2;   // LT4: exactly the existing Axe-assist defaults
	public int maceModeTier = 4;  // LT3: exactly the existing Mace-assist defaults

	public boolean swordModeAim = true;
	public boolean swordModeStrafe = true;
	public boolean swordModeAutoSprint = true;
	public boolean swordModeTrigger = true;
	public boolean swordModeAiBot = false; // optional advanced layer; always off by default
	public boolean swordModeAiAim = true;
	public boolean swordModeAiJump = true;

	public boolean axeModeAim = true;
	public boolean axeModeStun = true;
	public boolean axeModeProjectileAim = true;
	public boolean axeModeBowAim = true;
	public boolean axeModeCrossbowAim = true;
	public boolean axeModeTrigger = true;
	public boolean axeModeSwordFollowup = true;
	public boolean axeModeTriggerFollowup = true;

	public boolean maceModeAutoMace = true;
	public boolean maceModeAutoSwitch = true;
	public boolean maceModeAim = true;
	public boolean maceModeBreachSwap = true;
	public boolean maceModeStunSlam = true;

	public boolean aimImprovements = false;
	public boolean aimRetargeting = true;
	public int aimAssistStrength = 54;
	public int aimAssistDurationMs = 950;
	public int aimFovDeg = 32;            // acquisition/track cone half-angle (lower = only when aimed near)
	public int aimReactionMs = 60;        // reaction before it starts pulling onto a fresh target

	public boolean strafeImprovements = false;
	public boolean strafeRandomAngle = true;
	public boolean strafeBackstep = true;  // include a backward component in the input-driven juke (side+back vs pure side-step)
	public int strafeStrength = 50;
	public int strafeReachMs = 360;
	public int strafeSkipPct = 8;         // chance to skip a juke on a hit
	public int strafeIntervalMs = 420;    // minimum gap between jukes

	public boolean hitImprovements = false;
	public boolean hitPatient = true;
	public boolean hitDisableWhileSneaking = true; // sneaking is treated as deliberate "do not attack" intent
	public boolean hitSprintAwareCooldown = true;  // slightly earlier sword/axe window while sprint-comboing
	public int hitReactionMinMs = 8;      // sampled once when a fresh target is acquired
	public int hitReactionMs = 55;        // upper end of the acquisition-reaction range
	public int hitSkipChancePct = 2;      // fewer intentional misses
	public int hitCooldownPct = 93;       // top of the sampled attack-charge band; tuned to catch the earlier ready tick
	public int hitFollowupMs = 45;        // combo follow-up motor delay (lower = faster combos)
	public int hitAxePostDelayMs = 120;   // centre of the post-ready axe delay (jittered by +/-20ms; 0 disables)
	public boolean hitCritTiming = true;  // hold the swing while you're rising into a jump; fire as you fall (crit)
	public boolean hitCritSprintRelease = true; // tap off forward mid-air so a sprinting jump can actually crit

	// Reach — extends entity targeting + the attack-range gate. Stored in HUNDREDTHS of a block.
	// Grounded in Grim's actual Reach.java: it flags when (eye→look-ray-intercept of the
	// lag-compensated, hitboxMargin-expanded target box) > maxReach, where maxReach is the
	// server-authoritative item ATTACK_RANGE component (~3.0, unspoofable) and the box is expanded
	// by threshold(0.0005) + itemHitboxMargin + movementThreshold(0.03 only on a tick with no
	// position packet, i.e. standing still). So:
	//   • 300 (3.00) = vanilla; undetectable on EVERY anticheat.
	//   • ~305 (3.05) = the Grim ceiling — within its built-in margins (esp. the free 0.03 you get
	//     standing still); safe on Grim but NOT guaranteed on stricter/zero-margin checks.
	//   • >305 flags Grim (block-impossible-hits even cancels 3.05+ in real time). Only use the
	//     higher range on lenient/no-anticheat servers (vanilla itself allows up to ~6 blocks).
	public boolean reach = false;
	public int reachCm = 300;             // 300 = 3.00 blocks (safe everywhere); Grim ceiling ~305; max 600

	// Expanded Hitbox — a near-miss acquisition margin around player boxes. The
	// attack itself is delayed until a packet-facing yaw/pitch ray intersects the
	// target's REAL box; Amount is stored in hundredths of a block.
	public boolean expandedHitbox = false;
	public int expandedHitboxAmountCm = 18;
	public int expandedHitboxTurnSpeed = 62;
	public int expandedHitboxReactionMs = 35;

	// Velocity (anti-knockback) — scales the knockback the server sends when you're hit.
	// Defaults are deliberately GENTLE so strong simulation anti-cheats (Grim) don't flag: most
	// hits are untouched and the rest only shaved a little, keeping the cumulative offset under
	// Grim's tolerance. On weak/no-anticheat servers crank Horizontal down (toward 0) + Chance up.
	public boolean velocity = false;
	public int velocityHorizontal = 80;   // % of horizontal knockback kept (lower = more negated)
	public int velocityVertical = 100;    // % of vertical knockback kept
	public int velocityChance = 30;       // apply on only this % of hits (low = far fewer flags)
	public int velocityTicks = 0;         // wait this many ticks before reducing the already-applied motion
	public boolean velocityKiteMode = false;
	public boolean velocityAlwaysKite = false;
	public int velocityKiteHorizontal = 120;
	public int velocityKiteVertical = 120;
	public boolean velocityOnlyWhenTargeting = false;
	public boolean velocityWaterCheck = false;

	public boolean autoPot = false;
	public boolean autoPotFlickToPlayer = false;
	/** Auto Pot type: 0 Pots, 1 Soup, 2 Both; mode: 0 Dynamic, 1 Single. */
	public int autoPotType = 0;
	public int autoPotMode = 0;
	public int autoPotHealth = 10;
	public boolean autoPotRandom = false;

	public boolean autoMace = false;
	public boolean autoMaceAutoSwitch = true;
	public int autoMaceFov = 45;
	public int autoMaceRange = 6;
	public int autoMaceTurnSpeed = 45;    // how snappy the view whips onto the target (higher = faster)
	public int autoMaceSettleMs = 35;     // acquisition dwell; overlaps aim + cooldown
	public int autoMaceSmashSpeed = 75;   // turn speed during a falling smash (kept high so it lands mid-air)
	/**
	 * Silent aim: the mace turns the body onto the target as it always has, and
	 * the camera stays under your own mouse. Rotations are never spoofed into
	 * packets — the body really is aimed, so movement and the packet stream stay
	 * consistent for a simulating anti-cheat. Only the drawn view changes.
	 */
	public boolean maceSilentAim = false;
	public boolean autoMaceShieldBreak = true; // stun-slam: with an axe in hand, axe-tap to disable shield / stun, then mace-smash — fires on shielding targets and falling dives
	public int autoMaceShieldBreakMs = 60;     // gap between the axe (stun) hit and the mace smash — ~1 server tick

	// Axe Stun (General) — standalone ground shield-breaker: when you're aiming at a shielder and
	// carry an axe, swap to the axe, break the shield, then swap back to your previous item.
	public boolean axeStun = false;
	public int axeStunReactionMs = 110;   // humanized reaction before the swap fires
	public int axeStunSwitchBackMs = 90;  // gap between the axe hit and swapping back (~1+ server tick)
	public boolean axeStunRestorePrevious = false; // standalone: restore the exact pre-axe hotbar slot

	// Auto Breachswap — visible sword→Breach-mace handoff on a crit descent.
	public boolean autoBreachSwap = false;     // advanced; off by default
	public int autoBreachSwapCharge = 90;      // required MACE attack-charge % at hit time (vanilla crits above 90)

	// Auto Pearl Catch — retired. It is no longer a setting anywhere and CombatModePolicy gates
	// the feature off outright; only this dormant tuning remains so the controller still compiles.
	public int pearlCatchDelayMs = 0;          // wait after the throw before lining up (low = catch it high/early)
	public int pearlCatchAngle = 0;            // degrees to aim BELOW the intercept (0 = straight at it)
	public int pearlCatchAimSpeed = 80;        // how snappy the view turns onto the catch point

	public boolean autoAim = false;       // projectile aim assist for bow/crossbow/fireball
	public int autoAimStrength = 45;      // how strongly it follows (low = gentle, easy to break out of)
	public int autoAimFov = 70;           // acquisition cone (degrees) around your look

	// Hitboxes — combat overlay. RGB is stored as 0..255; opacity as a percentage.
	public boolean hitboxes = false;
	public int hitboxRed = 255;
	public int hitboxGreen = 140;
	public int hitboxBlue = 31;
	public int hitboxOutlineOpacity = 95;
	public int hitboxFillOpacity = 14;
	public int hitboxLineWidth = 2;

	// ── SubTiers ───────────────────────────────────────────────────────────────
	public boolean subTiersAutoBed = false;
	public boolean subTiersAutoCreeper = false;
	public boolean subTiersAutoMinecart = false;
	public int subTiersMinecartBowSpeed = 6;

	/** Client-side held-item swing slowdown (mining/attacking/placing look slower; server timing unchanged). */
	public boolean slowAnimations = false;
	public int slowAnimationStrength = 4;

	// ── Instants (game-mechanic optimizers; humanized, low-detection) ──────────
	public boolean instantBreakOn = false;
	public boolean instantBreakOnHandUse = false;
	/** When on, BreakOn only mines blocks whose id is in {@link #instantBreakOnBlocks}. */
	public boolean instantBreakOnCertain = false;
	public java.util.List<String> instantBreakOnBlocks = new java.util.ArrayList<>();
	public boolean instantAutoClicker = false;
	public int instantClickCps = 10;
	public int instantClickMinCps = 6;
	public boolean instantClickHoldToClick = false;
	/** Auto Clicker randomization: 0 Normal, 1 Extra, 2 Extra+. */
	public int instantClickRandomization = 1;
	public boolean instantClickJitter = false;
	public boolean instantClickLimitItems = false;
	public java.util.List<String> instantClickAllowedItems = new java.util.ArrayList<>(java.util.List.of(
			"minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
			"minecraft:golden_sword", "minecraft:diamond_sword", "minecraft:netherite_sword"));
	public boolean instantClickBreakBlocks = false;
	public int instantClickBreakBlocksDelayMs = 0;
	public boolean instantClickRight = false;
	/** Suppress left-clicks unless a fresh vanilla ray names a live entity. */
	public boolean instantClickTargetOnly = false;
	public boolean instantFastBreak = false;
	public int instantFastBreakLevel = 4;
	public boolean instantAutoTool = false;
	public int instantAutoToolSwapToDelayMs = 50;
	public boolean instantAutoToolSwapWeapon = true;
	public boolean instantAutoToolInstantWeapon = true;
	public boolean instantAutoToolSwapBack = false;
	public int instantAutoToolSwapBackDelayMs = 350;
	public boolean instantAutoToolRequireMouseDown = true;
	public boolean instantAutoToolOnlySneaking = false;
	public boolean instantFastPlace = false;
	/** Fast Place held-item modes: 0 All, 1 Blocks, 2 Projectiles. */
	public int instantFastPlaceHeldItem = 0;
	public int instantFastPlaceDelay = 1;

	// ── Inventory automation (shared serialized click engine) ───────────────
	public boolean inventoryAutoArmor = false;
	public boolean inventoryAutoArmorOpen = true;
	public boolean inventoryAutoArmorOnly = true;
	public boolean inventoryAutoArmorDurability = true;
	public boolean inventoryAutoArmorDropEquipped = false;
	public boolean inventoryAutoArmorCombatCheck = false;
	public int inventoryAutoArmorDelayMinMs = 100;
	public int inventoryAutoArmorDelayMaxMs = 120;
	public boolean inventoryChestSteal = false;
	public boolean inventoryChestStealCheckMenu = false;
	public boolean inventoryChestStealBestOnly = false;
	public boolean inventoryChestStealKeepOpen = false;
	public boolean inventoryChestStealShuffle = false;
	public int inventoryChestStealDelayMinMs = 150;
	public int inventoryChestStealDelayMaxMs = 200;
	public java.util.List<String> inventoryChestStealBlacklist = new java.util.ArrayList<>();
	public boolean inventoryRefillRequested = false;
	/** Refill type: 0 Both, 1 Pots, 2 Soup. */
	public int inventoryRefillType = 0;
	public boolean inventoryRefillVertical = false;
	public boolean inventoryRefillScatter = false;
	public boolean inventoryRefillHotbarClear = false;
	public int inventoryRefillDelayMinMs = 75;
	public int inventoryRefillDelayMaxMs = 125;
	public java.util.List<String> inventoryRefillAllowedItems = new java.util.ArrayList<>();
	public boolean inventoryAutoHotbar = false;
	public int inventoryAutoHotbarDelayMs = 110;
	public int inventoryAutoHotbarWeaponSlot = 1;
	public int inventoryAutoHotbarBlocksSlot = 2;
	public int inventoryAutoHotbarHealSlot = 6;
	public int inventoryAutoHotbarPearlSlot = 7;
	public boolean inventoryCleanerRequested = false;
	public int inventoryCleanerDelayMs = 100;
	public boolean inventoryCleanerKeepBlocks = true;
	public boolean inventoryCleanerKeepFood = true;
	public boolean inventoryCleanerKeepTools = true;
	public boolean inventoryCleanerKeepPotions = true;

	/** Hypixel Bed Breaker; the alternate name preserves existing profiles from v87. */
	@SerializedName(value = "hypixelBedBreaker", alternate = {"worldBedBreaker"})
	public boolean hypixelBedBreaker = false;
	public boolean instantAutoSprint = false;
	public boolean instantAutoWalk = false;

	// ── Extra Assists ──────────────────────────────────────────────────────────
	public boolean scaffoldAssist = false;
	/** Scaffold modes: 0 Legit, 1 GodBridge, 2 TellyBridge. */
	public int scaffoldMode = 0;
	public boolean scaffoldBlockCount = false;
	public boolean scaffoldPitchCheck = false;
	public int scaffoldPitch = 45;
	public boolean scaffoldBlacklist = true;
	public java.util.List<String> scaffoldBlacklistBlocks = new java.util.ArrayList<>(java.util.List.of(
			"minecraft:dispenser", "minecraft:note_block", "minecraft:cobweb", "minecraft:tnt",
			"minecraft:spawner", "minecraft:enchanting_table", "minecraft:oak_fence", "minecraft:jukebox",
			"minecraft:melon", "minecraft:command_block", "minecraft:anvil", "minecraft:glass_pane",
			"minecraft:white_stained_glass_pane", "minecraft:iron_bars", "minecraft:ice",
			"minecraft:packed_ice", "minecraft:redstone_block", "minecraft:gold_ore", "minecraft:iron_ore",
			"minecraft:coal_ore", "minecraft:lapis_ore", "minecraft:redstone_ore", "minecraft:acacia_stairs",
			"minecraft:oak_pressure_plate", "minecraft:stone_pressure_plate", "minecraft:beacon",
			"minecraft:oak_sapling", "minecraft:powered_rail", "minecraft:detector_rail",
			"minecraft:dead_bush", "minecraft:dandelion", "minecraft:poppy", "minecraft:brown_mushroom",
			"minecraft:red_mushroom", "minecraft:ladder", "minecraft:rail", "minecraft:oak_trapdoor",
			"minecraft:lily_pad", "minecraft:tripwire_hook", "minecraft:white_carpet", "minecraft:snow",
			"minecraft:trapped_chest", "minecraft:daylight_detector", "minecraft:hopper",
			"minecraft:chest", "minecraft:torch", "minecraft:lever", "minecraft:redstone_torch",
			"minecraft:stone_button", "minecraft:cactus"));
	public boolean scaffoldWhitelist = false;
	public java.util.List<String> scaffoldWhitelistBlocks = new java.util.ArrayList<>();
	public int scaffoldSneakDelayMinMs = 100;
	public int scaffoldSneakDelayMaxMs = 200;
	public boolean scaffoldRequireSneak = false;
	public int scaffoldGodActivationBlocks = 2;
	public boolean scaffoldTellyRequireRightClick = true;
	public int scaffoldTellyActivationBlocks = 2;
	public int scaffoldTellyYIncrease = 1;
	// Retained only so old JSON remains readable; the replacement mode system does not use them.
	public boolean scaffoldSneakOnly = false;
	public int scaffoldSpeed = 7; // 0 (slowest / most cautious) .. 10 (fastest) — how quick bridging is
	public boolean clutchAssist = false;
	public boolean heightClutchAssist = false; // water-bucket MLG / ladder fall-save from a held item
	public boolean autoXpEnabled = false;      // throw XP bottles until Mending armour is back to full
	public int autoXpDelayMs = 200;            // gap between throws (0-1000), jittered on top
	public boolean autoSignEnabled = false;    // write configured text onto every sign you place
	public String autoSignLine1 = "";
	public String autoSignLine2 = "";
	public String autoSignLine3 = "";
	public String autoSignLine4 = "";
	public boolean rtpFinderEnabled = false;   // spam an rtp command until it lands near a target
	public int rtpFinderTargetX = 0;
	public int rtpFinderTargetZ = 0;
	public int rtpFinderRadius = 1;            // 0 = 5k, 1 = 10k, 2 = 20k blocks or less
	public String rtpFinderCommand = "/rtp";   // any command, e.g. "/rtp eu west"
	public int rtpFinderIntervalMs = 1000;     // gap between attempts (250-10000)
	public boolean jumpResetAssist = false;    // real humanized jump on taking a hit, to cut knockback
	public int jumpResetReactionMs = 40;       // reaction after a hit before the jump fires (lower = faster)
	public int jumpResetSkipPct = 6;           // chance to skip a reset (humanizing)
	public boolean pingSpoofEnabled = false;
	public int pingSpoofMs = 100;              // delay added to the KeepAlive reply → the ping the server reports
	public boolean pingEqualizerEnabled = false; // match your reported ping to the opponent you're fighting
	public boolean antiFireballAssist = false; // smooth-aim + sword-deflect incoming bedwars fireballs
	public boolean rememberEnabled = false;    // capture multiple builds as translucent real-block ghosts
	public boolean schematicBuildEnabled = false; // hover-to-place from Remember or an optional loaded Litematica placement
	public boolean schematicAutoMove = true;  // walk/fly the build itself, interior-first, instead of hover-to-place
	public boolean schematicTemporaryBlocks = true; // bridge/stair/platform supports for otherwise unsupported schematic cells
	public boolean swordAiEnabled = false;     // distilled local movement model for sword combat
	public boolean swordAiAim = true;          // let the AI turn your view (pitch + yaw) onto the target
	public boolean swordAiJump = true;         // let the AI jump for crits + occasional movement (never constant)

	// ── Classics ───────────────────────────────────────────────────────────────
	public boolean flightEnabled = false;
	public int flightSpeed = 5; // 1 (slow) .. 10 (fast) blocks-per-tick fly speed
	public boolean spamEnabled = false;
	public String spamMessage = "Novaclient Is The Best Client";
	public int spamSpeed = 5; // 1 (slow) .. 10 (fast) chat-spam rate
	public boolean waterWalkEnabled = false;
	public boolean boatFlyEnabled = false;
	public int boatFlySpeed = 5; // 1 (slow) .. 10 (fast) boat-fly speed
	public boolean teleporterEnabled = false;
	public int teleporterRange = 64; // max blocks the look-and-right-click teleport reaches
	public boolean fullBrightEnabled = false;
	public int fullBrightLevel = 7; // 1 (gentle lift) .. 10 (maximum night-vision-grade lightmap)
	// ── NovaClient skin ─────────────────────────────────────────────────────────
	public String  novaSkinUsername = "";       // Configure: wear this player's skin everywhere YOU see (client-side)

	// ── Nickname (self) ──────────────────────────────────────────────────────
	public boolean nicknameEnabled = false;
	public String nicknameSelfName = "Marlowww"; // the name shown everywhere instead of your real one
	public boolean nicknameSelfSkin = true;       // also wear a skin (on by default)
	public String nicknameSelfSkinFrom = "";       // whose skin to wear (blank = your nickname's own skin)
	// ── Nick Other ───────────────────────────────────────────────────────────
	public boolean nickOtherEnabled = false;
	public java.util.List<NickEntry> nickOtherEntries = new java.util.ArrayList<>();

	public int categoryX = 6;
	public int categoryY = 28;
	public boolean categoryCollapsed = false;
	public boolean aimModuleExpanded = false;
	public boolean strafeModuleExpanded = false;
	public boolean hitModuleExpanded = false;

	public int crystalCategoryX = 160;
	public int crystalCategoryY = 28;
	public boolean crystalCategoryCollapsed = false;
	@SerializedName(value = "anchorMacro", alternate = {"anchorTweaks"})
	public boolean anchorMacro = false;
	/** Anchor modes: 0 On bind, 1 On place. */
	public int anchorMode = 1;
	/** When false, stop after charging and finish on a safe/whitelisted hotbar item. */
	public boolean anchorDetonate = true;
	public boolean anchorSafe = false;
	public boolean anchorExplosionItemWhitelist = false;
	public java.util.List<String> anchorExplosionItems = new java.util.ArrayList<>(java.util.List.of("minecraft:totem_of_undying"));
	public boolean anchorAimAssist = true;
	public boolean anchorSilentAim = true;
	public int anchorAimSpeedTenths = 120;
	public int anchorDelayMinMs = 0;
	public int anchorDelayMaxMs = 25;
	public boolean anchorStopWhenNoTotem = false; // off: detonate anyway; on: abort unless a totem is in reach
	public boolean totemTweaks = false;
	/**
	 * Last resort for a totem that is not in the hotbar. The normal refill is the
	 * swap-hands key, which needs no screen; a slot click implies an open inventory,
	 * and an open inventory implies a player who is not sprinting or swinging.
	 */
	public boolean totemOpenInventory = true; // open the inventory GUI to refill (vs. a silent offhand swap)
	public boolean autoCrystal = false;
	/** Crystal Aura modes: 0 Auto, 1 Manual. */
	public int crystalAuraMode = 0;
	/** Auto target priority: 0 Distance, 1 Yaw, 2 Armor, 3 Health. */
	public int crystalTargetMode = 0;
	public int crystalRangeTenths = 45;
	public int crystalMaxAngle = 120;
	public int crystalAimSpeedTenths = 45;
	public int crystalMinEfficiency = 50;
	public int crystalDelayMinMs = 50;
	public int crystalDelayMaxMs = 150;
	public boolean crystalAntiSuicide = true;
	public int crystalMaxSelfDamage = 19;
	/** 0 None, 1 Rapid fire, 2 Predict. Predict never deletes entities locally. */
	public int crystalOptimization = 0;
	public int crystalRapidMinEfficiency = 50;
	public boolean crystalPredictAttackVelocity = true;
	public boolean crystalAutoObsidian = false;
	public boolean crystalCenterScreen = true;
	public boolean crystalShowTarget = false;
	public boolean crystalManualPlaceObsidian = false;
	public boolean crystalManualShowTargetBlock = true;
	public int autoCrystalSpeed = 4; // padding after each confirmed action: 1 (~6 ticks) → 10 (none)
	public boolean autoCrystalStrictRay = true; // require the fresh vanilla ray; never wait on render cache
	public boolean fastUse = false;
	public boolean fastUseExpanded = false;
	public int fastUseLevel = 6;

	public int donutCategoryX = 314;
	public int donutCategoryY = 28;
	public boolean donutCategoryCollapsed = false;
	public boolean donutBasicEsp = false;
	public boolean donutAdvancedEsp = false;   // "Hole/Tunnel/Stairs ESP" in the UI — terrain cavities only
	public boolean donutAdvancedShowStairs = true;
	public transient boolean donutAdvancedEspReloadRequested = false; // momentary UI "Reload" signal (not persisted)
	public transient boolean autoLungeRequested = false; // momentary Auto Lunge keybind/click signal (not persisted)

	// Knockback Displacement — momentary, keybind-fired sprint-reset displacement hit.
	public transient boolean kbDisplaceRequested = false; // momentary keybind/click signal (not persisted)
	public int kbDisplaceAimSpeed = 70;   // how snappy the view whips onto the target
	public boolean kbDisplaceReset = true; // W-tap (sprint reset) before the hit for full sprint knockback
	// Spear: one-shot Lunge plus manual kinetic-charge aim support.
	public boolean lungeAim = true;
	public int lungeFov = 55;
	public int lungeRange = 14;
	public int lungeTurnSpeed = 70;
	public boolean lungeSpearMace = true;
	public boolean lungeShieldBreak = true;
	/** Pace the jab by how fast the key is actually spammed, and chain queued presses. */
	public boolean lungeSpamScaling = true;
	/** Sampled reaction, charge overshoot and recovery gaps instead of fixed frame offsets. */
	public boolean lungeSwapHumanize = true;
	/** Sprint-jump into the burst; on the ground friction scrubs the velocity off almost at once. */
	public boolean lungeSwapJump = true;
	/** Visible aim support only while the player manually holds a spear charge. */
	/** Auto Spear: arms the kinetic charge so a fly-through lands on contact. */
	public boolean autoSpearEnabled = false;
	public int autoSpearFov = 75;
	public int autoSpearRange = 42;
	public int autoSpearTurnSpeed = 48;
	/** Pull the spear from the hotbar for the run in, and put the old slot back after. */
	public boolean autoSpearAutoSwitch = true;
	/** Camera stays under your own mouse while the approach is aimed. Off by default. */
	public boolean autoSpearSilentAim = false;
	public boolean donutStashPinger = false;
	public boolean donutFreecam = false;
	public int donutFreecamSpeed = 5; // 1 (slow / precise) .. 10 (fast); 5 = the classic default
	public boolean donutChunkActivity = false;
	public boolean donutChunkFinder = false;   // "Activity Chunks" in the UI — flags chunks by recent activity
	/** Storage ESP: containers and redstone machinery, independent of Hole/Tunnel/Stairs ESP. */
	public boolean donutStorageEsp = false;
	public int donutStorageEspRange = 128;
	public int donutStorageEspOpacity = 22;    // fill alpha percent; outlines stay near-opaque
	public boolean donutStorageShowChests = true;
	public boolean donutStorageShowShulkers = true;
	public boolean donutStorageShowRedstone = true;
	public boolean donutStorageShowFurnaces = true;
	public boolean donutStorageEspExpanded = false;
	/** Suspicious Chunks: scarce, base-evidence-only chunk flags for depths you cannot see. */
	public boolean donutSuspiciousChunks = false;
	public int donutSuspiciousChunksRange = 256;
	public int donutSuspiciousChunksCeiling = 8;  // only evidence at or below this Y counts
	public boolean donutSuspiciousChunksLabels = true;
	public boolean donutSuspiciousChunksExpanded = false;
	public boolean donutAmethystDetector = false;
	public boolean donutNetherPortalMapper = false;
	public boolean donutPlayerSightings = false;
	public boolean donutTunnel = false;
	public boolean donutTunnelExpanded = false;
	public boolean donutBasicEspExpanded = false;
	public boolean donutAdvancedEspExpanded = false;
	public boolean donutStashPingerExpanded = false;
	public boolean donutChunkActivityExpanded = false;
	public boolean donutChunkFinderExpanded = false;
	public boolean donutAmethystDetectorExpanded = false;
	public int donutBasicEspRange = 192;
	public int donutAdvancedEspRange = 192;
	public int donutStashPingerRange = 192;
	public int donutChunkActivityRange = 192;
	public int donutChunkFinderRange = 192;
	public int donutAmethystDetectorRange = 192;
	public int donutTunnelHpThreshold = 10;
	public boolean donutBasicShowPlayers = true;
	public boolean donutBasicShowMonsters = true;
	public boolean donutBasicShowPassive = true;
	public boolean donutBasicShowAquatic = true;
	public boolean donutAdvancedShowTunnels = true;
	public boolean donutAdvancedShowPockets = true;
	public boolean donutAdvancedShowShafts = true;
	public boolean donutAdvancedShowPlaced = true;
	public boolean donutAdvancedShowSpawners = true;
	public boolean donutStashShowBases = true;
	public boolean donutStashShowSpawners = true;
	public boolean donutChunkFinderTracers = true;
	public boolean donutChunkFinderLabels = true;
	/** Aggressive side-channel mining (off by default): exact container intel from loaded chunks + boosted light-leak sensitivity. */
	public boolean donutChunkExperimental = false;

	/** GLFW key code per module id, assigned from the Nova GUI keybind rows. */
	public Map<String, Integer> moduleKeybinds = new HashMap<>();

	// ── GUI theme + preferences (the Theme / Settings pages) ─────────────────────
	public int guiAccent = 0;            // accent-colour preset index (0 = Ice Blue)
	public boolean guiAnimations = true; // eased motion on cards, sidebar, toggles
	public boolean guiGlow = true;       // soft accent glow behind active elements
	public boolean guiSounds = true;     // UI click sounds
	public boolean guiAutoScale = true;  // fit the entire Nova layout to the current GUI viewport
	public int guiScalePct = 100;         // requested manual size; the viewport fit ceiling still wins

	// ── On-screen HUD ────────────────────────────────────────────────────────────
	public boolean hudModuleList = true;       // enabled-modules list at the screen edge (on by default)
	public boolean hudModuleListRight = false; // false = left edge (default), true = right edge

	// ── Packet Utils (its own sidebar page) ──────────────────────────────────────
	// Master switch for the in-GUI packet toolkit. Off by default. When on, EVERY screen
	// you open (chests, auction houses, trades, …) gets a Nova-styled overlay of packet
	// controls. The live send/delay/desync toggles are session state on PacketManager,
	// not persisted here — a fresh session always starts sending normally.
	public boolean packetUtils = false;

	public int configVersion = 100;

	public static ProFPSConfig load() {
		Path path = configPath();
		if (!Files.exists(path)) {
			ProFPSConfig config = new ProFPSConfig();
			config.save();
			return config;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			ProFPSConfig config = GSON.fromJson(reader, ProFPSConfig.class);
			if (config == null) {
				return new ProFPSConfig();
			}
			if (config.sanitize()) {
				config.save();
			}
			return config;
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to load ProFPS config. Using defaults.", exception);
			return new ProFPSConfig();
		}
	}

	public void save() {
		try {
			Path path = configPath();
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException exception) {
			ProFPS.LOGGER.warn("Failed to save ProFPS config.", exception);
		}
	}

	// ── Named config profiles (the Configs page) ─────────────────────────────────
	// Saved under <config>/profps-configs/<name>.json. Loading copies fields INTO the live
	// instance (controllers hold a reference to it, so we mutate rather than swap it out).

	private static String sanitizeName(String name) {
		String clean = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9 _-]", "").trim();
		return clean.length() > 40 ? clean.substring(0, 40) : clean;
	}

	/** Names of all saved config profiles (alphabetical). */
	public static List<String> listProfiles() {
		List<String> out = new ArrayList<>();
		Path profileDir = profileDir();
		if (!Files.isDirectory(profileDir)) return out;
		try (Stream<Path> files = Files.list(profileDir)) {
			files.filter(p -> p.getFileName().toString().endsWith(".json"))
					.forEach(p -> {
						String n = p.getFileName().toString();
						out.add(n.substring(0, n.length() - 5));
					});
		} catch (IOException exception) {
			ProFPS.LOGGER.warn("Failed to list ProFPS config profiles.", exception);
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** Save a snapshot of the current settings under {@code name}. Returns the cleaned name, or null. */
	public String saveProfile(String name) {
		String clean = sanitizeName(name);
		if (clean.isEmpty()) return null;
		try {
			Path profileDir = profileDir();
			Files.createDirectories(profileDir);
			try (Writer writer = Files.newBufferedWriter(profileDir.resolve(clean + ".json"))) {
				GSON.toJson(this, writer);
			}
			return clean;
		} catch (IOException exception) {
			ProFPS.LOGGER.warn("Failed to save config profile '{}'.", clean, exception);
			return null;
		}
	}

	/** Load a saved profile into THIS instance (then persist to the live file). True on success. */
	public boolean loadProfile(String name) {
		String clean = sanitizeName(name);
		Path file = profileDir().resolve(clean + ".json");
		if (clean.isEmpty() || !Files.exists(file)) return false;
		try (Reader reader = Files.newBufferedReader(file)) {
			ProFPSConfig loaded = GSON.fromJson(reader, ProFPSConfig.class);
			if (loaded == null) return false;
			loaded.sanitize();
			copyFrom(loaded);
			save();
			return true;
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to load config profile '{}'.", clean, exception);
			return false;
		}
	}

	public static boolean deleteProfile(String name) {
		try {
			return Files.deleteIfExists(profileDir().resolve(sanitizeName(name) + ".json"));
		} catch (IOException exception) {
			return false;
		}
	}

	/** Reset every setting to defaults (mutating the live instance) and persist. */
	public void resetToDefaults() {
		copyFrom(new ProFPSConfig());
		save();
	}

	/** Copy every persisted field from {@code other} into this live instance (reflection). */
	private void copyFrom(ProFPSConfig other) {
		for (Field field : ProFPSConfig.class.getDeclaredFields()) {
			int mods = field.getModifiers();
			if (Modifier.isStatic(mods) || Modifier.isFinal(mods) || Modifier.isTransient(mods)) continue;
			try {
				field.set(this, field.get(other));
			} catch (IllegalAccessException ignored) {
				// skip fields we can't reflect into
			}
		}
	}

	private boolean sanitize() {
		boolean changed = false;
		if (guiScalePct < 50 || guiScalePct > 140) {
			guiScalePct = MathHelper.clamp(guiScalePct, 50, 140);
			changed = true;
		}
		if (fullBrightLevel < 1 || fullBrightLevel > 10) {
			fullBrightLevel = MathHelper.clamp(fullBrightLevel, 1, 10);
			changed = true;
		}
		if (combatMode < 0 || combatMode > 3) {
			// Corrupt/manual JSON must fail closed instead of silently activating Mace.
			combatMode = 0;
			changed = true;
		}
		if (swordModeTier < 0 || swordModeTier > 9) {
			swordModeTier = MathHelper.clamp(swordModeTier, 0, 9);
			changed = true;
		}
		if (axeModeTier < 0 || axeModeTier > 9) {
			axeModeTier = MathHelper.clamp(axeModeTier, 0, 9);
			changed = true;
		}
		if (maceModeTier < 0 || maceModeTier > 9) {
			maceModeTier = MathHelper.clamp(maceModeTier, 0, 9);
			changed = true;
		}
		if (moduleKeybinds == null) {
			moduleKeybinds = new HashMap<>();
			changed = true;
		}
		if (nickOtherEntries == null) {
			nickOtherEntries = new java.util.ArrayList<>();
			changed = true;
		}
		if (nicknameSelfName == null) {
			nicknameSelfName = "Marlowww";
			changed = true;
		}
		if (novaSkinUsername == null) {
			novaSkinUsername = "";
		}
		if (nicknameSelfSkinFrom == null) {
			nicknameSelfSkinFrom = "";
			changed = true;
		}
		if (aimAssistStrength < 15 || aimAssistStrength > 90) {
			aimAssistStrength = MathHelper.clamp(aimAssistStrength, 15, 90);
			changed = true;
		}
		if (aimAssistDurationMs < 250 || aimAssistDurationMs > 2200) {
			aimAssistDurationMs = MathHelper.clamp(aimAssistDurationMs, 250, 2200);
			changed = true;
		}
		if (aimFovDeg < 10 || aimFovDeg > 90) {
			aimFovDeg = MathHelper.clamp(aimFovDeg, 10, 90);
			changed = true;
		}
		if (aimReactionMs < 0 || aimReactionMs > 200) {
			aimReactionMs = MathHelper.clamp(aimReactionMs, 0, 200);
			changed = true;
		}
		if (strafeStrength < 15 || strafeStrength > 90) {
			strafeStrength = MathHelper.clamp(strafeStrength, 15, 90);
			changed = true;
		}
		if (strafeReachMs < 180 || strafeReachMs > 720) {
			strafeReachMs = MathHelper.clamp(strafeReachMs, 180, 720);
			changed = true;
		}
		if (strafeSkipPct < 0 || strafeSkipPct > 30) {
			strafeSkipPct = MathHelper.clamp(strafeSkipPct, 0, 30);
			changed = true;
		}
		if (strafeIntervalMs < 150 || strafeIntervalMs > 800) {
			strafeIntervalMs = MathHelper.clamp(strafeIntervalMs, 150, 800);
			changed = true;
		}
		if (hitReactionMinMs < 0 || hitReactionMinMs > 300) {
			hitReactionMinMs = MathHelper.clamp(hitReactionMinMs, 0, 300);
			changed = true;
		}
		if (hitReactionMs < 5 || hitReactionMs > 300) {
			hitReactionMs = MathHelper.clamp(hitReactionMs, 5, 300);
			changed = true;
		}
		if (hitReactionMinMs > hitReactionMs) {
			hitReactionMinMs = hitReactionMs;
			changed = true;
		}
		if (hitFollowupMs < 20 || hitFollowupMs > 200) {
			hitFollowupMs = MathHelper.clamp(hitFollowupMs, 20, 200);
			changed = true;
		}
		if (hitCooldownPct < 60 || hitCooldownPct > 100) {
			hitCooldownPct = MathHelper.clamp(hitCooldownPct, 60, 100);
			changed = true;
		}
		if (hitAxePostDelayMs < 0 || hitAxePostDelayMs > 300) {
			hitAxePostDelayMs = MathHelper.clamp(hitAxePostDelayMs, 0, 300);
			changed = true;
		}
		if (hitSkipChancePct < 0 || hitSkipChancePct > 15) {
			hitSkipChancePct = MathHelper.clamp(hitSkipChancePct, 0, 15);
			changed = true;
		}
		if (expandedHitboxAmountCm < 5 || expandedHitboxAmountCm > 150) {
			expandedHitboxAmountCm = MathHelper.clamp(expandedHitboxAmountCm, 5, 150);
			changed = true;
		}
		if (expandedHitboxTurnSpeed < 10 || expandedHitboxTurnSpeed > 100) {
			expandedHitboxTurnSpeed = MathHelper.clamp(expandedHitboxTurnSpeed, 10, 100);
			changed = true;
		}
		if (expandedHitboxReactionMs < 0 || expandedHitboxReactionMs > 180) {
			expandedHitboxReactionMs = MathHelper.clamp(expandedHitboxReactionMs, 0, 180);
			changed = true;
		}
		int oldVelocityTicks = velocityTicks;
		velocityTicks = MathHelper.clamp(velocityTicks, 0, 10);
		changed |= oldVelocityTicks != velocityTicks;
		int oldKiteHorizontal = velocityKiteHorizontal;
		velocityKiteHorizontal = MathHelper.clamp(velocityKiteHorizontal, 100, 300);
		changed |= oldKiteHorizontal != velocityKiteHorizontal;
		int oldKiteVertical = velocityKiteVertical;
		velocityKiteVertical = MathHelper.clamp(velocityKiteVertical, 100, 300);
		changed |= oldKiteVertical != velocityKiteVertical;
		autoPotType = MathHelper.clamp(autoPotType, 0, 2);
		autoPotMode = MathHelper.clamp(autoPotMode, 0, 1);
		autoPotHealth = MathHelper.clamp(autoPotHealth, 1, 20);
		if (hitboxRed < 0 || hitboxRed > 255) {
			hitboxRed = MathHelper.clamp(hitboxRed, 0, 255);
			changed = true;
		}
		if (hitboxGreen < 0 || hitboxGreen > 255) {
			hitboxGreen = MathHelper.clamp(hitboxGreen, 0, 255);
			changed = true;
		}
		if (hitboxBlue < 0 || hitboxBlue > 255) {
			hitboxBlue = MathHelper.clamp(hitboxBlue, 0, 255);
			changed = true;
		}
		if (hitboxOutlineOpacity < 10 || hitboxOutlineOpacity > 100) {
			hitboxOutlineOpacity = MathHelper.clamp(hitboxOutlineOpacity, 10, 100);
			changed = true;
		}
		if (hitboxFillOpacity < 0 || hitboxFillOpacity > 80) {
			hitboxFillOpacity = MathHelper.clamp(hitboxFillOpacity, 0, 80);
			changed = true;
		}
		if (hitboxLineWidth < 1 || hitboxLineWidth > 5) {
			hitboxLineWidth = MathHelper.clamp(hitboxLineWidth, 1, 5);
			changed = true;
		}
		if (fastUseLevel < 1 || fastUseLevel > 10) {
			fastUseLevel = MathHelper.clamp(fastUseLevel, 1, 10);
			changed = true;
		}
		if (autoCrystalSpeed < 1 || autoCrystalSpeed > 10) {
			autoCrystalSpeed = MathHelper.clamp(autoCrystalSpeed, 1, 10);
			changed = true;
		}
		int oldScaffoldMode = scaffoldMode;
		scaffoldMode = MathHelper.clamp(scaffoldMode, 0, 2);
		changed |= oldScaffoldMode != scaffoldMode;
		int oldCrystalMode = crystalAuraMode;
		crystalAuraMode = MathHelper.clamp(crystalAuraMode, 0, 1);
		changed |= oldCrystalMode != crystalAuraMode;
		int oldCrystalTarget = crystalTargetMode;
		crystalTargetMode = MathHelper.clamp(crystalTargetMode, 0, 3);
		changed |= oldCrystalTarget != crystalTargetMode;
		int oldCrystalOptimization = crystalOptimization;
		crystalOptimization = MathHelper.clamp(crystalOptimization, 0, 2);
		changed |= oldCrystalOptimization != crystalOptimization;
		int oldAnchorMode = anchorMode;
		anchorMode = MathHelper.clamp(anchorMode, 0, 1);
		changed |= oldAnchorMode != anchorMode;
		scaffoldPitch = MathHelper.clamp(scaffoldPitch, 0, 90);
		scaffoldSneakDelayMinMs = MathHelper.clamp(scaffoldSneakDelayMinMs, 0, 500);
		scaffoldSneakDelayMaxMs = MathHelper.clamp(scaffoldSneakDelayMaxMs, scaffoldSneakDelayMinMs, 500);
		scaffoldGodActivationBlocks = MathHelper.clamp(scaffoldGodActivationBlocks, 1, 4);
		scaffoldTellyActivationBlocks = MathHelper.clamp(scaffoldTellyActivationBlocks, 1, 4);
		scaffoldTellyYIncrease = MathHelper.clamp(scaffoldTellyYIncrease, 0, 3);
		crystalRangeTenths = MathHelper.clamp(crystalRangeTenths, 0, 60);
		crystalMaxAngle = MathHelper.clamp(crystalMaxAngle, 1, 360);
		crystalAimSpeedTenths = MathHelper.clamp(crystalAimSpeedTenths, 10, 100);
		crystalMinEfficiency = MathHelper.clamp(crystalMinEfficiency, 0, 100);
		crystalRapidMinEfficiency = MathHelper.clamp(crystalRapidMinEfficiency, 0, 100);
		crystalDelayMinMs = MathHelper.clamp(crystalDelayMinMs, 0, 500);
		crystalDelayMaxMs = MathHelper.clamp(crystalDelayMaxMs, crystalDelayMinMs, 500);
		crystalMaxSelfDamage = MathHelper.clamp(crystalMaxSelfDamage, 0, 20);
		anchorAimSpeedTenths = MathHelper.clamp(anchorAimSpeedTenths, 10, 150);
		anchorDelayMinMs = MathHelper.clamp(anchorDelayMinMs, 0, 500);
		anchorDelayMaxMs = MathHelper.clamp(anchorDelayMaxMs, anchorDelayMinMs, 500);
		if (lungeFov < 20 || lungeFov > 120) {
			lungeFov = MathHelper.clamp(lungeFov, 20, 120);
			changed = true;
		}
		if (lungeRange < 4 || lungeRange > 24) {
			lungeRange = MathHelper.clamp(lungeRange, 4, 24);
			changed = true;
		}
		if (lungeTurnSpeed < 20 || lungeTurnSpeed > 85) {
			lungeTurnSpeed = MathHelper.clamp(lungeTurnSpeed, 20, 85);
			changed = true;
		}
		if (autoSpearFov < 20 || autoSpearFov > 140) {
			autoSpearFov = MathHelper.clamp(autoSpearFov, 20, 140);
			changed = true;
		}
		if (autoSpearRange < 8 || autoSpearRange > 96) {
			autoSpearRange = MathHelper.clamp(autoSpearRange, 8, 96);
			changed = true;
		}
		if (autoSpearTurnSpeed < 20 || autoSpearTurnSpeed > 90) {
			autoSpearTurnSpeed = MathHelper.clamp(autoSpearTurnSpeed, 20, 90);
			changed = true;
		}
		if (subTiersMinecartBowSpeed < 1 || subTiersMinecartBowSpeed > 10) {
			subTiersMinecartBowSpeed = MathHelper.clamp(subTiersMinecartBowSpeed, 1, 10);
			changed = true;
		}
		if (donutBasicEspRange < 32 || donutBasicEspRange > 1024) {
			donutBasicEspRange = MathHelper.clamp(donutBasicEspRange, 32, 1024);
			changed = true;
		}
		if (donutAdvancedEspRange < 48 || donutAdvancedEspRange > 1024) {
			donutAdvancedEspRange = MathHelper.clamp(donutAdvancedEspRange, 48, 1024);
			changed = true;
		}
		if (donutStashPingerRange < 48 || donutStashPingerRange > 1024) {
			donutStashPingerRange = MathHelper.clamp(donutStashPingerRange, 48, 1024);
			changed = true;
		}
		if (donutChunkActivityRange < 48 || donutChunkActivityRange > 1024) {
			donutChunkActivityRange = MathHelper.clamp(donutChunkActivityRange, 48, 1024);
			changed = true;
		}
		if (donutStorageEspRange < 32 || donutStorageEspRange > 512) {
			donutStorageEspRange = MathHelper.clamp(donutStorageEspRange, 32, 512);
			changed = true;
		}
		if (donutStorageEspOpacity < 5 || donutStorageEspOpacity > 60) {
			donutStorageEspOpacity = MathHelper.clamp(donutStorageEspOpacity, 5, 60);
			changed = true;
		}
		if (donutSuspiciousChunksRange < 48 || donutSuspiciousChunksRange > 1024) {
			donutSuspiciousChunksRange = MathHelper.clamp(donutSuspiciousChunksRange, 48, 1024);
			changed = true;
		}
		if (donutSuspiciousChunksCeiling < -64 || donutSuspiciousChunksCeiling > 64) {
			donutSuspiciousChunksCeiling = MathHelper.clamp(donutSuspiciousChunksCeiling, -64, 64);
			changed = true;
		}
		if (donutChunkFinderRange < 48 || donutChunkFinderRange > 1024) {
			donutChunkFinderRange = MathHelper.clamp(donutChunkFinderRange, 48, 1024);
			changed = true;
		}
		if (donutAmethystDetectorRange < 48 || donutAmethystDetectorRange > 1024) {
			donutAmethystDetectorRange = MathHelper.clamp(donutAmethystDetectorRange, 48, 1024);
			changed = true;
		}
		if (donutTunnelHpThreshold < 4 || donutTunnelHpThreshold > 20) {
			donutTunnelHpThreshold = MathHelper.clamp(donutTunnelHpThreshold, 4, 20);
			changed = true;
		}
		if (configVersion < 13) {
			if (donutCategoryX == 0 && donutCategoryY == 0) {
				donutCategoryX = 314;
				donutCategoryY = 28;
			}
			configVersion = 13;
			changed = true;
		}
		if (configVersion < 14) {
			donutStashPinger = false;
			configVersion = 14;
			changed = true;
		}
		if (configVersion < 15) {
			donutBasicEspRange = 192;
			donutAdvancedEspRange = 192;
			donutStashPingerRange = 192;
			donutBasicShowPlayers = true;
			donutBasicShowMonsters = true;
			donutBasicShowPassive = true;
			donutBasicShowAquatic = true;
			donutAdvancedShowTunnels = true;
			donutAdvancedShowPockets = true;
			donutAdvancedShowShafts = true;
			donutAdvancedShowPlaced = true;
			donutAdvancedShowSpawners = true;
			donutStashShowBases = true;
			donutStashShowSpawners = true;
			configVersion = 15;
			changed = true;
		}
		if (configVersion < 16) {
			donutFreecam = false;
			configVersion = 16;
			changed = true;
		}
		if (configVersion < 17) {
			donutChunkActivity = false;
			donutAmethystDetector = false;
			donutChunkActivityRange = 192;
			donutAmethystDetectorRange = 192;
			configVersion = 17;
			changed = true;
		}
		if (configVersion < 18) {
			donutTunnel = false;
			donutTunnelExpanded = false;
			donutTunnelHpThreshold = 10;
			configVersion = 18;
			changed = true;
		}
		if (configVersion < 19) {
			donutNetherPortalMapper = false;
			donutPlayerSightings = false;
			configVersion = 19;
			changed = true;
		}
		if (configVersion < 20) {
			autoCrystalSpeed = 10;
			configVersion = 20;
			changed = true;
		}
		if (configVersion < 21) {
			donutChunkFinder = false;
			donutChunkFinderExpanded = false;
			donutChunkFinderRange = 192;
			configVersion = 21;
			changed = true;
		}
		if (configVersion < 22) {
			autoPot = false;
			autoPotFlickToPlayer = false;
			configVersion = 22;
			changed = true;
		}
		if (slowAnimationStrength < 2 || slowAnimationStrength > 8) {
			slowAnimationStrength = MathHelper.clamp(slowAnimationStrength, 2, 8);
			changed = true;
		}
		if (configVersion < 23) {
			slowAnimations = false;
			slowAnimationStrength = 4;
			configVersion = 23;
			changed = true;
		}
		if (configVersion < 24) {
			donutChunkFinderTracers = true;
			donutChunkFinderLabels = true;
			configVersion = 24;
			changed = true;
		}
		if (configVersion < 25) {
			donutChunkExperimental = false;
			configVersion = 25;
			changed = true;
		}
		if (instantClickCps < 1 || instantClickCps > 20) {
			instantClickCps = MathHelper.clamp(instantClickCps, 1, 20);
			changed = true;
		}
		int oldClickMin = instantClickMinCps;
		instantClickMinCps = MathHelper.clamp(instantClickMinCps, 1, instantClickCps);
		changed |= oldClickMin != instantClickMinCps;
		int oldClickRandomization = instantClickRandomization;
		instantClickRandomization = MathHelper.clamp(instantClickRandomization, 0, 2);
		changed |= oldClickRandomization != instantClickRandomization;
		instantClickBreakBlocksDelayMs = MathHelper.clamp(instantClickBreakBlocksDelayMs, 0, 2000);
		if (instantClickAllowedItems == null) { instantClickAllowedItems = new java.util.ArrayList<>(); changed = true; }
		if (scaffoldSpeed < 0 || scaffoldSpeed > 10) {
			scaffoldSpeed = MathHelper.clamp(scaffoldSpeed, 0, 10);
			changed = true;
		}
		if (donutFreecamSpeed < 1 || donutFreecamSpeed > 10) {
			donutFreecamSpeed = MathHelper.clamp(donutFreecamSpeed, 1, 10);
			changed = true;
		}
		if (flightSpeed < 1 || flightSpeed > 10) {
			flightSpeed = MathHelper.clamp(flightSpeed, 1, 10);
			changed = true;
		}
		if (boatFlySpeed < 1 || boatFlySpeed > 10) {
			boatFlySpeed = MathHelper.clamp(boatFlySpeed, 1, 10);
			changed = true;
		}
		if (pingSpoofMs < 20 || pingSpoofMs > 1000) {
			pingSpoofMs = MathHelper.clamp(pingSpoofMs, 20, 1000);
			changed = true;
		}
		if (teleporterRange < 8 || teleporterRange > 256) {
			teleporterRange = MathHelper.clamp(teleporterRange, 8, 256);
			changed = true;
		}
		if (spamSpeed < 1 || spamSpeed > 10) {
			spamSpeed = MathHelper.clamp(spamSpeed, 1, 10);
			changed = true;
		}
		if (spamMessage == null) {
			spamMessage = "Novaclient Is The Best Client";
			changed = true;
		}
		if (instantFastBreakLevel < 1 || instantFastBreakLevel > 10) {
			instantFastBreakLevel = MathHelper.clamp(instantFastBreakLevel, 1, 10);
			changed = true;
		}
		int oldToolSwapDelay = instantAutoToolSwapToDelayMs;
		instantAutoToolSwapToDelayMs = MathHelper.clamp(instantAutoToolSwapToDelayMs, 0, 500);
		changed |= oldToolSwapDelay != instantAutoToolSwapToDelayMs;
		int oldToolBackDelay = instantAutoToolSwapBackDelayMs;
		instantAutoToolSwapBackDelayMs = MathHelper.clamp(instantAutoToolSwapBackDelayMs, 50, 1000);
		changed |= oldToolBackDelay != instantAutoToolSwapBackDelayMs;
		int oldFastPlaceMode = instantFastPlaceHeldItem;
		instantFastPlaceHeldItem = MathHelper.clamp(instantFastPlaceHeldItem, 0, 2);
		changed |= oldFastPlaceMode != instantFastPlaceHeldItem;
		int oldFastPlaceDelay = instantFastPlaceDelay;
		instantFastPlaceDelay = MathHelper.clamp(instantFastPlaceDelay, 0, 4);
		changed |= oldFastPlaceDelay != instantFastPlaceDelay;
		inventoryAutoArmorDelayMinMs = MathHelper.clamp(inventoryAutoArmorDelayMinMs, 1, 200);
		inventoryAutoArmorDelayMaxMs = MathHelper.clamp(inventoryAutoArmorDelayMaxMs, inventoryAutoArmorDelayMinMs, 200);
		inventoryChestStealDelayMinMs = MathHelper.clamp(inventoryChestStealDelayMinMs, 50, 300);
		inventoryChestStealDelayMaxMs = MathHelper.clamp(inventoryChestStealDelayMaxMs, inventoryChestStealDelayMinMs, 300);
		inventoryRefillType = MathHelper.clamp(inventoryRefillType, 0, 2);
		inventoryRefillDelayMinMs = MathHelper.clamp(inventoryRefillDelayMinMs, 50, 200);
		inventoryRefillDelayMaxMs = MathHelper.clamp(inventoryRefillDelayMaxMs, inventoryRefillDelayMinMs, 200);
		inventoryAutoHotbarDelayMs = MathHelper.clamp(inventoryAutoHotbarDelayMs, 0, 300);
		inventoryAutoHotbarWeaponSlot = MathHelper.clamp(inventoryAutoHotbarWeaponSlot, 1, 9);
		inventoryAutoHotbarBlocksSlot = MathHelper.clamp(inventoryAutoHotbarBlocksSlot, 1, 9);
		inventoryAutoHotbarHealSlot = MathHelper.clamp(inventoryAutoHotbarHealSlot, 1, 9);
		inventoryAutoHotbarPearlSlot = MathHelper.clamp(inventoryAutoHotbarPearlSlot, 1, 9);
		inventoryCleanerDelayMs = MathHelper.clamp(inventoryCleanerDelayMs, 0, 300);
		if (inventoryChestStealBlacklist == null) { inventoryChestStealBlacklist = new java.util.ArrayList<>(); changed = true; }
		if (inventoryRefillAllowedItems == null) { inventoryRefillAllowedItems = new java.util.ArrayList<>(); changed = true; }
		if (configVersion < 26) {
			instantBreakOn = false;
			instantAutoClicker = false;
			instantFastBreak = false;
			instantAutoTool = false;
			instantFastPlace = false;
			instantAutoSprint = false;
			instantAutoWalk = false;
			instantClickCps = 12;
			instantFastBreakLevel = 4;
			configVersion = 26;
			changed = true;
		}
		if (instantBreakOnBlocks == null) {
			instantBreakOnBlocks = new java.util.ArrayList<>();
			changed = true;
		}
		if (autoMaceFov < 20 || autoMaceFov > 90) {
			autoMaceFov = MathHelper.clamp(autoMaceFov, 20, 90);
			changed = true;
		}
		if (autoMaceRange < 3 || autoMaceRange > 7) {
			autoMaceRange = MathHelper.clamp(autoMaceRange, 3, 7);
			changed = true;
		}
		if (autoMaceTurnSpeed < 20 || autoMaceTurnSpeed > 90) {
			autoMaceTurnSpeed = MathHelper.clamp(autoMaceTurnSpeed, 20, 90);
			changed = true;
		}
		if (autoMaceSettleMs < 0 || autoMaceSettleMs > 150) {
			autoMaceSettleMs = MathHelper.clamp(autoMaceSettleMs, 0, 150);
			changed = true;
		}
		if (autoMaceSmashSpeed < 30 || autoMaceSmashSpeed > 95) {
			autoMaceSmashSpeed = MathHelper.clamp(autoMaceSmashSpeed, 30, 95);
			changed = true;
		}
		if (autoMaceShieldBreakMs < 0 || autoMaceShieldBreakMs > 200) {
			autoMaceShieldBreakMs = MathHelper.clamp(autoMaceShieldBreakMs, 0, 200);
			changed = true;
		}
		if (axeStunReactionMs < 0 || axeStunReactionMs > 300) {
			axeStunReactionMs = MathHelper.clamp(axeStunReactionMs, 0, 300);
			changed = true;
		}
		if (axeStunSwitchBackMs < 30 || axeStunSwitchBackMs > 250) {
			axeStunSwitchBackMs = MathHelper.clamp(axeStunSwitchBackMs, 30, 250);
			changed = true;
		}
		if (autoBreachSwapCharge < 50 || autoBreachSwapCharge > 100) {
			autoBreachSwapCharge = MathHelper.clamp(autoBreachSwapCharge, 50, 100);
			changed = true;
		}
		if (pearlCatchDelayMs < 0 || pearlCatchDelayMs > 1000) {
			pearlCatchDelayMs = MathHelper.clamp(pearlCatchDelayMs, 0, 1000);
			changed = true;
		}
		if (pearlCatchAngle < -45 || pearlCatchAngle > 45) {
			pearlCatchAngle = MathHelper.clamp(pearlCatchAngle, -45, 45);
			changed = true;
		}
		if (pearlCatchAimSpeed < 10 || pearlCatchAimSpeed > 95) {
			pearlCatchAimSpeed = MathHelper.clamp(pearlCatchAimSpeed, 10, 95);
			changed = true;
		}
		if (autoAimStrength < 10 || autoAimStrength > 90) {
			autoAimStrength = MathHelper.clamp(autoAimStrength, 10, 90);
			changed = true;
		}
		if (autoAimFov < 20 || autoAimFov > 120) {
			autoAimFov = MathHelper.clamp(autoAimFov, 20, 120);
			changed = true;
		}
		if (configVersion < 27) {
			autoMace = false;
			autoMaceFov = 45;
			autoMaceRange = 6;
			instantBreakOnCertain = false;
			configVersion = 27;
			changed = true;
		}
		if (configVersion < 28) {
			scaffoldAssist = false;
			scaffoldSneakOnly = true;
			configVersion = 28;
			changed = true;
		}
		if (configVersion < 29) {
			clutchAssist = false;
			configVersion = 29;
			changed = true;
		}
		if (configVersion < 30) {
			strafeBackstep = false; // velocity backstep is now opt-in; default to the stealth pivot-only strafe
			configVersion = 30;
			changed = true;
		}
		if (configVersion < 31) {
			configVersion = 31; // (scaffold timing migration — superseded by the Speed meter at v34)
			changed = true;
		}
		if (configVersion < 32) {
			configVersion = 32; // (scaffold auto-look was added then removed — view-rotation bridging was a mistake)
			changed = true;
		}
		if (configVersion < 33) {
			configVersion = 33;
			changed = true;
		}
		if (configVersion < 34) {
			scaffoldSpeed = 7;        // new 0-10 bridging-speed meter (replaces the old Fast toggle)
			configVersion = 34;
			changed = true;
		}
		if (configVersion < 35) {
			donutFreecamSpeed = 5;    // new 1-10 freecam speed meter (5 = the classic default)
			configVersion = 35;
			changed = true;
		}
		if (configVersion < 36) {
			instantClickCps = 12;     // autoclicker reverted to a single CPS (min/max/jitter removed)
			configVersion = 36;
			changed = true;
		}
		if (configVersion < 37) {
			flightEnabled = false;    // new Classics → Flight module
			flightSpeed = 5;
			configVersion = 37;
			changed = true;
		}
		if (configVersion < 38) {
			spamEnabled = false;      // new Classics → Spam + Water Walker
			spamMessage = "Novaclient Is The Best Client";
			spamSpeed = 5;
			waterWalkEnabled = false;
			configVersion = 38;
			changed = true;
		}
		if (configVersion < 39) {
			heightClutchAssist = false; // new Extra Assists → Height Clutch
			configVersion = 39;
			changed = true;
		}
		if (configVersion < 40) {
			boatFlyEnabled = false;    // new Classics → Boat Fly
			boatFlySpeed = 5;
			configVersion = 40;
			changed = true;
		}
		if (configVersion < 41) {
			jumpResetAssist = false;   // new Combat → Auto Jump Reset
			configVersion = 41;
			changed = true;
		}
		if (configVersion < 42) {
			pingSpoofEnabled = false;  // new Extra Assists → Ping Spoofer
			pingSpoofMs = 100;
			teleporterEnabled = false; // new Classics → Teleporter
			teleporterRange = 64;
			configVersion = 42;
			changed = true;
		}
		if (configVersion < 43) {
			antiFireballAssist = false; // new Extra Assists → Anti Fireball
			configVersion = 43;
			changed = true;
		}
		if (configVersion < 44) {
			rememberEnabled = false;    // new Extra Assists → Remember
			configVersion = 44;
			changed = true;
		}
		if (configVersion < 45) {
			pingEqualizerEnabled = false; // new Extra Assists → Ping Equalizer
			configVersion = 45;
			changed = true;
		}
		if (configVersion < 46) {
			// Strafe now moves you for real via legitimate input (the old velocity
			// backstep is gone). "Backstep" became "Backward" — include a back step
			// in the juke — and defaults ON so the strafe actually repositions you.
			strafeBackstep = true;
			configVersion = 46;
			changed = true;
		}
		if (configVersion < 47) {
			nicknameEnabled = false;      // new Classics → Nickname
			nicknameSelfName = "Marlowww";
			nicknameSelfSkin = true;
			nicknameSelfSkinFrom = "";
			nickOtherEnabled = false;     // new Classics → Nick Other
			nickOtherEntries = new java.util.ArrayList<>();
			configVersion = 47;
			changed = true;
		}
		if (configVersion < 48) {
			autoMaceShieldBreak = true;   // on by default — axe-break a held-up shield before the mace
			autoMaceShieldBreakMs = 60;
			autoBreachSwap = false;       // advanced module, off by default
			autoBreachSwapCharge = 90;
			configVersion = 48;
			changed = true;
		}
		if (configVersion < 49) {
			// (retired) the custom home screen has been removed entirely — classic main menu for everyone
			configVersion = 49;
			changed = true;
		}
		if (configVersion < 50) {
			// Push the slightly stronger triggerbot tuning to existing configs.
			hitReactionMs = 110;
			hitSkipChancePct = 2;
			hitCooldownPct = 95;
			hitFollowupMs = 70;
			configVersion = 50;
			changed = true;
		}
		if (configVersion < 51) {
			// Pearl Catch: throw earlier and aim closer to the pearl so it intercepts high up.
			pearlCatchDelayMs = 0;
			pearlCatchAngle = 10;
			pearlCatchAimSpeed = 80;
			configVersion = 51;
			changed = true;
		}
		if (configVersion < 52) {
			// Velocity: retire the brutal full-negate default for a Grim-safe gentle one.
			velocityHorizontal = 80;
			velocityVertical = 100;
			velocityChance = 30;
			configVersion = 52;
			changed = true;
		}
		if (configVersion < 53) {
			// Pearl Catch: aim straight at the intercept (no below-bias) so it tracks the fall.
			pearlCatchAngle = 0;
			pearlCatchDelayMs = 0;
			configVersion = 53;
			changed = true;
		}
		if (configVersion < 54) {
			swordAiEnabled = false;
			swordAiAim = true;
			swordAiJump = true;
			configVersion = 54;
			changed = true;
		}
		if (configVersion < 55) {
			// Auto Crystal was defaulting to full-tick speed (an obvious bot cadence); move
			// everyone down to the slower, humanized default unless they'd hand-lowered it.
			if (autoCrystalSpeed > 4) autoCrystalSpeed = 4;
			configVersion = 55;
			changed = true;
		}
		if (configVersion < 56) {
			totemOpenInventory = true; // Auto Totem opens the inventory to refill, on by default
			configVersion = 56;
			changed = true;
		}
		if (configVersion < 57) {
			configVersion = 57;
			changed = true;
		}
		if (configVersion < 58) {
			// Triggerbot v58: explicit acquisition range, stable weapon-aware cooldown
			// cycles, optional sneak pause, and a human post-ready axe beat.
			hitDisableWhileSneaking = true;
			hitSprintAwareCooldown = true;
			if (hitReactionMs == 110) hitReactionMs = 95; // migrate the old default only
			hitReactionMinMs = Math.min(20, hitReactionMs);
			hitAxePostDelayMs = 120;
			configVersion = 58;
			changed = true;
		}
		if (configVersion < 59) {
			// Hitboxes is now a through-wall Combat overlay with configurable color,
			// outline/fill opacity, and line width. Preserve the old orange look.
			hitboxRed = 255;
			hitboxGreen = 140;
			hitboxBlue = 31;
			hitboxOutlineOpacity = 95;
			hitboxFillOpacity = 14;
			hitboxLineWidth = 2;
			configVersion = 59;
			changed = true;
		}
		if (configVersion < 60) {
			subTiersAutoBed = false;
			subTiersAutoCreeper = false;
			subTiersAutoMinecart = false;
			configVersion = 60;
			changed = true;
		}
		if (configVersion < 61) {
			// Scaffold v61 follows the real movement path and supports bounded
			// diagonal/air bridging, zig-zag movement, and jump assists.
			scaffoldSneakOnly = false;
			configVersion = 61;
			changed = true;
		}
		if (configVersion < 62) {
			// Level 4 preserves the original Auto Minecart bow timing. New and
			// existing configs start at the quicker level 6 requested for v62.
			subTiersMinecartBowSpeed = 6;
			configVersion = 62;
			changed = true;
		}
		if (configVersion < 63) {
			// Scaffold no longer has any camera/aim subsystem. Advancing the
			// version also rewrites old configs without the retired aim setting.
			configVersion = 63;
			changed = true;
		}
		if (configVersion < 64) {
			// Scaffold v64 is placement-only. It never rewrites movement or jump
			// input; old movement-assist settings are retired from saved configs.
			configVersion = 64;
			changed = true;
		}
		if (configVersion < 65) {
			// Triggerbot v65 keeps sword damage near full and routes sweep-prone
			// grounded cycles through genuine pre-hit sprint input. Values equal to
			// the previous 95% default follow the new default; other values stay intact.
			if (hitCooldownPct == 95) hitCooldownPct = 100;
			configVersion = 65;
			changed = true;
		}
		if (configVersion < 66) {
			// Triggerbot v66 overlaps anti-sweep preparation with acquisition/cooldown
			// and moves the old defaults to the faster response profile. Equality is
			// the only provenance available, so exact former-default values follow it.
			if (hitCooldownPct == 100) hitCooldownPct = 93;
			if (hitReactionMinMs == 20 && hitReactionMs == 95) {
				hitReactionMinMs = 8;
				hitReactionMs = 55;
			}
			if (hitFollowupMs == 70) hitFollowupMs = 45;
			configVersion = 66;
			changed = true;
		}
		if (configVersion < 67) {
			expandedHitbox = false;
			expandedHitboxAmountCm = 18;
			expandedHitboxTurnSpeed = 62;
			expandedHitboxReactionMs = 35;
			configVersion = 67;
			changed = true;
		}
		if (configVersion < 68) {
			schematicBuildEnabled = false;
			configVersion = 68;
			changed = true;
		}
		if (configVersion < 69) {
			// Combat Modes are a non-destructive overlay. Existing standalone module
			// toggles and tuning are intentionally left untouched by this migration.
			combatMode = 0;
			swordModeTier = 3; // HT4
			axeModeTier = 2;   // LT4
			maceModeTier = 4;  // LT3

			swordModeAim = true;
			swordModeStrafe = true;
			swordModeTrigger = true;
			swordModeAiBot = false;
			swordModeAiAim = true;
			swordModeAiJump = true;

			axeModeAim = true;
			axeModeStun = true;
			axeModeProjectileAim = true;
			axeModeBowAim = true;
			axeModeCrossbowAim = true;
			axeModeSwordFollowup = true;
			axeModeTriggerFollowup = true;

			maceModeAutoMace = true;
			maceModeAim = true;
			maceModeBreachSwap = true;
			maceModeStunSlam = true;
			configVersion = 69;
			changed = true;
		}
		if (configVersion < 70) {
			// Sword mode owns a conservative sprint-key layer. It never supplies forward
			// movement and yields to AI Bot/manual retreat, sneak, unsafe footing and every
			// vanilla sprint restriction.
			swordModeAutoSprint = true;
			configVersion = 70;
			changed = true;
		}
		if (configVersion < 71) {
			guiAutoScale = true;
			guiScalePct = 100;
			configVersion = 71;
			changed = true;
		}
		if (configVersion < 72) {
			// Auto Schem Build v72 gains a physical, vanilla-input navigation layer.
			// It is intentionally on by default; switching it off preserves the original
			// manual hover-to-place behavior exactly.
			schematicAutoMove = true;
			configVersion = 72;
			changed = true;
		}
		if (configVersion < 73) {
			// Full Bright is opt-in. Existing profiles receive the balanced slider
			// position without unexpectedly changing their lighting on upgrade.
			fullBrightEnabled = false;
			fullBrightLevel = 7;
			configVersion = 73;
			changed = true;
		}
		if (configVersion < 74) {
			schematicTemporaryBlocks = true;
			configVersion = 74;
			changed = true;
		}
		if (configVersion < 75) {
			maceModeAutoSwitch = true;
			autoMaceAutoSwitch = true;
			autoCrystalStrictRay = true;
			configVersion = 75;
			changed = true;
		}
		if (configVersion < 76) {
			// Move only the former default. Explicitly customized settle values
			// remain untouched.
			if (autoMaceSettleMs == 70) autoMaceSettleMs = 35;
			configVersion = 76;
			changed = true;
		}
		if (configVersion < 77) {
			// Auto Move remains implemented internally, but is no longer exposed or
			// active. Force old saved profiles off so removing its toggle cannot
			// leave invisible movement ownership behind.
			schematicAutoMove = false;
			configVersion = 77;
			changed = true;
		}
		if (configVersion < 78) {
			// Auto Move returns with its own toggle, an interior-first placement
			// order, and repeated verify sweeps. Hover-to-place can only ever fill
			// what the crosshair can already see, so a thick or wide schematic
			// needs this on to finish; v77 profiles are restored to it.
			schematicAutoMove = true;
			configVersion = 78;
			changed = true;
		}
		if (configVersion < 79) {
			// Keep target validation and a bounded rate on old profiles. Auto Clicker
			// now owns an autonomous stream whenever enabled; the former Hold setting
			// has been retired and old JSON values are intentionally ignored.
			instantClickTargetOnly = true;
			instantClickCps = MathHelper.clamp(instantClickCps, 1, 20);
			configVersion = 79;
			changed = true;
		}
		if (configVersion < 80) {
			// Replace the old hidden-pitch/end-tick Lunge with a visible, fully
			// charged pre-movement jab and an optional target-scoped mace handoff.
			lungeAim = true;
			lungeFov = 55;
			lungeRange = 14;
			lungeTurnSpeed = 70;
			lungeSpearMace = true;
			lungeShieldBreak = true;
			// Spear Charge Assist was replaced by Auto Spear in v95.
			configVersion = 80;
			changed = true;
		}
		if (configVersion < 81) {
			// Previous-slot restoration is now explicit. Keep it off on upgrade so
			// Axe Stun never fights a player's post-hit slot choice unexpectedly.
			axeStunRestorePrevious = false;
			configVersion = 81;
			changed = true;
		}
		if (configVersion < 82) {
			// Autonomous means the click rhythm is observable immediately after the
			// module is enabled. Target Only remains available as an opt-in filter.
			instantClickTargetOnly = false;
			configVersion = 82;
			changed = true;
		}
		if (configVersion < 83) {
			// Anchor Tweaks became a focused Anchor Macro. Legacy JSON is read
			// through @SerializedName, and this save drops the retired cover,
			// detonation, rotation, aggression, and blast-policy settings.
			configVersion = 83;
			changed = true;
		}
		if (configVersion < 84) {
			// The classic sidebar panel was removed; the column panel is the only UI, and its
			// settings live behind the wordmark. The retired guiExperimental key is simply
			// ignored on load.
			configVersion = 84;
			changed = true;
		}
		if (configVersion < 85) {
			// Replace the old one-size Scaffold/AutoCrystal/Anchor surfaces with the
			// recovered client's explicit mode layouts. Existing enable toggles survive.
			scaffoldMode = 0;
			scaffoldBlockCount = false;
			scaffoldPitchCheck = false;
			scaffoldPitch = 45;
			scaffoldBlacklist = true;
			scaffoldWhitelist = false;
			scaffoldSneakDelayMinMs = 100;
			scaffoldSneakDelayMaxMs = 200;
			scaffoldRequireSneak = false;
			scaffoldGodActivationBlocks = 2;
			scaffoldTellyRequireRightClick = true;
			scaffoldTellyActivationBlocks = 2;
			scaffoldTellyYIncrease = 1;
			crystalAuraMode = 0;
			crystalTargetMode = 0;
			crystalRangeTenths = 45;
			crystalMaxAngle = 120;
			crystalAimSpeedTenths = 45;
			crystalMinEfficiency = 50;
			crystalDelayMinMs = 50;
			crystalDelayMaxMs = 150;
			crystalAntiSuicide = true;
			crystalMaxSelfDamage = 19;
			crystalOptimization = 0;
			crystalRapidMinEfficiency = 50;
			crystalPredictAttackVelocity = true;
			crystalAutoObsidian = false;
			crystalCenterScreen = true;
			crystalShowTarget = false;
			crystalManualPlaceObsidian = false;
			crystalManualShowTargetBlock = true;
			anchorMode = 1;
			anchorSafe = false;
			anchorExplosionItemWhitelist = false;
			anchorAimAssist = true;
			anchorSilentAim = true;
			anchorAimSpeedTenths = 120;
			anchorDelayMinMs = 50;
			anchorDelayMaxMs = 100;
			configVersion = 85;
			changed = true;
		}
		if (configVersion < 86) {
			// Complete the recovered client's utility surfaces. Fast Place existed as
			// an orphaned flag; AutoTool and Velocity only exposed their basic scales.
			velocityTicks = 0;
			velocityKiteMode = false;
			velocityAlwaysKite = false;
			velocityKiteHorizontal = 120;
			velocityKiteVertical = 120;
			velocityOnlyWhenTargeting = false;
			velocityWaterCheck = false;
			instantAutoToolSwapToDelayMs = 50;
			instantAutoToolSwapWeapon = true;
			instantAutoToolInstantWeapon = true;
			instantAutoToolSwapBack = false;
			instantAutoToolSwapBackDelayMs = 350;
			instantAutoToolRequireMouseDown = true;
			instantAutoToolOnlySneaking = false;
			instantFastPlaceHeldItem = 0;
			instantFastPlaceDelay = 1;
			configVersion = 86;
			changed = true;
		}
		if (configVersion < 87) {
			autoPotType = 0;
			autoPotMode = 0;
			autoPotHealth = 10;
			autoPotRandom = false;
			instantClickMinCps = Math.min(6, instantClickCps);
			instantClickHoldToClick = false;
			instantClickRandomization = 1;
			instantClickJitter = false;
			instantClickLimitItems = false;
			instantClickBreakBlocks = false;
			instantClickBreakBlocksDelayMs = 0;
			inventoryAutoArmor = false;
			inventoryChestSteal = false;
			inventoryRefillRequested = false;
			inventoryAutoHotbar = false;
			inventoryCleanerRequested = false;
			hypixelBedBreaker = false;
			configVersion = 87;
			changed = true;
		}
		if (configVersion < 88) {
			// Network and World module groups were removed. Saving v88 drops their retired
			// JSON keys; Bed Breaker survives under its Hypixel-specific serialized name.
			configVersion = 88;
			changed = true;
		}
		if (configVersion < 89) {
			// Anchor confirmation now gates every action, so the former 50-100 ms padding
			// only made charge-to-detonation sluggish. Keep a small configurable jitter.
			anchorDelayMinMs = 0;
			anchorDelayMaxMs = 25;
			configVersion = 89;
			changed = true;
		}
		if (configVersion < 90) {
			// New Combat → Auto XP. Off by default; it only ever fires on damaged Mending armour.
			autoXpEnabled = false;
			autoXpDelayMs = 200;
			configVersion = 90;
			changed = true;
		}
		if (configVersion < 91) {
			// New Inventory → Auto Sign and Misc → RTP Finder. Both off until configured.
			autoSignEnabled = false;
			autoSignLine1 = "";
			autoSignLine2 = "";
			autoSignLine3 = "";
			autoSignLine4 = "";
			rtpFinderEnabled = false;
			rtpFinderTargetX = 0;
			rtpFinderTargetZ = 0;
			rtpFinderRadius = 1;
			rtpFinderCommand = "/rtp";
			rtpFinderIntervalMs = 1000;
			configVersion = 91;
			changed = true;
		}
		if (configVersion < 92) {
			// Auto Totem now maintains the offhand continuously instead of
			// reacting once to the pop packet. Silent swap becomes the default:
			// the visible-inventory path locks the player's own movement and
			// clicks for as long as the screen is up, which is the worst thing
			// to do in the fight that just cost a totem. The GUI mode is still
			// available for anyone who prefers to see the refill happen.
			totemOpenInventory = false;
			maceSilentAim = false;
			lungeSpamScaling = true;
			hitCritSprintRelease = true;
			configVersion = 92;
			changed = true;
		}
		if (configVersion < 93) {
			// The ESP split. Storage detection leaves Advanced ESP and becomes its
			// own module, so Advanced ESP is now only about terrain — and gains a
			// staircase detector to match the name it is given in the UI.
			donutStorageEsp = false;
			donutStorageEspRange = 128;
			donutStorageEspOpacity = 22;
			donutStorageShowChests = true;
			donutStorageShowShulkers = true;
			donutStorageShowRedstone = true;
			donutStorageShowFurnaces = true;
			donutAdvancedShowStairs = true;
			// Off by default: it is meant to be rare and acted on, not left on as
			// background decoration.
			donutSuspiciousChunks = false;
			donutSuspiciousChunksRange = 256;
			donutSuspiciousChunksCeiling = 8;
			donutSuspiciousChunksLabels = true;
			configVersion = 93;
			changed = true;
		}
		if (configVersion < 94) {
			// Auto Lunge became Auto Lunge Swap: the burst is now an attribute
			// swap rather than a held-spear jab, so its old target-aiming
			// settings no longer describe anything it does.
			lungeSwapHumanize = true;
			lungeSpamScaling = true;
			lungeSwapJump = true;
			configVersion = 94;
			changed = true;
		}
		if (configVersion < 95) {
			// Spear Charge Assist only aimed while you held a charge yourself.
			// Auto Spear replaces it: it decides when to start the charge so a
			// fly-through actually lands, which is the part that needed timing.
			autoSpearEnabled = false;
			autoSpearFov = 75;
			autoSpearRange = 42;
			autoSpearTurnSpeed = 48;
			// Silent aim decouples the camera; it must never arrive switched on.
			autoSpearAutoSwitch = true;
			autoSpearSilentAim = false;
			configVersion = 95;
			changed = true;
		}
		if (configVersion < 98) {
			// Anchor Macro settles back to one sequence: place, charge, detonate,
			// stop. Double Anchor and the short-lived air place are both gone, so
			// nothing chains a second anchor and nothing clicks an empty cell.
			// Their fields are dropped rather than migrated — an absent field just
			// reads as the default, so an old config needs no rewriting.
			configVersion = 98;
			changed = true;
		}
		if (configVersion < 99) {
			// Auto Clicker clicks continuously once enabled, not only while the
			// crosshair rests on somebody. "Only on targets" stays as the way to
			// narrow it back down, but it is off unless it is asked for.
			instantClickTargetOnly = false;
			configVersion = 99;
			changed = true;
		}
		if (configVersion < 100) {
			// Auto Totem refills with the swap-hands key now, so it needs no screen
			// at all when the totem is in the hotbar. The inventory route survives
			// only as a fallback for a totem stored deeper than that.
			totemOpenInventory = true;
			configVersion = 100;
			changed = true;
		}
		return changed;
	}
}
