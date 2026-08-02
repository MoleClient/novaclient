package com.profps.client.combatmode;

/** The single active high-level combat workflow. */
public enum CombatMode {
	OFF(0),
	SWORD(1),
	AXE(2),
	MACE(3);

	private final int configValue;

	CombatMode(int configValue) {
		this.configValue = configValue;
	}

	public int configValue() {
		return configValue;
	}

	public static CombatMode fromConfig(int value) {
		return switch (value) {
			case 1 -> SWORD;
			case 2 -> AXE;
			case 3 -> MACE;
			default -> OFF;
		};
	}
}
