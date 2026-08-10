package com.profps.client.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the bug that starved the corpus to 2% of real play.
 *
 * <p>{@code overridden} suppresses recording for 40 ticks, so a false positive is expensive: one
 * spurious flag per keypress is enough to blank out almost everything, and it looks exactly like
 * "the player had modules on". The original comparison read the keyboard at the tail of
 * {@code handleInputEvents()} and compared it against {@code input.playerInput}, which was written
 * during the <em>previous</em> player tick. Every press and every release disagreed.
 *
 * <p>The tell in the collected data was symmetry: {@code jump pressed=1 applied=0} 87 times and
 * {@code pressed=0 applied=1} 85 times. A real module disagrees in one direction and sustains it.
 */
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

	/**
	 * The actual regression. Tick N-1 held nothing and had nothing applied; tick N presses forward.
	 * Aligned, that is two clean ticks. Compared across the one-tick offset it looks like an
	 * override, which is what the bug did.
	 */
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

	/** A module applying input the player never pressed is the thing this must still catch. */
	@Test
	public void appliedInputTheKeyboardNeverAskedForIsAnOverride() {
		assertTrue(DataContribution.inputOverridden(keys(), keys(JUMP)));
		assertTrue(DataContribution.inputOverridden(keys(FORWARD), keys(FORWARD, SPRINT)));
	}

	/** And a module swallowing input the player did press. */
	@Test
	public void swallowedInputIsAnOverride() {
		assertTrue(DataContribution.inputOverridden(keys(FORWARD), keys()));
	}

	/** The very first tick of a session has nothing to compare against, and must not guess. */
	@Test
	public void theFirstTickIsNeverAnOverride() {
		assertFalse(DataContribution.inputOverridden(null, keys(FORWARD, JUMP)));
	}
}
