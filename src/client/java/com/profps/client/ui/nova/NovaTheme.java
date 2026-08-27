package com.profps.client.ui.nova;

import net.minecraft.util.math.MathHelper;

/**
 * Accent colour presets shared by every Nova surface.
 * Order is persisted as {@code ProFPSConfig.guiAccent}, so keep it stable.
 */
public final class NovaTheme {
	/** {soft, base, deep} RGB per preset. */
	public static final int[][] ACCENT_PRESETS = {
			{0x7FD8FF, 0x38BDF8, 0x0EA5E9}, // Ice Blue
			{0xC4B5FD, 0x8B5CF6, 0x6D28D9}, // Violet
			{0x5EEAD4, 0x2DD4BF, 0x0D9488}, // Teal
			{0x86EFAC, 0x34D399, 0x059669}, // Mint
			{0xF9A8D4, 0xF472B6, 0xDB2777}, // Rose
			{0xFCD34D, 0xF59E0B, 0xD97706}, // Amber
			{0xFCA5A5, 0xF87171, 0xDC2626}, // Crimson
			{0xE2E8F0, 0xCBD5E1, 0x94A3B8}, // Frost
	};

	public static final String[] ACCENT_NAMES = {
			"Ice Blue", "Violet", "Teal", "Mint", "Rose", "Amber", "Crimson", "Frost"
	};

	/** Index of the default accent. */
	public static final int DEFAULT = 0;

	private NovaTheme() {}

	/** {@code {soft, base, deep}} ARGB for a preset index, clamped to a real preset. */
	public static int[] accent(int preset) {
		int i = MathHelper.clamp(preset, 0, ACCENT_PRESETS.length - 1);
		return new int[] {
				0xFF000000 | ACCENT_PRESETS[i][0],
				0xFF000000 | ACCENT_PRESETS[i][1],
				0xFF000000 | ACCENT_PRESETS[i][2],
		};
	}

	public static String name(int preset) {
		return ACCENT_NAMES[MathHelper.clamp(preset, 0, ACCENT_NAMES.length - 1)];
	}

	public static int count() {
		return ACCENT_PRESETS.length;
	}
}
