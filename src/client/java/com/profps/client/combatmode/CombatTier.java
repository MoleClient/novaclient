package com.profps.client.combatmode;

/** Ordered combat assistance tiers; the ordinal is the persisted slider index. */
public enum CombatTier {
	LT5("LT5"),
	HT5("HT5"),
	LT4("LT4"),
	HT4("HT4"),
	LT3("LT3"),
	HT3("HT3"),
	LT2("LT2"),
	HT2("HT2"),
	LT1("LT1"),
	HT1("HT1");

	private static final CombatTier[] VALUES = values();
	private final String label;

	CombatTier(String label) {
		this.label = label;
	}

	public int index() {
		return ordinal();
	}

	public String label() {
		return label;
	}

	public boolean isAbove(CombatTier other) {
		return ordinal() > other.ordinal();
	}

	public static CombatTier fromIndex(int index) {
		return VALUES[Math.max(0, Math.min(VALUES.length - 1, index))];
	}
}
