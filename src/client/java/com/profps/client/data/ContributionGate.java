package com.profps.client.data;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.nova.NovaModules;
import net.minecraft.client.MinecraftClient;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decides whether a tick is real human play or something a module produced. */
final class ContributionGate {
	/** What a module contaminates. Independent axes, not a severity ladder; the gate accumulates a set. */
	enum Taint {
		/** No action, no camera, no hidden knowledge. */
		NONE,
		/** Mining and building ticks only. */
		BLOCKS,
		/** Fighting ticks only. */
		COMBAT,
		/** Movement, view, network or world knowledge. */
		ALL
	}

	/** Anything absent from this map is {@link Taint#ALL}. */
	private static final Map<String, Taint> SCOPES = Map.ofEntries(
			// Rendering and naming only.
			Map.entry("fullbright", Taint.NONE),
			Map.entry("nickname", Taint.NONE),
			Map.entry("nickother", Taint.NONE),
			// Exception: draws player boxes through walls, so recorded movement reflects that knowledge.
			Map.entry("hitboxes", Taint.NONE),
			// Reach only into breaking and placing.
			Map.entry("instamine", Taint.BLOCKS),
			Map.entry("autotool", Taint.BLOCKS),
			Map.entry("breakon", Taint.BLOCKS),
			Map.entry("fastplace", Taint.BLOCKS),
			Map.entry("autosign", Taint.BLOCKS),
			// Inventory and item handling: change what is in hand mid-fight, nothing else.
			Map.entry("totem", Taint.COMBAT),
			Map.entry("fastuse", Taint.COMBAT),
			Map.entry("autoarmor", Taint.COMBAT),
			Map.entry("refill", Taint.COMBAT),
			Map.entry("autohotbar", Taint.COMBAT),
			Map.entry("cheststeal", Taint.COMBAT),
			Map.entry("invcleaner", Taint.COMBAT)
	);

	/** Ticks to keep suppressing after the last contaminated one, covering knockback and cooldowns. */
	private static final int COOLDOWN_TICKS = 40;

	private final EnumSet<Taint> held = EnumSet.noneOf(Taint.class);
	private String reason;
	private int cooldown;

	/** Display name of whatever is suppressing recording, or null when nothing is. */
	String reason() {
		return reason;
	}

	/** Recomputes the taint for this tick. {@code overridden} means a module drove the movement input. */
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
					// Name the widest-reaching one.
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
			// Hold the previous taint through the cooldown.
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

	/** A module counts as active when its toggle is on, not when its keybind is held. */
	private static boolean isActive(NovaModules.Module module) {
		return Boolean.TRUE.equals(module.get.get());
	}

	/** Whether a tick with this activity label survives every taint currently in force. */
	boolean allows(String activity) {
		return allows(held, activity);
	}

	/** The decision on its own, testable without a running client. Each taint vetoes independently. */
	static boolean allows(Set<Taint> taints, String activity) {
		if (taints.isEmpty()) return true;
		if (taints.contains(Taint.ALL)) return false;
		if (taints.contains(Taint.COMBAT) && ActivityClassifier.COMBAT.equals(activity)) return false;
		return !taints.contains(Taint.BLOCKS)
				|| (!ActivityClassifier.MINING.equals(activity)
				&& !ActivityClassifier.BUILDING.equals(activity));
	}
}
