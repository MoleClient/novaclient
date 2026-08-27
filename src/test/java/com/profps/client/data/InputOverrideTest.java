package com.profps.client.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers {@code DataContribution.inputOverridden}, which flags only module-driven input. */
class InputOverrideTest {
	private static final int FORWARD = 0;
	private static final int JUMP = 4;
	private static final int SPRINT = 6;

	private static boolean[] keys(int... held) {
		boolean[] out = new boolean[7];
		for (int index : held) out[index] = true;
		return out;
	}

	@Test
	public void steadyStateIsNotAnOverride() {
		assertFalse(DataContribution.inputOverridden(keys(FORWARD), keys(FORWARD)));
		assertFalse(DataContribution.inputOverridden(keys(), keys()));
		assertFalse(DataContribution.inputOverridden(keys(FORWARD, SPRINT), keys(FORWARD, SPRINT)));
	}

	@Test
	public void pressingAKeyIsNotAnOverride() {
		boolean[] previousKeys = keys();
		boolean[] appliedForPreviousTick = keys();
		assertFalse(DataContribution.inputOverridden(previousKeys, appliedForPreviousTick),
				"a fresh keypress must not read as a module driving the player");
	}

	@Test
	public void releasingAKeyIsNotAnOverride() {
		assertFalse(DataContribution.inputOverridden(keys(JUMP), keys(JUMP)));
	}

	@Test
	public void appliedInputTheKeyboardNeverAskedForIsAnOverride() {
		assertTrue(DataContribution.inputOverridden(keys(), keys(JUMP)));
		assertTrue(DataContribution.inputOverridden(keys(FORWARD), keys(FORWARD, SPRINT)));
	}

	@Test
	public void swallowedInputIsAnOverride() {
		assertTrue(DataContribution.inputOverridden(keys(FORWARD), keys()));
	}

	@Test
	public void theFirstTickIsNeverAnOverride() {
		assertFalse(DataContribution.inputOverridden(null, keys(FORWARD, JUMP)));
	}
}
