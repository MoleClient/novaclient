package com.profps.client.donutsmp;

import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Passive intelligence backbone for ground-based base finding.
 *
 * <p>The server masks block data below the deepslate layer, but it cannot mask
 * the <i>side effects</i> of a player working down there — those leak to the
 * client as packets no matter what:
 * <ul>
 *   <li><b>Sound events</b> — opening chests/barrels/shulkers, anvils, pistons,
 *       brewing, enchanting, doors, and especially block-break sounds while
 *       someone mines. Each carries an exact XYZ.</li>
 *   <li><b>Block-update packets</b> — when a player mines or places a block in a
 *       chunk the client already has loaded, the server must sync that change.
 *       Deep underground there is almost no <i>natural</i> block churn, so a
 *       stream of deep updates is a near-certain "someone is active below you".</li>
 * </ul>
 *
 * This class accumulates those leaked signals per chunk with wall-clock decay.
 * The Chunk Activity detector reads the per-chunk snapshot and folds it into its
 * suspicion score. Packet mixins (running on the client thread) push signals in;
 * the detector (also client thread) pulls them out — no cross-thread access.
 */
public final class WorldSignalMonitor {
	private static final WorldSignalMonitor INSTANCE = new WorldSignalMonitor();

	/** Signals older than this contribute nothing. */
	private static final long WINDOW_MS = 75_000L;

	/** Accumulated signal at which a chunk is flagged "hot" for an immediate out-of-band scan. */
	private static final double HOT_THRESHOLD = 6.0;

	private boolean active;
	private final Map<Long, ChunkSignal> signals = new HashMap<>();
	private final java.util.LinkedHashSet<Long> hotChunks = new java.util.LinkedHashSet<>();

	private WorldSignalMonitor() {}

	public static WorldSignalMonitor get() {
		return INSTANCE;
	}

	/** Enabled by the Chunk Activity module; when off we ignore and drop everything. */
	public void setActive(boolean value) {
		if (active && !value) {
			signals.clear();
			hotChunks.clear();
		}
		active = value;
	}

	public boolean isActive() {
		return active;
	}

	// ── Signal intake (called from packet mixins) ─────────────────────────────

