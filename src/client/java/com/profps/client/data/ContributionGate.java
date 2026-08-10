package com.profps.client.data;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.nova.NovaModules;
import net.minecraft.client.MinecraftClient;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a tick is real human play or something a module produced.
 *
 * <p>The corpus is worthless if module output leaks into it — a model trained on Triggerbot's
 * swings learns Triggerbot's timing, and a model trained on Freecam learns to fly. So the gate is
 * fail-safe by construction: it walks the live module catalogue, and any module it does not
 * explicitly recognise as harmless suppresses recording. A module added next year is excluded
 * until somebody deliberately classifies it, which is the correct direction for that mistake.
 *
 * <p>Scope keeps the filter from being all-or-nothing. Most modules touch movement, the view or
 * the network, and every row carries position and look angles, so those stop recording outright.
 * A few only reach one activity — AutoTool cannot influence how you strafe — and those suppress
 * just the ticks labelled with the activity they touch, so somebody with AutoTool on still
 * contributes their travelling and their fights.
 */
final class ContributionGate {
	/**
	 * What a module contaminates. These are independent axes, not a severity ladder — AutoTool and
	 * Triggerbot being on together has to suppress mining <em>and</em> fighting, so the gate
	 * accumulates a set rather than a maximum.
	 */
	enum Taint {
		/** Harmless: no action, no camera, no hidden knowledge. */
		NONE,
		/** Mining and building ticks only. */
		BLOCKS,
		/** Fighting ticks only. */
		COMBAT,
		/** Everything — the module reaches movement, the view, the network or world knowledge. */
		ALL
	}

	/**
	 * Anything absent from this map is {@link Taint#ALL}. Only modules that provably cannot reach
	 * movement, the camera, physics, netcode or hidden world knowledge get a narrower scope.
	 */
	private static final Map<String, Taint> SCOPES = Map.ofEntries(
			// Rendering and naming. No action, no information the player did not already have.
			Map.entry("fullbright", Taint.NONE),
			Map.entry("nickname", Taint.NONE),
			Map.entry("nickother", Taint.NONE),
			// Deliberate exception, chosen by the author. Unlike the entries above this one does
			// grant information — it draws player boxes through walls — so movement recorded under
			// it reflects a player who knew where people were. It draws nothing and sends nothing,
			// which is the line drawn here, but the corpus is not strictly unassisted because of it.
			Map.entry("hitboxes", Taint.NONE),
			// Reach only into breaking and placing.
			Map.entry("fastbreak", Taint.BLOCKS),
			Map.entry("autotool", Taint.BLOCKS),
			Map.entry("breakon", Taint.BLOCKS),
			Map.entry("fastplace", Taint.BLOCKS),
			Map.entry("autosign", Taint.BLOCKS),
			// Inventory and item handling: they change what is in hand mid-fight, nothing else.
			Map.entry("totem", Taint.COMBAT),
			Map.entry("fastuse", Taint.COMBAT),
			Map.entry("autoarmor", Taint.COMBAT),
			Map.entry("refill", Taint.COMBAT),
			Map.entry("autohotbar", Taint.COMBAT),
			Map.entry("cheststeal", Taint.COMBAT),
			Map.entry("invcleaner", Taint.COMBAT)
	);

	/**
	 * Ticks to keep suppressing after the last contaminated one. Knockback, momentum and a swing
	 * cooldown all outlive the module that caused them, and those trailing ticks look like ordinary
	 * play while being anything but.
	 */
	private static final int COOLDOWN_TICKS = 40;

	private final EnumSet<Taint> held = EnumSet.noneOf(Taint.class);
	private String reason;
	private int cooldown;

	/** Display name of whatever is suppressing recording, or null when nothing is. */
	String reason() {
		return reason;
	}

	/**
	 * Recomputes the taint for this tick. {@code overridden} comes from the recorder comparing the
	 * player's real keys against the input the body was handed — it catches anything driving
	 * movement that the catalogue scan somehow missed.
	 */
	void update(MinecraftClient client, ProFPSConfig config, boolean overridden) {
		EnumSet<Taint> found = EnumSet.noneOf(Taint.class);
		String blame = null;

		List<NovaModules.Category> categories = com.profps.client.ProFPSClient.novaCategories();
		if (categories != null) {
			for (NovaModules.Category category : categories) {
				for (NovaModules.Module module : category.modules) {
					Taint scope = scopeOf(module.id);
					if (scope == Taint.NONE || !isActive(module)) continue;
					found.add(scope);
					// Name the widest-reaching one, since that is the one worth turning off.
					if (blame == null || scope == Taint.ALL) blame = module.name;
				}
			}
		}

		if (overridden) {
			found.add(Taint.ALL);
			if (blame == null) blame = "a module driving your input";
		}

		if (!found.isEmpty()) {
			cooldown = COOLDOWN_TICKS;
			held.clear();
			held.addAll(found);
			reason = blame;
			return;
		}
		if (cooldown > 0) {
			// Hold the previous taint through the cooldown: knockback and momentum outlive the
			// module, and the status would otherwise flicker as one settles.
			cooldown--;
			return;
		}
		held.clear();
		reason = null;
	}

	/** Unknown ids are {@link Taint#ALL} on purpose: a new module is excluded until classified. */
	static Taint scopeOf(String id) {
		return SCOPES.getOrDefault(id, Taint.ALL);
	}

	/**
	 * A module counts as active when its toggle is on. Nothing else.
	 *
	 * <p>This used to also treat a held module keybind as active, to catch the momentary modules —
	 * they report {@code false} forever and fire straight off the key. It was wrong twice over. For
	 * an ordinary module the keybind just flips the config field this already reads, so it added
	 * nothing; for a momentary one the key being down only means the module was <em>asked</em> to
	 * fire, and it may decline — Auto Lunge on Left Alt read as permanently active without ever
	 * doing anything. Each false positive costs 40 ticks, so the check destroyed far more data than
	 * the momentary modules it was meant to catch could ever have contaminated.
	 */
	private static boolean isActive(NovaModules.Module module) {
		return Boolean.TRUE.equals(module.get.get());
	}

	/** Whether a tick with this activity label survives every taint currently in force. */
	boolean allows(String activity) {
		return allows(held, activity);
	}

	/**
	 * The decision on its own, so it can be tested without a running client. Each taint vetoes
	 * independently — two narrow taints together suppress both their activities rather than the
	 * wider one swallowing the narrower.
	 */
	static boolean allows(Set<Taint> taints, String activity) {
		if (taints.isEmpty()) return true;
		if (taints.contains(Taint.ALL)) return false;
		if (taints.contains(Taint.COMBAT) && ActivityClassifier.COMBAT.equals(activity)) return false;
		return !taints.contains(Taint.BLOCKS)
				|| (!ActivityClassifier.MINING.equals(activity)
				&& !ActivityClassifier.BUILDING.equals(activity));
	}
}
