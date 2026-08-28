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

	// Resolved lazily so this stays instantiable before Fabric has a game provider.
	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("profps.json");
	}

	private static Path profileDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("profps-configs");
	}

	public boolean enabled = true;

	// Combat Modes. One mode at a time: 0 Off, 1 Sword, 2 Axe, 3 Mace. Tier indices run in
	// display order: LT5, HT5, LT4, HT4, LT3, HT3, LT2, HT2, LT1, HT1.
	// These are an overlay over the standalone module fields below, not a rewrite of them.
	public int combatMode = 0;
	public int swordModeTier = 3; // HT4
	public int axeModeTier = 2;   // LT4
	public int maceModeTier = 4;  // LT3

	public boolean swordModeAim = true;
	public boolean swordModeStrafe = true;
	public boolean swordModeAutoSprint = true;
	public boolean swordModeTrigger = true;
	public boolean swordModeAiBot = false; // optional advanced layer; always off by default
	public boolean swordModeAiAim = true;
	public boolean swordModeAiJump = true;

	public boolean axeModeAim = true;
	public boolean axeModeStun = true;
	public boolean axeModeCrit = true;
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

	// Reach extends entity targeting and the attack-range gate. Stored in hundredths of a block.
	// Vanilla item ATTACK_RANGE is ~3.0 and server-authoritative; Grim's own margins reach ~3.05.
	public boolean reach = false;
	public int reachCm = 300;             // 300 = 3.00 blocks; max 600

	// Expanded Hitbox is an acquisition margin only; the attack waits for a ray into the real
	// box. Amount is stored in hundredths of a block.
	public boolean expandedHitbox = false;
	public int expandedHitboxAmountCm = 18;
	public int expandedHitboxTurnSpeed = 62;
	public int expandedHitboxReactionMs = 35;

	// Velocity scales server-sent knockback. Defaults are gentle to stay inside Grim's tolerance.
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
	/** Aims the body while the camera stays under the player's own mouse. Rotations are not spoofed. */
	public boolean maceSilentAim = false;
	public boolean autoMaceShieldBreak = true; // stun-slam: axe-tap to break the shield, then mace-smash
	public int autoMaceShieldBreakMs = 60;     // gap between the axe hit and the mace smash, about 1 server tick

	// Standalone ground shield-breaker: swap to the axe, break the shield, swap back.
	public boolean axeStun = false;
	public int axeStunReactionMs = 110;
	public int axeStunSwitchBackMs = 90;  // gap between the axe hit and swapping back, at least 1 server tick
	public boolean axeStunRestorePrevious = false;

	// Vanilla needs the descent, over 0.9 charge and no sprint for the 1.5x crit.
	public boolean axeCrit = false;

	// Visible sword to Breach-mace handoff on a crit descent.
	public boolean autoBreachSwap = false;
	public int autoBreachSwapCharge = 90;      // required mace attack-charge %; vanilla crits above 90

	// Pearl Catch is retired; CombatModePolicy gates it off. These remain so the controller compiles.
	public int pearlCatchDelayMs = 0;
	public int pearlCatchAngle = 0;            // degrees below the intercept; 0 aims straight at it
	public int pearlCatchAimSpeed = 80;

	public boolean autoAim = false;       // projectile aim assist for bow/crossbow/fireball
	public int autoAimStrength = 45;
	public int autoAimFov = 70;           // acquisition cone in degrees

	// Hitboxes overlay. RGB is stored as 0..255; opacity as a percentage.
	public boolean hitboxes = false;
	public int hitboxRed = 255;
	public int hitboxGreen = 140;
	public int hitboxBlue = 31;
	public int hitboxOutlineOpacity = 95;
	public int hitboxFillOpacity = 14;
	public int hitboxLineWidth = 2;

	// SubTiers
	public boolean subTiersAutoBed = false;
	public boolean subTiersAutoCreeper = false;
	public boolean subTiersAutoMinecart = false;
	public int subTiersMinecartBowSpeed = 6;

	/** Client-side held-item swing slowdown; server timing is unchanged. */
	public boolean slowAnimations = false;
	public int slowAnimationStrength = 4;

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
	public boolean instamineEnabled = false;
	/** 1 is a modest boost, 10 breaks in a single tick with no post-break gap. */
	public int instamineLevel = 8;
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

	// Inventory automation
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

	// Extra Assists
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
	public int scaffoldSpeed = 7; // 0 slowest .. 10 fastest
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
	public boolean schematicBuildEnabled = false; // place the crosshair cell from Remember or an enabled Litematica placement
	/** Placement pace 1-10; 5 matches vanilla's held-click cadence, 10 is quickest. */
	public int schematicBuildSpeed = 10;
	/** Retained only to read older config files; the crosshair builder no longer moves the player. */
	public boolean schematicAutoMove = true;
	/** Retained only to read older config files; temporary support scaffolding is gone. */
	public boolean schematicTemporaryBlocks = true;
	public boolean swordAiEnabled = false;     // distilled local movement model for sword combat
	public boolean swordAiAim = true;          // let the AI turn your view (pitch + yaw) onto the target
	public boolean swordAiJump = true;         // let the AI jump for crits + occasional movement (never constant)

	// Classics
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
	// NovaClient skin
	public String  novaSkinUsername = "";       // Configure: wear this player's skin everywhere YOU see (client-side)

	// Nickname
	public boolean nicknameEnabled = false;
	public String nicknameSelfName = "Marlowww"; // the name shown everywhere instead of your real one
	public boolean nicknameSelfSkin = true;       // also wear a skin (on by default)
	public String nicknameSelfSkinFrom = "";       // whose skin to wear (blank = your nickname's own skin)
	// Nick Other
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
	public boolean anchorExplosionItemWhitelist = false;
	public java.util.List<String> anchorExplosionItems = new java.util.ArrayList<>(java.util.List.of("minecraft:totem_of_undying"));
	/** Anchor action speed: 1 deliberate, 10 immediate. */
	public int anchorSpeed = 6;
	public boolean totemTweaks = false;
	/** Fallback for a totem outside the hotbar; the normal refill uses the swap-hands key. */
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
	public boolean fastUse = false;
	public boolean fastUseExpanded = false;
	public int fastUseLevel = 6;

	public int donutCategoryX = 314;
	public int donutCategoryY = 28;
	public boolean donutCategoryCollapsed = false;
	public boolean donutBasicEsp = false;
	public boolean donutAdvancedEsp = false;   // "Hole/Tunnel/Stairs ESP" in the UI; terrain cavities only
	public boolean donutAdvancedShowStairs = true;
	public transient boolean donutAdvancedEspReloadRequested = false; // momentary UI "Reload" signal (not persisted)
	public transient boolean autoLungeRequested = false; // momentary Auto Lunge keybind/click signal (not persisted)

	// Knockback Displacement: momentary, keybind-fired sprint-reset displacement hit.
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
	/** Sprint-jump into the burst; ground friction scrubs the velocity off almost at once. */
	public boolean lungeSwapJump = true;
	/** Holds the kinetic charge and aims it at whoever is close enough to hit. */
	public boolean autoSpearEnabled = false;
	public int autoSpearFov = 90;
	/** Contact happens at 2 to 4.5m; the range is how early the spear comes out, not its reach. */
	public int autoSpearRange = 20;
	public int autoSpearTurnSpeed = 55;
	/** Pull the spear from the hotbar for the run in, and put the old slot back after. */
	public boolean autoSpearAutoSwitch = true;
	/** Aim the body while the camera stays under the player's own mouse. */
	public boolean autoSpearSilentAim = false;
	/** Storage ESP: containers and redstone machinery, independent of Hole/Tunnel/Stairs ESP. */
	public boolean donutStorageEsp = false;
	public int donutStorageEspRange = 128;
	public int donutStorageEspOpacity = 22;    // fill alpha percent; outlines stay near-opaque
	public boolean donutStorageShowChests = true;
	public boolean donutStorageShowShulkers = true;
	public boolean donutStorageShowRedstone = true;
	public boolean donutStorageShowFurnaces = true;
	public boolean donutStorageEspExpanded = false;
	/** Prime Chunk Finder: flags chunks with under-render base evidence. */
	public boolean donutPrimeChunk = false;
	public int donutPrimeChunkWeight = 40;  // flag sensitivity ×100 → 0.0 (strict) .. 1.0 (loose)
	public int donutPrimeChunkRange = 256;
	public boolean donutPrimeChunkTracers = true;
	public boolean donutPrimeChunkExpanded = false;
	/** Stash Pinger: base classifier with toast/action-bar pings and tracers. */
	public boolean donutStashPinger = false;
	public int donutStashTemperature = 45; // flag sensitivity ×100 → 0.0 (strict) .. 1.0 (loose)
	public int donutStashRange = 256;
	public boolean donutStashTracers = true;
	public boolean donutStashPingerExpanded = false;
	public boolean donutAmethystDetector = false;
	/** Freecam: detached flying camera; forced off at startup by its controller. */
	public boolean donutFreecam = false;
	public int donutFreecamSpeed = 40; // stored ×10 → 0.1..10.0 flight speed, 40 = the 4.0 default
	public boolean donutFreecamTurbo = false; // ×2.5 on top of the slider
	public boolean donutTunnel = false;
	public boolean donutTunnelExpanded = false;
	public boolean donutBasicEspExpanded = false;
	public boolean donutAdvancedEspExpanded = false;
	public boolean donutAmethystDetectorExpanded = false;
	public int donutBasicEspRange = 192;
	public int donutAdvancedEspRange = 192;
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

	/** GLFW key code per module id, assigned from the Nova GUI keybind rows. */
	public Map<String, Integer> moduleKeybinds = new HashMap<>();

	// GUI theme and preferences.
	public int guiAccent = 0;            // accent-colour preset index; 0 = Ice Blue
	public boolean guiAnimations = true;
	public boolean guiGlow = true;
	public boolean guiSounds = true;
	public boolean guiAutoScale = true;  // fit the Nova layout to the current GUI viewport
	public int guiScalePct = 100;         // manual size request; the viewport fit ceiling still wins

	public boolean hudModuleList = true;
	public boolean hudModuleListRight = false; // false = left edge, true = right edge

	// Master switch for the in-GUI packet toolkit. The live send/delay/desync toggles are
	// session state on PacketManager, not persisted here.
	public boolean packetUtils = false;

	// Anonymous per-tick movement telemetry, uploaded in batches.
	public boolean dataContribution = true;
	/** Adds absolute world coordinates, dimension and server address to the uploaded motion. */
	public boolean dataContributionLocation = true;
	/** Ingest host. Must be a hostname, not a bare IP, so it can terminate TLS. */
	public String dataContributionEndpoint = "https://ingest.goatmath.org";
	/** Random per-install salt; the uploaded pseudonym is a hash of the account UUID and this salt. */
	public String dataContributionSalt = "";

	public int configVersion = 114;

	/** Plain HTTP is accepted only for loopback. */
	static boolean isLoopback(String endpoint) {
		return endpoint.startsWith("http://127.0.0.1")
				|| endpoint.startsWith("http://localhost")
				|| endpoint.startsWith("http://[::1]");
	}

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

	// Named config profiles
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

	/** Loads a saved profile into this instance and persists it. Returns true on success. */
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

	/** Resets every setting to defaults on this instance and persists. */
	public void resetToDefaults() {
		copyFrom(new ProFPSConfig());
		save();
	}

	/** Copies every persisted field from {@code other} into this instance by reflection. */
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
		int oldAnchorSpeed = anchorSpeed;
		anchorSpeed = MathHelper.clamp(anchorSpeed, 1, 10);
		changed |= oldAnchorSpeed != anchorSpeed;
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
		if (autoSpearRange < 4 || autoSpearRange > 64) {
			autoSpearRange = MathHelper.clamp(autoSpearRange, 4, 64);
			changed = true;
		}
		if (autoSpearTurnSpeed < 20 || autoSpearTurnSpeed > 90) {
			autoSpearTurnSpeed = MathHelper.clamp(autoSpearTurnSpeed, 20, 90);
			changed = true;
		}
		if (schematicBuildSpeed < 1 || schematicBuildSpeed > 10) {
			schematicBuildSpeed = MathHelper.clamp(schematicBuildSpeed, 1, 10);
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
		if (donutStorageEspRange < 32 || donutStorageEspRange > 512) {
			donutStorageEspRange = MathHelper.clamp(donutStorageEspRange, 32, 512);
			changed = true;
		}
		if (donutStorageEspOpacity < 5 || donutStorageEspOpacity > 60) {
			donutStorageEspOpacity = MathHelper.clamp(donutStorageEspOpacity, 5, 60);
			changed = true;
		}
		if (donutAmethystDetectorRange < 48 || donutAmethystDetectorRange > 1024) {
			donutAmethystDetectorRange = MathHelper.clamp(donutAmethystDetectorRange, 48, 1024);
			changed = true;
		}
		if (donutFreecamSpeed < 1 || donutFreecamSpeed > 100) {
			donutFreecamSpeed = MathHelper.clamp(donutFreecamSpeed, 1, 100);
			changed = true;
		}
		if (donutPrimeChunkWeight < 0 || donutPrimeChunkWeight > 100) {
			donutPrimeChunkWeight = MathHelper.clamp(donutPrimeChunkWeight, 0, 100);
			changed = true;
		}
		if (donutPrimeChunkRange < 48 || donutPrimeChunkRange > 1024) {
			donutPrimeChunkRange = MathHelper.clamp(donutPrimeChunkRange, 48, 1024);
			changed = true;
		}
		if (donutStashTemperature < 0 || donutStashTemperature > 100) {
			donutStashTemperature = MathHelper.clamp(donutStashTemperature, 0, 100);
			changed = true;
		}
		if (donutStashRange < 48 || donutStashRange > 1024) {
			donutStashRange = MathHelper.clamp(donutStashRange, 48, 1024);
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
			configVersion = 14;
			changed = true;
		}
		if (configVersion < 15) {
			donutBasicEspRange = 192;
			donutAdvancedEspRange = 192;
			donutBasicShowPlayers = true;
			donutBasicShowMonsters = true;
			donutBasicShowPassive = true;
			donutBasicShowAquatic = true;
			donutAdvancedShowTunnels = true;
			donutAdvancedShowPockets = true;
			donutAdvancedShowShafts = true;
			donutAdvancedShowPlaced = true;
			donutAdvancedShowSpawners = true;
			configVersion = 15;
			changed = true;
		}
		if (configVersion < 16) {
			configVersion = 16;
			changed = true;
		}
		if (configVersion < 17) {
			donutAmethystDetector = false;
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
			configVersion = 19;
			changed = true;
		}
		if (configVersion < 20) {
			autoCrystalSpeed = 10;
			configVersion = 20;
			changed = true;
		}
		if (configVersion < 21) {
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
			configVersion = 24;
			changed = true;
		}
		if (configVersion < 25) {
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
		if (instamineLevel < 1 || instamineLevel > 10) {
			instamineLevel = MathHelper.clamp(instamineLevel, 1, 10);
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
			instamineEnabled = false;
			instantAutoTool = false;
			instantFastPlace = false;
			instantAutoSprint = false;
			instantAutoWalk = false;
			instantClickCps = 12;
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
			configVersion = 31; // scaffold timing migration, superseded by the Speed meter at v34
			changed = true;
		}
		if (configVersion < 32) {
			configVersion = 32; // scaffold auto-look was added then removed
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
			// Backstep became Backward: the juke now includes a real back step and defaults on.
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
			autoMaceShieldBreak = true;   // axe-break a held-up shield before the mace
			autoMaceShieldBreakMs = 60;
			autoBreachSwap = false;       // advanced module, off by default
			autoBreachSwapCharge = 90;
			configVersion = 48;
			changed = true;
		}
		if (configVersion < 49) {
			// The custom home screen was removed.
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
			// Replace the full-negate Velocity default with a gentler one.
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
			// Move existing configs off the full-tick Auto Crystal speed unless already lowered.
			if (autoCrystalSpeed > 4) autoCrystalSpeed = 4;
			configVersion = 55;
			changed = true;
		}
		if (configVersion < 56) {
			configVersion = 56;
			changed = true;
		}
		if (configVersion < 57) {
			configVersion = 57;
			changed = true;
		}
		if (configVersion < 58) {
			// Triggerbot v58 adds acquisition range, weapon-aware cooldown, sneak pause and an axe post-delay.
			hitDisableWhileSneaking = true;
			hitSprintAwareCooldown = true;
			if (hitReactionMs == 110) hitReactionMs = 95; // migrate the old default only
			hitReactionMinMs = Math.min(20, hitReactionMs);
			hitAxePostDelayMs = 120;
			configVersion = 58;
			changed = true;
		}
		if (configVersion < 59) {
			// Hitboxes became a configurable overlay; preserve the old orange look.
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
			// Scaffold v61 adds diagonal/air bridging, zig-zag movement and jump assists.
			scaffoldSneakOnly = false;
			configVersion = 61;
			changed = true;
		}
		if (configVersion < 62) {
			// Move existing configs from Auto Minecart bow level 4 to level 6.
			subTiersMinecartBowSpeed = 6;
			configVersion = 62;
			changed = true;
		}
		if (configVersion < 63) {
			// Scaffold lost its aim subsystem; this rewrites old configs without the retired setting.
			configVersion = 63;
			changed = true;
		}
		if (configVersion < 64) {
			// Scaffold v64 is placement-only; the old movement-assist settings are retired.
			configVersion = 64;
			changed = true;
		}
		if (configVersion < 65) {
			// Triggerbot v65: only values equal to the previous 95% default follow the new default.
			if (hitCooldownPct == 95) hitCooldownPct = 100;
			configVersion = 65;
			changed = true;
		}
		if (configVersion < 66) {
			// Triggerbot v66: only values equal to the former defaults move to the faster profile.
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
			// Combat Modes are an overlay; standalone module fields are left untouched.
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
			// Sword mode gains a sprint-key layer that never supplies forward movement.
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
			// v72 added Auto Move to Auto Schem Build; the feature is gone, so this only carries the version forward.
			configVersion = 72;
			changed = true;
		}
		if (configVersion < 73) {
			// Full Bright is opt-in; existing profiles get the balanced slider position.
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
			configVersion = 75;
			changed = true;
		}
		if (configVersion < 76) {
			// Move only the former default settle value; customized values stay.
			if (autoMaceSettleMs == 70) autoMaceSettleMs = 35;
			configVersion = 76;
			changed = true;
		}
		if (configVersion < 77) {
			configVersion = 77;
			changed = true;
		}
		if (configVersion < 78) {
			// v77/v78 toggled Auto Move; the feature is gone, so these only carry the version forward.
			configVersion = 78;
			changed = true;
		}
		if (configVersion < 79) {
			// Auto Clicker is autonomous when enabled; the old Hold setting is retired.
			instantClickTargetOnly = true;
			instantClickCps = MathHelper.clamp(instantClickCps, 1, 20);
			configVersion = 79;
			changed = true;
		}
		if (configVersion < 80) {
			// Lunge becomes a visible pre-movement jab with an optional mace handoff.
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
			// Axe Stun previous-slot restoration is now explicit; keep it off on upgrade.
			axeStunRestorePrevious = false;
			configVersion = 81;
			changed = true;
		}
		if (configVersion < 82) {
			// Auto Clicker runs on enable; Target Only stays as an opt-in filter.
			instantClickTargetOnly = false;
			configVersion = 82;
			changed = true;
		}
		if (configVersion < 83) {
			// Anchor Tweaks became Anchor Macro; the retired cover, detonation, rotation, aggression and blast-policy settings are dropped.
			configVersion = 83;
			changed = true;
		}
		if (configVersion < 84) {
			// The classic sidebar panel was removed; the retired guiExperimental key is ignored on load.
			configVersion = 84;
			changed = true;
		}
		if (configVersion < 85) {
			// Scaffold, Auto Crystal and Anchor gain explicit mode layouts; existing enable toggles survive.
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
			anchorExplosionItemWhitelist = false;
			configVersion = 85;
			changed = true;
		}
		if (configVersion < 86) {
			// Add the Fast Place, AutoTool and Velocity settings that had no surface.
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
			// Network and World module groups removed; Bed Breaker keeps its Hypixel serialized name.
			configVersion = 88;
			changed = true;
		}
		if (configVersion < 89) {
			// Anchor confirmation gates every action; the raw delay fields became one speed control in v105.
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
			// Auto Totem maintains the offhand continuously; silent swap is the default.
			maceSilentAim = false;
			lungeSpamScaling = true;
			hitCritSprintRelease = true;
			configVersion = 92;
			changed = true;
		}
		if (configVersion < 93) {
			// Storage detection splits out of Advanced ESP, which gains a staircase detector.
			donutStorageEsp = false;
			donutStorageEspRange = 128;
			donutStorageEspOpacity = 22;
			donutStorageShowChests = true;
			donutStorageShowShulkers = true;
			donutStorageShowRedstone = true;
			donutStorageShowFurnaces = true;
			donutAdvancedShowStairs = true;
			configVersion = 93;
			changed = true;
		}
		if (configVersion < 94) {
			// Auto Lunge became Auto Lunge Swap; its old target-aiming settings are retired.
			lungeSwapHumanize = true;
			lungeSpamScaling = true;
			lungeSwapJump = true;
			configVersion = 94;
			changed = true;
		}
		if (configVersion < 95) {
			// Auto Spear replaces Spear Charge Assist and decides when to start the charge.
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
			// Anchor Macro settles to one sequence: place, charge, detonate, stop. Retired fields are dropped, not migrated.
			configVersion = 98;
			changed = true;
		}
		if (configVersion < 99) {
			// Auto Clicker clicks continuously once enabled; Only on targets is off unless asked for.
			instantClickTargetOnly = false;
			configVersion = 99;
			changed = true;
		}
		if (configVersion < 100) {
			// Auto Totem refills with the swap-hands key; the inventory route is a fallback.
			configVersion = 100;
			changed = true;
		}
		if (configVersion < 101) {
			// The inventory fallback is no longer optional, so the field is dropped rather than migrated.
			configVersion = 101;
			changed = true;
		}
		if (configVersion < 102) {
			// Auto Crystal is place-and-break off the live crosshair; the strict-ray option is retired.
			configVersion = 102;
			changed = true;
		}
		if (configVersion < 103) {
			// Data contribution arrives on, both switches, changeable on the Data page.
			configVersion = 103;
			changed = true;
		}
		if (configVersion < 104) {
			// Repoint existing installs off the placeholder collector hostname, which load() would otherwise keep.
			dataContributionEndpoint = new ProFPSConfig().dataContributionEndpoint;
			configVersion = 104;
			changed = true;
		}
		if (configVersion < 105) {
			// Anchor speed 10 could charge and detonate in one tick; start existing profiles lower.
			anchorSpeed = 6;
			configVersion = 105;
			changed = true;
		}
		if (configVersion < 106) {
			// The old visible aim-assist path and its rotation-speed setting are retired.
			configVersion = 106;
			changed = true;
		}
		if (configVersion < 107) {
			// The manual-crosshair path stopped changing camera or packet rotation.
			configVersion = 107;
			changed = true;
		}
		if (configVersion < 108) {
			// Glowstone cover is now the single Anchor Macro behavior; existing profiles start with detonation on.
			anchorDetonate = true;
			configVersion = 108;
			changed = true;
		}
		if (configVersion < 109) {
			// Anchor rotation handling removed; actions use the real crosshair and vanilla doItemUse.
			configVersion = 109;
			changed = true;
		}
		if (configVersion < 110) {
			// Move existing saves from Freecam speed 1.0 to the new 4.0 base.
			donutFreecamSpeed = 40;
			configVersion = 110;
			changed = true;
		}
		if (configVersion < 111) {
			// Auto Move returns and is enabled for old profiles, including the v77 ones that had it forced off.
			schematicAutoMove = true;
			configVersion = 111;
			changed = true;
		}
		if (configVersion < 112) {
			// Auto Spear rebuilt: it holds the charge whenever an opponent is near, aims at them, and arms at 20m instead of 42m.
			autoSpearFov = 90;
			autoSpearRange = 20;
			autoSpearTurnSpeed = 55;
			// Silent aim decouples the camera; it must never arrive switched on.
			autoSpearSilentAim = false;
			configVersion = 112;
			changed = true;
		}
		if (configVersion < 113) {
			// Schematic Build defaults to its quickest pace.
			schematicBuildSpeed = 10;
			configVersion = 113;
			changed = true;
		}
		if (configVersion < 114) {
			// Fastbreak became Instamine: it targets a tick count and clears the post-break
			// cooldown rather than scaling the vanilla rate, so the old level does not carry
			// over. The module stays off until it is asked for.
			instamineEnabled = false;
			instamineLevel = 8;
			configVersion = 114;
			changed = true;
		}

		// HTTPS or loopback only; loopback is exempt so a local collector can be tested.
		if (dataContributionEndpoint == null
				|| !(dataContributionEndpoint.startsWith("https://") || isLoopback(dataContributionEndpoint))) {
			dataContributionEndpoint = new ProFPSConfig().dataContributionEndpoint;
			changed = true;
		}
		while (dataContributionEndpoint.endsWith("/")) {
			dataContributionEndpoint = dataContributionEndpoint.substring(0, dataContributionEndpoint.length() - 1);
			changed = true;
		}
		if (dataContributionSalt == null || dataContributionSalt.length() < 32) {
			// Rolled once per install, then left alone.
			dataContributionSalt = java.util.UUID.randomUUID().toString().replace("-", "")
					+ java.util.UUID.randomUUID().toString().replace("-", "");
			changed = true;
		}
		return changed;
	}
}
