package com.profps.client.extras;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the {@code .litematic} bit packing and signed extent decoding. */
final class LitematicFileTest {

	@Test
	@DisplayName("bit width is max(2, ceil(log2(paletteSize)))")
	void bitWidthMatchesLitematica() {
		// A one- or two-entry palette still pays the two-bit floor.
		assertEquals(2, LitematicFile.bitsFor(1));
		assertEquals(2, LitematicFile.bitsFor(2));
		assertEquals(2, LitematicFile.bitsFor(3));
		assertEquals(2, LitematicFile.bitsFor(4));
		// Crossing a power of two costs a bit, one entry after the boundary.
		assertEquals(3, LitematicFile.bitsFor(5));
		assertEquals(3, LitematicFile.bitsFor(8));
		assertEquals(4, LitematicFile.bitsFor(9));
		// Widths taken from real files: 11 entries need 4 bits, 131 and 160 need 8.
		assertEquals(4, LitematicFile.bitsFor(11));
		assertEquals(8, LitematicFile.bitsFor(131));
		assertEquals(8, LitematicFile.bitsFor(160));
		assertEquals(9, LitematicFile.bitsFor(257));
	}

	@Test
	@DisplayName("a negative extent resolves to the same span as its positive mirror")
	void negativeExtentsResolveToTheLowerCorner() {
		// Straight case: 5 wide starting at 0 covers 0..4.
		assertEquals(0, LitematicFile.minCorner(0, 5));
		// A negative extent means position is the high corner: 72 with -73 covers 0..72.
		assertEquals(0, LitematicFile.minCorner(72, -73));
		assertEquals(0, LitematicFile.minCorner(4, -5));
		// Away from the origin, both directions.
		assertEquals(-10, LitematicFile.minCorner(-10, 4));
		assertEquals(-13, LitematicFile.minCorner(-10, -4));
		// A one-block extent is its own corner either way.
		assertEquals(7, LitematicFile.minCorner(7, 1));
		assertEquals(7, LitematicFile.minCorner(7, -1));
	}

	@Test
	@DisplayName("entries that straddle a long boundary round-trip")
	void packedEntriesSpanLongs() {
		// 3 bits does not divide 64, so entries 21, 42 and 63 straddle a long boundary.
		int bits = 3;
		int[] values = new int[200];
		Random random = new Random(20260822L);
		for (int i = 0; i < values.length; i++) values[i] = random.nextInt(1 << bits);

		long[] packed = pack(values, bits);
		for (int i = 0; i < values.length; i++) {
			assertEquals(values[i], LitematicFile.unpack(packed, bits, i), "entry " + i);
		}
	}

	@Test
	@DisplayName("every bit width round-trips, including the ones that divide 64")
	void packedEntriesRoundTripAtEveryWidth() {
		Random random = new Random(4671L);
		for (int bits = 2; bits <= 16; bits++) {
			int[] values = new int[257];
			for (int i = 0; i < values.length; i++) values[i] = random.nextInt(1 << bits);
			long[] packed = pack(values, bits);
			for (int i = 0; i < values.length; i++) {
				assertEquals(values[i], LitematicFile.unpack(packed, bits, i), "bits=" + bits + " entry=" + i);
			}
		}
	}

	@Test
	@DisplayName("reading past the end reports −1 rather than throwing")
	void outOfRangeIsRejected() {
		long[] packed = pack(new int[]{1, 2, 3}, 4);
		assertEquals(-1, LitematicFile.unpack(packed, 4, 4096));
	}

	/** The writer side of Litematica's packing, used only to generate fixtures. */
	private static long[] pack(int[] values, int bits) {
		long[] data = new long[(int) (((long) values.length * bits + 63L) / 64L)];
		for (int i = 0; i < values.length; i++) {
			long startOffset = (long) i * bits;
			int startArray = (int) (startOffset >> 6);
			int endArray = (int) ((((long) (i + 1)) * bits - 1L) >> 6);
			int startBit = (int) (startOffset & 63L);
			long mask = (1L << bits) - 1L;
			long value = values[i] & mask;
			data[startArray] |= value << startBit;
			if (startArray != endArray) data[endArray] |= value >>> (64 - startBit);
		}
		return data;
	}
}
