package com.profps.client.ui.nova;

/** Resolves Nova's drawing scale from the current GUI viewport, never below the 650x450 virtual canvas. */
public final class NovaUiScale {
	public static final int MIN_LAYOUT_WIDTH = 650;
	public static final int MIN_LAYOUT_HEIGHT = 450;
	public static final int MIN_MANUAL_PERCENT = 50;
	public static final int MAX_MANUAL_PERCENT = 140;

	private static final float AUTO_REFERENCE_WIDTH = 900.0F;
	private static final float AUTO_REFERENCE_HEIGHT = 520.0F;
	private static final float AUTO_MIN = 0.50F;
	private static final float AUTO_MAX = 1.25F;
	private static final float WINDOW_MARGIN = 16.0F;

	private NovaUiScale() {
	}

	public static float resolve(int viewportWidth, int viewportHeight, boolean automatic, int manualPercent) {
		float desired = automatic
				? clamp(Math.min(viewportWidth / AUTO_REFERENCE_WIDTH,
						viewportHeight / AUTO_REFERENCE_HEIGHT), AUTO_MIN, AUTO_MAX)
				: clamp(manualPercent, MIN_MANUAL_PERCENT, MAX_MANUAL_PERCENT) / 100.0F;

		float availableWidth = Math.max(1.0F, viewportWidth - WINDOW_MARGIN);
		float availableHeight = Math.max(1.0F, viewportHeight - WINDOW_MARGIN);
		float fitCeiling = Math.min(availableWidth / MIN_LAYOUT_WIDTH,
				availableHeight / MIN_LAYOUT_HEIGHT);
		return Math.max(0.01F, Math.min(desired, fitCeiling));
	}

	public static int virtualWidth(int viewportWidth, float scale) {
		return Math.max(MIN_LAYOUT_WIDTH, (int) Math.floor(viewportWidth / Math.max(0.01F, scale)));
	}

	public static int virtualHeight(int viewportHeight, float scale) {
		return Math.max(MIN_LAYOUT_HEIGHT, (int) Math.floor(viewportHeight / Math.max(0.01F, scale)));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
