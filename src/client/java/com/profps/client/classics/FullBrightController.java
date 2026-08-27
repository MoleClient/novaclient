package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;

/** Client-only lightmap tuning: raises vanilla gamma and night-vision blend strength. */
public final class FullBrightController {
	private static volatile ProFPSConfig config;

	private FullBrightController() {
	}

	public static void initialize(ProFPSConfig liveConfig) {
		config = liveConfig;
	}

	public static float adjustNightVision(float vanillaStrength) {
		ProFPSConfig cfg = config;
		if (cfg == null || !cfg.enabled || !cfg.fullBrightEnabled) return vanillaStrength;

		float level = clampLevel(cfg.fullBrightLevel) / 10.0F;
		float target = 0.10F + 0.90F * level;
		return Math.max(vanillaStrength, target);
	}

	public static float adjustGamma(float vanillaGamma) {
		ProFPSConfig cfg = config;
		if (cfg == null || !cfg.enabled || !cfg.fullBrightEnabled) return vanillaGamma;

		float level = clampLevel(cfg.fullBrightLevel) / 10.0F;
		float target = 0.35F + 0.65F * level;
		return Math.max(vanillaGamma, target);
	}

	private static int clampLevel(int level) {
		return Math.max(1, Math.min(10, level));
	}
}