	/**
	 * True for events the LOCAL player generated themselves. Everything a player
	 * does — mining, placing, opening their own chests, footsteps — happens
	 * within arm's reach, and counting it painted a red "base" box that followed
	 * the player around wherever they dug. Anyone else's activity is at least a
	 * few blocks away from our own position.
	 */
	private static boolean isSelfActivity(double x, double y, double z) {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player == null) return false;
		return player.squaredDistanceTo(x, y, z) < 81.0; // 9 blocks > max reach
	}

	public void recordSound(double x, double y, double z, SoundCategory category, String soundId) {
		if (!active) return;
		if (isSelfActivity(x, y, z)) return;
		double weight = soundWeight(category, soundId, y);
		if (weight <= 0.0) return;
		ChunkSignal signal = signalFor(x, z);
		signal.addSound(weight, depthBonus(y), (int) Math.floor(y));
		// Mechanism sounds feed the redstone-clock detector: a farm clock fires
		// the same piston/dispenser at a fixed interval, which no player does.
		if (soundId.contains("piston") || soundId.contains("dispenser")
				|| soundId.contains("dropper") || soundId.contains("note_block")) {
			signal.addMechanismTick(System.currentTimeMillis());
		}
		markHotIfStrong(x, z, signal);
	}

	public void recordBlockChange(int x, int y, int z) {
		if (!active) return;
		if (isSelfActivity(x + 0.5, y + 0.5, z + 0.5)) return;
		double weight = blockUpdateWeight(y);
		if (weight <= 0.0) return;
		ChunkSignal signal = signalFor(x, z);
		// Reject explosion bursts: a TNT/crystal blast or a griefed PvP fight
		// dumps dozens of block changes into a chunk in a single instant, which
		// otherwise scored like furious mining and painted a "base" over a crater.
		// Real mining trickles ~1 block at a time, so it never trips the burst
		// detector and still scores normally.
		if (!signal.registerBlockUpdate(System.currentTimeMillis())) return;
		signal.addBlockUpdate(weight, depthBonus(y), y);
		markHotIfStrong(x, z, signal);
	}

	/** Queue the chunk for an immediate out-of-band scan once its signal is strong enough. */
	private void markHotIfStrong(double x, double z, ChunkSignal signal) {
		if (signal.decayedValue(System.currentTimeMillis()) < HOT_THRESHOLD) return;
		if (hotChunks.size() >= 64) return; // detector is clearly behind; don't hoard
		hotChunks.add(ChunkPos.toLong((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4));
	}

	/**
	 * Chunks whose live signal just crossed the hot threshold, cleared on read.
	 * The detector scans these the same tick instead of waiting for the sweep.
	 */
	public java.util.List<Long> drainHotChunks() {
		if (hotChunks.isEmpty()) return java.util.List.of();
		java.util.List<Long> out = new java.util.ArrayList<>(hotChunks);
		hotChunks.clear();
		return out;
	}

	// ── Snapshot read (called from the detector) ──────────────────────────────

	/** Time-decayed signal snapshot for a chunk, or null if it has none. */
	public Snapshot snapshot(int chunkX, int chunkZ) {
		ChunkSignal signal = signals.get(ChunkPos.toLong(chunkX, chunkZ));
		if (signal == null) return null;
		long now = System.currentTimeMillis();
		double decay = signal.decayedValue(now);
		if (decay < 0.01) return null;
		return new Snapshot(signal.soundScore(now), signal.blockUpdateScore(now),
				signal.soundEvents(now), signal.blockUpdates(now), signal.deepestY,
				signal.clockScore(now));
	}

	/** Drop fully-decayed chunks; call occasionally so the map can't grow forever. */
	public void prune() {
		if (!active || signals.isEmpty()) return;
		long now = System.currentTimeMillis();
		signals.values().removeIf(signal -> signal.decayedValue(now) < 0.01);
	}

	private ChunkSignal signalFor(double x, double z) {
		long key = ChunkPos.toLong((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
		return signals.computeIfAbsent(key, k -> new ChunkSignal());
	}

	// ── Weighting ──────────────────────────────────────────────────────────────

	/** Deep activity is far more telling than surface activity. */
	private static double depthBonus(double y) {
		if (y < 0) return 2.2;       // deepslate / masked zone — the prize
		if (y < 16) return 1.6;
		if (y < 40) return 1.0;
		return 0.55;                 // surface — lots of natural noise
	}

	private static double blockUpdateWeight(int y) {
		// Natural block churn (fluids, crops, leaves, fire) lives near/above the
		// surface. Below the deepslate line almost the only source of updates is
		// a player mining or placing — so weight deep updates heavily.
		if (y < 0) return 3.0;
		if (y < 16) return 1.6;
		if (y < 48) return 0.45;
		return 0.12;
	}

	private static double soundWeight(SoundCategory category, String id, double y) {
		if (id == null) return 0.0;
		// Drowned-out noise we never care about.
		if (id.startsWith("music") || id.contains("record") || id.startsWith("ui.")
				|| id.startsWith("ambient") || id.startsWith("weather")
				|| id.startsWith("entity.player.")) {
			return 0.0;
		}
		// Container interactions — someone is in their base touching storage.
		if (id.contains("shulker_box.open") || id.contains("shulker_box.close")) return 9.0;
		if (id.contains("barrel.open") || id.contains("barrel.close")) return 8.0;
		if (id.contains("chest.open") || id.contains("chest.close")) return 7.5;
		if (id.contains("ender_chest")) return 6.5;
		// Crafting/utility stations.
		if (id.contains("anvil")) return 6.0;
		if (id.contains("enchantment_table") || id.contains("enchant")) return 6.0;
		if (id.contains("brewing_stand")) return 5.5;
		if (id.contains("grindstone") || id.contains("smithing")) return 4.5;
		// Redstone / automation.
		if (id.contains("piston")) return 4.5;
		if (id.contains("dispenser") || id.contains("dropper")) return 4.0;
		if (id.contains("note_block")) return 3.0;
		if (id.contains("lever") || id.contains("button")) return 2.5;
		if (id.contains("door") || id.contains("trapdoor") || id.contains("fence_gate")) return 3.5;
		// Player at base picking up farm output.
		if (id.contains("experience_orb")) return 2.8;
		if (id.contains("item.pickup")) return 1.8;
		// Active mining: block break/place sounds. Only meaningful when deep,
		// where depthBonus already amplifies them.
		if (category == SoundCategory.BLOCKS && (id.contains(".break") || id.contains(".place")
				|| id.contains(".hit") || id.contains(".step"))) {
			return y < 24 ? 2.2 : 0.0;
		}
		return 0.0;
	}

	// ── Data holders ─────────────────────────────────────────────────────────

	public record Snapshot(double soundScore, double blockUpdateScore,
			int soundEvents, int blockUpdates, int deepestY, double clockScore) {
		public double total() {
			return soundScore + blockUpdateScore + clockScore;
		}

		public boolean hasClock() {
			return clockScore > 0.0;
		}
	}

	private static final class ChunkSignal {
		private static final int MECHANISM_SAMPLES = 10;
		/** ≥ this many block updates within the window = an explosion, not mining. */
		private static final int BURST_THRESHOLD = 14;
		private static final long BURST_WINDOW_MS = 250L;
		/** After a detected blast, ignore the chunk's block-update signal this long. */
		private static final long EXPLOSION_COOLDOWN_MS = 90_000L;

		private double soundAccum;
		private double blockAccum;
		private int soundCount;
		private int blockCount;
		private long lastUpdateMs = System.currentTimeMillis();
		private int deepestY = Integer.MAX_VALUE;
		private final long[] mechanismTimes = new long[MECHANISM_SAMPLES];
		private int mechanismIndex;
		private int mechanismCount;
		private long burstWindowStart;
		private int burstWindowCount;
		private long explosionSuppressedUntil;

		/**
		 * Burst gate for block updates. Returns false (skip scoring) while the
		 * chunk is inside an explosion cooldown, and trips that cooldown — wiping
		 * the burst's accumulated score — the moment too many updates land too
		 * fast. Sparse mining never crosses the threshold.
		 */
		boolean registerBlockUpdate(long now) {
			if (now < explosionSuppressedUntil) return false;
			if (now - burstWindowStart <= BURST_WINDOW_MS) {
				burstWindowCount++;
			} else {
				burstWindowStart = now;
				burstWindowCount = 1;
			}
			if (burstWindowCount >= BURST_THRESHOLD) {
				explosionSuppressedUntil = now + EXPLOSION_COOLDOWN_MS;
				blockAccum = 0.0; // erase the partial burst we just let through
				blockCount = 0;
				return false;
			}
			return true;
		}

		void addMechanismTick(long now) {
			// Two sounds from the same firing (extend+retract) arrive ~instantly;
			// collapse anything within 80ms into one tick.
			if (mechanismCount > 0
					&& now - mechanismTimes[(mechanismIndex + MECHANISM_SAMPLES - 1) % MECHANISM_SAMPLES] < 80L) {
				return;
			}
			mechanismTimes[mechanismIndex] = now;
			mechanismIndex = (mechanismIndex + 1) % MECHANISM_SAMPLES;
			if (mechanismCount < MECHANISM_SAMPLES) mechanismCount++;
		}

		/**
		 * Redstone-clock detector: ≥6 recent mechanism firings at a near-constant
		 * interval (coefficient of variation < 0.25) is an automated farm clock.
		 */
		double clockScore(long now) {
			if (mechanismCount < 6) return 0.0;
			long newest = mechanismTimes[(mechanismIndex + MECHANISM_SAMPLES - 1) % MECHANISM_SAMPLES];
			if (now - newest > WINDOW_MS) return 0.0;

			long[] ordered = new long[mechanismCount];
			for (int i = 0; i < mechanismCount; i++) {
				ordered[i] = mechanismTimes[(mechanismIndex + MECHANISM_SAMPLES - mechanismCount + i) % MECHANISM_SAMPLES];
			}
			int intervals = mechanismCount - 1;
			double mean = (ordered[intervals] - ordered[0]) / (double) intervals;
			if (mean < 150.0 || mean > 15_000.0) return 0.0;
			double variance = 0.0;
			for (int i = 0; i < intervals; i++) {
				double d = (ordered[i + 1] - ordered[i]) - mean;
				variance += d * d;
			}
			double cv = Math.sqrt(variance / intervals) / mean;
			return cv < 0.25 ? 34.0 : 0.0;
		}

		void addSound(double weight, double depthMul, int y) {
			decayInPlace();
			soundAccum += weight * depthMul;
			soundCount++;
			if (y < deepestY) deepestY = y;
		}

		void addBlockUpdate(double weight, double depthMul, int y) {
			decayInPlace();
			blockAccum += weight * depthMul;
			blockCount++;
			if (y < deepestY) deepestY = y;
		}

		/** Exponential decay toward zero over the window. */
		private void decayInPlace() {
			long now = System.currentTimeMillis();
			double factor = decayFactor(now);
			soundAccum *= factor;
			blockAccum *= factor;
			soundCount = (int) Math.round(soundCount * factor);
			blockCount = (int) Math.round(blockCount * factor);
			lastUpdateMs = now;
		}

		private double decayFactor(long now) {
			long dt = Math.max(0, now - lastUpdateMs);
			if (dt >= WINDOW_MS) return 0.0;
			return Math.exp(-(double) dt / (WINDOW_MS * 0.5));
		}

		double decayedValue(long now) {
			double f = decayFactor(now);
			return (soundAccum + blockAccum) * f;
		}

		double soundScore(long now) {
			return soundAccum * decayFactor(now);
		}

		double blockUpdateScore(long now) {
			return blockAccum * decayFactor(now);
		}

		int soundEvents(long now) {
			return (int) Math.round(soundCount * decayFactor(now));
		}

		int blockUpdates(long now) {
			return (int) Math.round(blockCount * decayFactor(now));
		}
	}
}
