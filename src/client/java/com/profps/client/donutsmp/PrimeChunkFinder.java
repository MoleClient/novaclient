package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prime Chunk Finder — flags chunks whose UNDER-RENDER space (the zone below
 * y=0 that depth-hiding servers never stream to a surface player) is very
 * likely to hold a base, using only what still leaks through the mask.
 *
 * <p>No single leak is trusted on its own. Each chunk accumulates evidence from
 * independent channels, and only the combined score crosses the flag line:
 *
 * <ul>
 *   <li><b>Light through the mask.</b> Depth-hiders replace blocks but ship the
 *       server-computed light grid untouched. Block light glowing inside what
 *       renders as solid stone below y=0 is artificial lighting. Sampled on a
 *       stride, and only in the −40..0 band — the lava seas and ancient-city
 *       lanterns further down would drown the signal in natural light.</li>
 *   <li><b>Leaked block entities.</b> Chunk packets carry container/spawner
 *       block-entity data even where the block itself is masked. A chest the
 *       renderer cannot see is furniture, not geology.</li>
 *   <li><b>Palette anomalies.</b> A masked section's palette should only hold
 *       mask blocks. Glass, concrete, wool, beds, enchanters below y=0 mean the
 *       section was streamed with real contents. Mineshaft materials (planks,
 *       fences, rails, cobwebs) are deliberately NOT in this set — mineshafts
 *       generate below zero naturally and were the classic overflag.</li>
 *   <li><b>Improper rotation.</b> World generation lays deepslate, infested
 *       deepslate and basalt on the Y axis and never any other way. Vanilla
 *       orients a placed pillar along the face it was clicked against, so a
 *       player who mines a hole and walls it back up leaves X- or Z-axis
 *       deepslate behind. There is no natural source of one, at any depth, so
 *       this channel alone is scanned across the whole hidden column instead of
 *       the palette channel's shallow window.</li>
 *   <li><b>Cultivated growth.</b> Bamboo thickens, cocoa ripens, glow berries
 *       return, kelp climbs and amethyst matures only on random ticks — which
 *       only fire in a chunk somebody is keeping loaded. Maturity is therefore
 *       an odometer of human time spent in an area rather than evidence of a
 *       build. Cave vines hung bare of every berry, or a geode stripped of
 *       every bud, read as a harvest round in progress.</li>
 *   <li><b>Live traffic.</b> Sounds, block updates, chest-lid/piston events and
 *       particles are broadcast with true coordinates regardless of masking.
 *       Underground traffic decays over ~2 minutes so a survey flight sees
 *       recent activity, not fossils.</li>
 *   <li><b>Entity census.</b> Item frames, armor stands, chest minecarts,
 *       villagers, passive animals and named mobs below the surface got there
 *       in someone's boat. Hostile mobs are ignored — caves make those free.</li>
 * </ul>
 *
 * <p><b>Evidence latches.</b> Static channels are kept at their high-water mark
 * instead of being overwritten each rescan, because the strongest of them are
 * destroyed by the act of looking. Light through the mask works by catching
 * block light glowing inside what renders as solid stone; walk closer, the
 * server sends the real blocks, the fake stone becomes the real room, and the
 * light is no longer inside anything. Recomputing over the top meant a flag
 * evaporated precisely when you approached it while fresh chunks kept flagging
 * at the far edge of the scan — so a base appeared to run away. It never moved;
 * the proof did. A flag now survives the approach.
 *
 * <p>Latched evidence is retired by <b>verdict</b>, not by decay. Inside
 * {@link #VERDICT_RADIUS_CHUNKS} the client certainly holds real blocks, so a
 * scan there is final: evidence that survives confirms the chunk, and evidence
 * that has entirely evaporated clears it permanently. The exception is a chunk
 * whose light channel still fires at point-blank range — that server is masking
 * even here, the real blocks were never delivered, and there is nothing to
 * adjudicate.
 *
 * <p>Natural-structure dampeners keep the precision: a lone spawner with a
 * couple of chests and no light is a dungeon, not a base, so spawner presence
 * halves container weight unless artificial light corroborates. Chunks with no
 * intrinsic evidence can never be flagged by neighbours alone; adjacency only
 * amplifies chunks that already testify (+15% per flagged neighbour, capped),
 * because real bases span several chunks and their evidence should agree.
 *
 * <p>The Weight slider (0.0–1.0) moves the flag line: 0 demands a stacked,
 * multi-channel case; 1 flags on light suspicion. The default (0.40) sits where
 * a leaked container plus any second channel flags, but no single natural
 * feature does. Buried builds above y=0 flag through the same channels — the
 * hidden-space criterion is "well below the surface", not "below zero".
 *
 * <p>Rendering matches the classic flat-wash look: a translucent red sheet at
 * the deepslate boundary drawn through terrain, pale for borderline scores and
 * saturated red for strong ones, with contiguous flags greedily merged into
 * single rectangles so a whole base is one quad, not forty. Tracers use the
 * shared {@link NovaTracers} system. All scanning runs inside a
 * {@link ScanBudget} lane; rectangles rebuild only when the flag set changes.
 */
public final class PrimeChunkFinder {
	// ── Evidence weights (sum vs threshold) ───────────────────────────────────
	private static final double W_LIGHT_CELL = 0.5;      // per lit sample, capped
	private static final double CAP_LIGHT = 2.5;
	private static final double W_CONTAINER_FIRST = 3.0; // first leaked container BE
	private static final double W_CONTAINER_EXTRA = 0.5; // each additional, capped
	private static final double CAP_CONTAINER = 5.0;
	private static final double CAP_CONTAINER_DUNGEON = 2.0; // spawner nearby, no light
	private static final double W_SPAWNER = 2.0;
	private static final double W_PALETTE = 2.2;         // man-made block below zero
	private static final double W_PALETTE_DEEP_SCALE = 0.5; // −48..−33 band half weight
	private static final double W_SOUND_CONTAINER = 1.1; // chest/door/piston sounds
	private static final double W_SOUND_BLOCK = 0.5;     // generic dig/place
	private static final double CAP_SOUND = 3.0;
	private static final double W_BLOCK_UPDATE = 1.1;
	private static final double CAP_BLOCK_UPDATE = 4.0;
	private static final double W_BLOCK_EVENT = 1.4;     // lids and pistons broadcast real coords
	private static final double CAP_BLOCK_EVENT = 4.2;
	private static final double W_PARTICLE = 0.5;
	private static final double CAP_PARTICLE = 2.0;
	private static final double W_FURNITURE_ENTITY = 1.2; // frames, stands
	private static final double W_CHEST_MINECART = 1.5;
	private static final double W_MOVED_ANIMAL = 1.0;
	private static final double W_VILLAGER = 2.0;
	private static final double W_NAMED_MOB = 1.5;
	private static final double W_PLAYER_BELOW = 1.0;    // another player under the mask right now
	private static final double CAP_CENSUS = 6.0;
	/** Growth clock: random ticks only run while a player keeps the chunk
	 * loaded, so grown kelp, thick bamboo, ripe cocoa, returned glow berries and
	 * matured geodes are odometers of human time spent in an area. Heat NEVER
	 * flags alone — it is capped well under every threshold and only amplifies
	 * chunks that have real evidence. That cap is what makes it safe to read
	 * plants a lush cave or a jungle would have grown by itself. */
	private static final double CAP_GROWTH_HEAT = 1.2;
	private static final double W_HARVESTED_GEODE = 1.5; // stripped buds = player's farm route
	private static final double W_HARVESTED_BERRIES = 1.2; // cave vines picked bare
	private static final double CAP_HARVEST = 2.4;
	/**
	 * Improperly rotated pillar blocks. This is the only static channel with no
	 * natural explanation whatsoever, so it carries palette-grade weight and,
	 * unlike the palette channel, it is trusted at every depth: there is no band
	 * where world generation starts producing sideways deepslate.
	 */
	private static final double W_ROTATION = 2.6;
	private static final double CAP_ROTATION = 3.9;

	/** Per-tick decay for live traffic: half-life ≈ 2 minutes. */
	private static final double TRAFFIC_DECAY = 0.99985;
	/** Threshold line: weight 0 → 5.5, weight 1 → 0.7 (default 0.40 → 3.58). */
	private static final double THRESHOLD_STRICT = 5.5;
	private static final double THRESHOLD_LOOSE = 0.7;
	/** Strong tier (saturated red) begins at this multiple of the threshold. */
	private static final double STRONG_TIER = 1.6;
	/** Adjacency: +15% per flagged neighbour, at most +45%, never from zero. */
	private static final double NEIGHBOUR_BONUS = 0.15;
	private static final double NEIGHBOUR_BONUS_CAP = 0.45;
	private static final double NEIGHBOUR_NEEDS_OWN = 1.0;

	// Light scan geometry: the band where artificial light is meaningful.
	private static final int LIGHT_MIN_Y = -40;
	private static final int LIGHT_MAX_Y = -1;
	private static final int LIGHT_STRIDE_XZ = 4;
	private static final int LIGHT_STRIDE_Y = 4;
	/** Sub-surface criterion for above-zero evidence: this far under the heightmap. */
	private static final int BURIED_MARGIN = 12;

	/**
	 * Chunks within this many chunks of the player are certainly streamed with
	 * real blocks by any server, masking or not. A scan at that range is
	 * therefore a verdict, not a sample: evidence that holds up confirms the
	 * chunk, and evidence that has entirely evaporated clears it for good. This
	 * is the release valve on latched evidence — without it a flag raised at
	 * distance could never be retired, and the map would fill with red.
	 */
	private static final int VERDICT_RADIUS_CHUNKS = 3;
	/** Standing evidence to keep. Beyond this the quietest far cells are dropped. */
	private static final int MAX_TRACKED_CHUNKS = 20_000;

	private static final int RESCAN_INTERVAL_TICKS = 200; // static leaks re-read every 10s
	private static final int CENSUS_INTERVAL_TICKS = 20;
	private static final int MAX_RECTS = 192;
	private static final int MAX_TRACERS = 24;
	/** The flat wash sits just above the deepslate boundary. */
	private static final double SHEET_Y = 0.05;

	private static final int COLOR_STRONG = 0xE03434; // saturated red
	private static final int COLOR_PALE = 0xE8D8B8;   // borderline cream

	/** Blocks that do not occur below zero unless a player brought them. */
	private static final Set<Block> MAN_MADE = buildManMadeSet();

	/**
	 * Pillar blocks world generation only ever lays down on the Y axis.
	 *
	 * <p>Deepslate is the valuable one: it fills the entire hidden band, and
	 * vanilla never generates it sideways. Vanilla orients a placed pillar along
	 * the face it was clicked against, so a player who mines deepslate and walls
	 * the hole back up leaves X- or Z-axis deepslate behind. One of those under
	 * the mask is a hand-placed block with no natural explanation at all — a
	 * cleaner tell than any palette anomaly, because a base builder cannot avoid
	 * making them and a depth-hider that rewrites blocks to plain deepslate
	 * cannot fake them back into existence.
	 */
	private static final Set<Block> NATURAL_Y_PILLARS = Set.of(
			Blocks.DEEPSLATE, Blocks.INFESTED_DEEPSLATE, Blocks.BASALT);

	private static PrimeChunkFinder instance;

	private final ProFPSConfig config;

	/** Live per-chunk evidence, keyed by ChunkPos.toLong(). */
	private final Map<Long, Evidence> evidence = new HashMap<>();
	private final Set<Long> flagged = new HashSet<>();
	private final Set<Long> strong = new HashSet<>();
	private final ArrayDeque<long[]> scanQueue = new ArrayDeque<>();
	private List<Rect> rects = List.of();
	private boolean rectsDirty;
	private int nextRescanTick;
	private int nextCensusTick;
	private int scanCentreChunkX = Integer.MIN_VALUE;
	private int scanCentreChunkZ = Integer.MIN_VALUE;
	private ClientWorld trackedWorld;
	private boolean failedClosed;

	private static final class Evidence {
		boolean cleared;        // inspected point-blank and found empty
		boolean confirmed;      // inspected point-blank and evidence held up
		double lightScore;      // static scans — latched at their peak
		double blockEntityScore;
		double paletteScore;
		double rotationScore;   // natural pillars turned off-axis by hand
		double censusScore;
		double growthHeat;      // loaded-time odometer, capped sub-threshold
		double harvestScore;    // stripped geodes and picked vines: direct activity
		double trafficScore;    // live packets — decays
		int soundCount, updateCount, eventCount, particleCount;
		boolean spawnerPresent;
		int lastTouchedTick;

		double total() {
			// A chunk somebody actually walked into and found nothing in stays
			// silent for good, which is what keeps latched evidence from turning
			// the map permanently red.
			if (cleared) return 0.0;
			double containers = blockEntityScore;
			if (spawnerPresent && lightScore < 0.5) {
				containers = Math.min(containers, CAP_CONTAINER_DUNGEON);
			}
			double core = lightScore + containers + paletteScore + rotationScore + censusScore
					+ trafficScore + harvestScore + (spawnerPresent ? W_SPAWNER : 0.0);
			// Heat only speaks when something else already does.
			return core > 0.5 ? core + Math.min(CAP_GROWTH_HEAT, growthHeat) : core;
		}
	}

	private record Rect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, boolean strongTier) {
		Box box() {
			return new Box(minChunkX << 4, SHEET_Y, minChunkZ << 4,
					(maxChunkX + 1) << 4, SHEET_Y, (maxChunkZ + 1) << 4);
		}

		Vec3d centre() {
			return new Vec3d(((minChunkX + maxChunkX + 1) / 2.0) * 16.0, SHEET_Y,
					((minChunkZ + maxChunkZ + 1) / 2.0) * 16.0);
		}
	}

	public PrimeChunkFinder(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/** Fast gate for the packet mixin: only pay for intake while the module runs. */
	public static boolean listening() {
		return instance != null && !instance.failedClosed
				&& instance.config.enabled && instance.config.donutPrimeChunk;
	}

	/** Cross-module corroboration: is this chunk currently prime-flagged? */
	public static boolean isFlagged(long chunkKey) {
		return instance != null && instance.flagged.contains(chunkKey);
	}

	// ── Packet intake (called from PrimeSignalMixin on the client thread) ─────

	public static void recordSound(double x, double y, double z, String soundId) {
		if (!listening() || instance.aboveGround(x, y, z) || nearSelf(x, y, z)) return;
		Evidence cell = instance.cellFor(x, z);
		if (cell == null || cell.soundCount >= 64) return;
		cell.soundCount++;
		boolean containerish = soundId != null && (soundId.contains("chest") || soundId.contains("barrel")
				|| soundId.contains("door") || soundId.contains("piston") || soundId.contains("shulker")
				|| soundId.contains("anvil") || soundId.contains("furnace") || soundId.contains("brewing"));
		instance.addTraffic(cell, containerish ? W_SOUND_CONTAINER : W_SOUND_BLOCK,
				cell.soundCount * (containerish ? W_SOUND_CONTAINER : W_SOUND_BLOCK) > CAP_SOUND);
	}

	public static void recordBlockUpdate(BlockPos pos) {
		if (!listening() || instance.aboveGround(pos.getX(), pos.getY(), pos.getZ())
				|| nearSelf(pos.getX(), pos.getY(), pos.getZ())) return;
		Evidence cell = instance.cellFor(pos.getX(), pos.getZ());
		if (cell == null || cell.updateCount >= 64) return;
		cell.updateCount++;
		instance.addTraffic(cell, W_BLOCK_UPDATE, cell.updateCount * W_BLOCK_UPDATE > CAP_BLOCK_UPDATE);
	}

	public static void recordBlockEvent(BlockPos pos) {
		if (!listening() || instance.aboveGround(pos.getX(), pos.getY(), pos.getZ())
				|| nearSelf(pos.getX(), pos.getY(), pos.getZ())) return;
		Evidence cell = instance.cellFor(pos.getX(), pos.getZ());
		if (cell == null || cell.eventCount >= 32) return;
		cell.eventCount++;
		instance.addTraffic(cell, W_BLOCK_EVENT, cell.eventCount * W_BLOCK_EVENT > CAP_BLOCK_EVENT);
	}

	public static void recordParticle(double x, double y, double z) {
		if (!listening() || instance.aboveGround(x, y, z) || nearSelf(x, y, z)) return;
		Evidence cell = instance.cellFor(x, z);
		if (cell == null || cell.particleCount >= 32) return;
		cell.particleCount++;
		instance.addTraffic(cell, W_PARTICLE, cell.particleCount * W_PARTICLE > CAP_PARTICLE);
	}

	/** Your own mining, torching and chest traffic must never flag your own dig. */
	private static boolean nearSelf(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return true;
		return client.player.squaredDistanceTo(x, y, z) < 12.0 * 12.0;
	}

	private void addTraffic(Evidence cell, double amount, boolean capped) {
		if (!capped) {
			cell.trafficScore += amount;
			rectsDirty = true; // cheap; rebuild is gated by flag-set comparison anyway
		}
	}

	/** Below zero always counts; above zero only well under the local surface. */
	private boolean aboveGround(double x, double y, double z) {
		if (y < 0.0) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return true;
		int surface = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING,
				MathHelper.floor(x), MathHelper.floor(z));
		return y > surface - BURIED_MARGIN;
	}

	private Evidence cellFor(double x, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return null;
		int chunkX = MathHelper.floor(x) >> 4;
		int chunkZ = MathHelper.floor(z) >> 4;
		double range = MathHelper.clamp(config.donutPrimeChunkRange, 48, 1024);
		double dx = (chunkX << 4) + 8 - client.player.getX();
		double dz = (chunkZ << 4) + 8 - client.player.getZ();
		if (dx * dx + dz * dz > (range + 32) * (range + 32)) return null;
		return evidence.computeIfAbsent(ChunkPos.toLong(chunkX, chunkZ), key -> new Evidence());
	}

	// ── Tick: decay, census, budgeted static scans ────────────────────────────

	public void tick(MinecraftClient client) {
		if (failedClosed || !config.enabled || !config.donutPrimeChunk) {
			if (!evidence.isEmpty()) reset();
			return;
		}
		if (client.player == null || client.world == null) {
			if (!evidence.isEmpty()) reset();
			return;
		}
		try {
			if (client.world != trackedWorld) {
				reset();
				trackedWorld = client.world;
			}
			// The whole signal model is about the overworld's hidden depths.
			if (client.world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;

			int tick = client.player.age;
			decayTraffic();
			if (tick >= nextCensusTick) {
				nextCensusTick = tick + CENSUS_INTERVAL_TICKS;
				runEntityCensus(client);
			}
			if (scanQueue.isEmpty() && (tick >= nextRescanTick
					|| ScanBudget.leftScanArea(client, scanCentreChunkX, scanCentreChunkZ, 4))) {
				nextRescanTick = tick + RESCAN_INTERVAL_TICKS;
				enqueueScans(client);
			}
			drainScanQueue(client, tick);
			evictDistantQuietChunks(client);
			refreshFlags();
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Prime Chunk Finder failed; disabling it to protect the client.", exception);
			reset();
			config.donutPrimeChunk = false;
			// Not saved: a transient failure must not persist as an off switch.
			failedClosed = true;
		}
	}

	private void reset() {
		evidence.clear();
		flagged.clear();
		strong.clear();
		scanQueue.clear();
		rects = List.of();
		rectsDirty = false;
		trackedWorld = null;
		scanCentreChunkX = Integer.MIN_VALUE;
		scanCentreChunkZ = Integer.MIN_VALUE;
	}

	/**
	 * Bounds the evidence map. Latched evidence no longer fades on its own, so a
	 * long survey flight would otherwise accumulate a cell per chunk visited
	 * forever. Flagged chunks and point-blank verdicts are never dropped — those
	 * are the results — so only quiet, distant, unjudged cells are evicted, and
	 * re-entering their area simply re-scans them.
	 */
	private void evictDistantQuietChunks(MinecraftClient client) {
		if (evidence.size() <= MAX_TRACKED_CHUNKS) return;
		int playerChunkX = client.player.getBlockPos().getX() >> 4;
		int playerChunkZ = client.player.getBlockPos().getZ() >> 4;
		evidence.entrySet().removeIf(entry -> {
			Evidence cell = entry.getValue();
			if (cell.confirmed || cell.cleared || flagged.contains(entry.getKey())) return false;
			if (cell.total() > 0.5) return false;
			long key = entry.getKey();
			int dx = Math.abs(ChunkPos.getPackedX(key) - playerChunkX);
			int dz = Math.abs(ChunkPos.getPackedZ(key) - playerChunkZ);
			return Math.max(dx, dz) > 96;
		});
	}

	private void decayTraffic() {
		for (Evidence cell : evidence.values()) {
			if (cell.trafficScore > 0.0) {
				cell.trafficScore *= TRAFFIC_DECAY;
				if (cell.trafficScore < 0.05) {
					cell.trafficScore = 0.0;
					cell.soundCount = cell.updateCount = cell.eventCount = cell.particleCount = 0;
				}
			}
		}
	}

	private void runEntityCensus(MinecraftClient client) {
		// Sightings LATCH: entity tracking only reaches ~5 chunks, so a survey
		// flight sees each base's entities for seconds. The flag must outlive
		// the flyover — scores decay over minutes instead of resetting, and a
		// fresh sweep can only raise them.
		for (Evidence cell : evidence.values()) cell.censusScore *= 0.996;
		Map<Long, Double> sweep = new HashMap<>();
		ClientWorld world = client.world;
		for (Entity entity : world.getEntities()) {
			if (entity == client.player) continue;
			double y = entity.getY();
			if (y >= 0.0) {
				// Above zero only counts when buried well below the surface.
				int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING, entity.getBlockX(), entity.getBlockZ());
				if (y > surface - BURIED_MARGIN) continue;
			}
			double weight = weightForEntity(entity);
			if (weight <= 0.0) continue;
			// Below the send boundary the census IS the static channel — the
			// server tracks these entities to us while hiding every block, so
			// they carry more of the case than they would in rendered space.
			if (y < 0.0) weight *= 1.6;
			Evidence cell = cellFor(entity.getX(), entity.getZ());
			if (cell == null) continue;
			long key = ChunkPos.toLong(MathHelper.floor(entity.getX()) >> 4, MathHelper.floor(entity.getZ()) >> 4);
			sweep.merge(key, weight, Double::sum);
			cell.lastTouchedTick = client.player.age;
		}
		for (Map.Entry<Long, Double> entry : sweep.entrySet()) {
			Evidence cell = evidence.get(entry.getKey());
			if (cell != null) {
				cell.censusScore = Math.min(CAP_CENSUS, Math.max(cell.censusScore, entry.getValue()));
			}
		}
		rectsDirty = true;
	}

	private static double weightForEntity(Entity entity) {
		if (entity instanceof ItemFrameEntity || entity instanceof ArmorStandEntity) return W_FURNITURE_ENTITY;
		if (entity instanceof AbstractMinecartEntity) return W_CHEST_MINECART;
		if (entity instanceof PlayerEntity) return W_PLAYER_BELOW;
		if (entity instanceof net.minecraft.entity.passive.VillagerEntity) return W_VILLAGER;
		// Farm output is entity-tracked even when the blocks are withheld:
		// drops riding collection streams, and XP that only players, furnaces
		// and breeding can mint. Natural drops despawn in five minutes, so a
		// standing population of them below zero is a machine at work.
		if (entity instanceof net.minecraft.entity.ItemEntity) return 0.7;
		if (entity instanceof net.minecraft.entity.ExperienceOrbEntity) return 1.1;
		if (entity.hasCustomName()) return W_NAMED_MOB;
		if (entity instanceof PassiveEntity) return W_MOVED_ANIMAL;
		return 0.0; // hostiles are what caves hand out for free
	}

	private void enqueueScans(MinecraftClient client) {
		Vec3d centre = client.player.getEntityPos();
		scanCentreChunkX = MathHelper.floor(centre.x) >> 4;
		scanCentreChunkZ = MathHelper.floor(centre.z) >> 4;
		int radius = MathHelper.clamp((int) Math.ceil(
				MathHelper.clamp(config.donutPrimeChunkRange, 48, 1024) / 16.0), 3, 32);
		List<long[]> order = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				order.add(new long[]{scanCentreChunkX + dx, scanCentreChunkZ + dz, (long) dx * dx + (long) dz * dz});
			}
		}
		order.sort(Comparator.comparingLong(entry -> entry[2]));
		scanQueue.clear();
		scanQueue.addAll(order);
	}

	private void drainScanQueue(MinecraftClient client, int tick) {
		if (scanQueue.isEmpty() || ScanBudget.isChunkLoadBusy(client)) return;
		long pool = ScanBudget.takeBudget(tick, ScanBudget.Lane.PRIME_CHUNK, config);
		if (pool <= 0) return;
		long start = System.nanoTime();
		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) break;
			long[] next = scanQueue.poll();
			scanChunk(client, (int) next[0], (int) next[1]);
		}
		ScanBudget.reportUsed(tick, ScanBudget.Lane.PRIME_CHUNK, System.nanoTime() - start);
	}

	/** One chunk's static leak audit: light, block entities, palette. */
	private void scanChunk(MinecraftClient client, int chunkX, int chunkZ) {
		ClientWorld world = client.world;
		WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
		if (chunk == null) return;
		long key = ChunkPos.toLong(chunkX, chunkZ);
		Evidence cell = evidence.computeIfAbsent(key, ignored -> new Evidence());

		// Light through the mask: block light inside render-solid stone below 0.
		int litSamples = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int baseX = chunkX << 4, baseZ = chunkZ << 4;
		for (int y = LIGHT_MIN_Y; y <= LIGHT_MAX_Y; y += LIGHT_STRIDE_Y) {
			for (int x = 0; x < 16; x += LIGHT_STRIDE_XZ) {
				for (int z = 0; z < 16; z += LIGHT_STRIDE_XZ) {
					pos.set(baseX + x, y, baseZ + z);
					if (world.getLightLevel(LightType.BLOCK, pos) <= 0) continue;
					BlockState visible = world.getBlockState(pos);
					// Glowing air/cave is natural (lava pockets, geodes); light
					// registered INSIDE an opaque block is the mask lying.
					if (visible.isOpaqueFullCube()) litSamples++;
				}
			}
		}
		// Orphan light at the send boundary — the channel that works when the
		// server WITHHOLDS everything below zero instead of masking it with
		// stone. A hidden torch at y=-3 still lights the rendered band at
		// y=0..11, but its emitter block does not exist in our world copy. So:
		// find lit air low in the rendered band, hill-climb the light gradient
		// to its peak, and if no rendered block near the peak actually emits,
		// the source is under the floor. Caves are self-clearing — their lava,
		// lichen and torches ARE rendered, so the emitter check finds them.
		int orphanSamples = 0;
		for (int x = 0; x < 16 && orphanSamples < 5; x += LIGHT_STRIDE_XZ) {
			for (int z = 0; z < 16 && orphanSamples < 5; z += LIGHT_STRIDE_XZ) {
				for (int y = 0; y <= 12; y += 3) {
					pos.set(baseX + x, y, baseZ + z);
					if (world.getLightLevel(LightType.BLOCK, pos) < 5) continue;
					if (!world.getBlockState(pos).isAir()) continue;
					if (isOrphanLight(world, baseX + x, y, baseZ + z)) orphanSamples++;
					break;
				}
			}
		}
		double freshLight = Math.min(CAP_LIGHT,
				Math.max(litSamples * W_LIGHT_CELL, orphanSamples * 0.9));

		// Leaked block entities below the hidden line.
		int containers = 0;
		boolean spawner = false;
		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			BlockPos bePos = entry.getKey();
			if (bePos.getY() >= 0) {
				int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bePos.getX(), bePos.getZ());
				if (bePos.getY() > surface - BURIED_MARGIN) continue;
			}
			BlockEntityType<?> type = entry.getValue().getType();
			if (type == BlockEntityType.MOB_SPAWNER || type == BlockEntityType.TRIAL_SPAWNER
					|| type == BlockEntityType.VAULT) {
				spawner = true;
			} else if (isContainerType(type)) {
				containers++;
			}
		}
		double freshContainers = containers == 0 ? 0.0
				: Math.min(CAP_CONTAINER, W_CONTAINER_FIRST + (containers - 1) * W_CONTAINER_EXTRA);

		// Palette anomalies in the below-zero sections. The deepest band gets
		// half weight (ancient cities and aquifers live down there); below −48
		// is ignored outright.
		double palette = 0.0;
		int bottomY = world.getBottomY();
		for (int sectionY = -48; sectionY < 0; sectionY += 16) {
			int index = (sectionY - bottomY) >> 4;
			ChunkSection section = validSection(chunk, index);
			if (section == null || section.isEmpty()) continue;
			if (section.hasAny(state -> MAN_MADE.contains(state.getBlock()))) {
				palette += sectionY < -32 ? W_PALETTE * W_PALETTE_DEEP_SCALE : W_PALETTE;
			}
		}
		double freshPalette = Math.min(W_PALETTE * 2, palette);

		// Improperly rotated pillars. Scanned across the whole hidden column
		// rather than the palette channel's −48..0 window, because the deep
		// bands that make palette evidence unreliable — ancient cities,
		// aquifers, the lava sea — do not generate sideways deepslate either.
		// Depth costs this channel nothing, so it gets the full range.
		double rotation = 0.0;
		boolean deepDark = false;
		for (int sectionY = bottomY; sectionY < 0; sectionY += 16) {
			int index = (sectionY - bottomY) >> 4;
			ChunkSection section = validSection(chunk, index);
			if (section == null || section.isEmpty()) continue;
			if (section.hasAny(PrimeChunkFinder::improperlyRotated)) rotation += W_ROTATION;
			if (!deepDark && section.hasAny(PrimeChunkFinder::ancientCityMarker)) deepDark = true;
		}
		// An ancient city is stamped down from templates that are rotated as a
		// unit, so its deepslate genuinely does lie sideways with nobody's hand
		// involved — the one natural counterexample this channel has. Reinforced
		// deepslate generates nowhere else in the game, which makes it a clean
		// marker to stand the channel down on rather than flag a whole city.
		double freshRotation = deepDark ? 0.0 : Math.min(CAP_ROTATION, rotation);

		// Growth clock. Kelp: worldgen stalks are short; stalks that have grown
		// tall mean the chunk has spent long stretches loaded by players.
		int kelpStalks = 0, tallStalks = 0;
		for (int x = 2; x < 16 && kelpStalks < 12; x += 5) {
			for (int z = 2; z < 16 && kelpStalks < 12; z += 5) {
				int wx = baseX + x, wz = baseZ + z;
				if (world.getTopY(Heightmap.Type.MOTION_BLOCKING, wx, wz) > 64) continue;
				int y = 61;
				pos.set(wx, y, wz);
				while (y > 20 && !world.getFluidState(pos).isEmpty()
						&& world.getBlockState(pos).getBlock() != Blocks.KELP
						&& world.getBlockState(pos).getBlock() != Blocks.KELP_PLANT) {
					y--;
					pos.set(wx, y, wz);
				}
				int height = 0;
				while (y > 20 && (world.getBlockState(pos).getBlock() == Blocks.KELP
						|| world.getBlockState(pos).getBlock() == Blocks.KELP_PLANT)) {
					height++;
					y--;
					pos.set(wx, y, wz);
				}
				if (height > 0) {
					kelpStalks++;
					if (height >= 10) tallStalks++;
				}
			}
		}
		double heat = kelpStalks >= 3 ? (tallStalks / (double) kelpStalks) * CAP_GROWTH_HEAT : 0.0;

		// Geodes: budding amethyst with every bud stage stripped is a player's
		// harvest route; a palette of only fully-grown clusters is long-loaded.
		// Cultivated plants read the same way, and cost the same: maturity lives
		// in the block state, so every question below is answered off the
		// section palette without walking a single column.
		boolean budding = false, anyBud = false, cluster = false;
		boolean thickBamboo = false, ripeCocoa = false;
		boolean caveVines = false, berries = false, vines = false;
		for (ChunkSection section : chunk.getSectionArray()) {
			if (section == null || section.isEmpty()) continue;
			if (!budding && section.hasAny(state -> state.getBlock() == Blocks.BUDDING_AMETHYST)) budding = true;
			if (!anyBud && section.hasAny(state -> {
				Block block = state.getBlock();
				return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD
						|| block == Blocks.LARGE_AMETHYST_BUD;
			})) anyBud = true;
			if (!cluster && section.hasAny(state -> state.getBlock() == Blocks.AMETHYST_CLUSTER)) cluster = true;
			// Bamboo thickens only once a stalk has grown tall, so age=1 is a
			// stalk that has been random-ticked many times over.
			if (!thickBamboo && section.hasAny(state -> state.isOf(Blocks.BAMBOO)
					&& propertyValue(state, "age").equals("1"))) thickBamboo = true;
			if (!ripeCocoa && section.hasAny(state -> state.isOf(Blocks.COCOA)
					&& propertyValue(state, "age").equals("2"))) ripeCocoa = true;
			if (!caveVines && section.hasAny(state -> state.isOf(Blocks.CAVE_VINES)
					|| state.isOf(Blocks.CAVE_VINES_PLANT))) caveVines = true;
			if (!berries && section.hasAny(state -> (state.isOf(Blocks.CAVE_VINES)
					|| state.isOf(Blocks.CAVE_VINES_PLANT))
					&& propertyValue(state, "berries").equals("true"))) berries = true;
			if (!vines && section.hasAny(state -> state.isOf(Blocks.VINE))) vines = true;
		}

		double harvest = budding && !anyBud && !cluster ? W_HARVESTED_GEODE : 0.0;
		if (budding && cluster && !anyBud) heat += 0.4; // all buds fully mature
		// Glow berries regrow on random ticks and are picked by hand. A chunk
		// hung with cave vines and not one berry on any of them is somebody's
		// harvest round — the same tell as a geode stripped of its buds.
		if (caveVines && !berries) harvest += W_HARVESTED_BERRIES;
		double freshHarvest = Math.min(CAP_HARVEST, harvest);

		// Cultivated growth. These never flag a chunk on their own — heat is
		// capped well under every threshold and only speaks for a chunk that
		// already testifies — because lush caves grow their own vines and
		// jungles their own bamboo. What they are good for is separating a
		// chunk somebody has been standing in from one that merely generated.
		if (thickBamboo) heat += 0.5;
		if (ripeCocoa) heat += 0.5;
		if (berries) heat += 0.3;
		if (vines) heat += 0.2;
		commitEvidence(client, cell, chunkX, chunkZ, freshLight, freshContainers, spawner,
				freshPalette, freshRotation, freshHarvest, Math.min(CAP_GROWTH_HEAT, heat));
		rectsDirty = true;
	}

	/**
	 * Folds a fresh scan into a chunk's standing evidence.
	 *
	 * <p>Static evidence is kept at its <b>high-water mark</b> rather than
	 * overwritten, and that is the whole point of this method. The light channel
	 * — the one that finds bases nobody else can — works by catching block light
	 * glowing inside what renders as solid stone. Walking closer makes the server
	 * send the real blocks, the fake stone becomes the real room, and the light
	 * is no longer inside anything. The evidence is destroyed <em>by the act of
	 * going to look at it</em>. Recomputing straight over the top meant a flag
	 * evaporated exactly when you approached it, while fresh chunks kept flagging
	 * at the far edge of the scan — so the base appeared to flee. It never moved;
	 * the proof did.
	 *
	 * <p>Latching alone would make every flag permanent, so it is paired with a
	 * verdict: see {@link #VERDICT_RADIUS_CHUNKS}.
	 */
	private void commitEvidence(MinecraftClient client, Evidence cell, int chunkX, int chunkZ,
			double light, double containers, boolean spawner, double palette, double rotation,
			double harvest, double heat) {
		boolean pointBlank = withinVerdictRadius(client, chunkX, chunkZ);
		if (pointBlank) {
			// Close enough that the client certainly holds the real blocks, so
			// this scan is a verdict rather than a sample. Judge on the channels
			// that survive being looked at — containers, palette, rotation,
			// harvest, spawners.
			//
			// Unless the light channel is still firing. Light inside render-solid
			// stone at point-blank range means the server is masking this chunk
			// even here, so the real blocks were never delivered and there is
			// nothing to have a verdict about. Some servers hide the deep bands
			// at every distance; clearing those would throw away exactly the
			// bases this module exists to find.
			boolean maskStillOn = light > 0.01;
			double durable = containers + palette + rotation + harvest;
			if (!maskStillOn && durable < 0.5 && !spawner && cell.trafficScore < 0.5) {
				cell.cleared = true;
				cell.lightScore = 0.0;
				cell.blockEntityScore = 0.0;
				cell.paletteScore = 0.0;
				cell.rotationScore = 0.0;
				cell.harvestScore = 0.0;
				cell.growthHeat = 0.0;
				cell.spawnerPresent = false;
				return;
			}
			cell.cleared = false;
			cell.confirmed = true;
		}
		cell.lightScore = Math.max(cell.lightScore, light);
		cell.blockEntityScore = Math.max(cell.blockEntityScore, containers);
		cell.paletteScore = Math.max(cell.paletteScore, palette);
		cell.rotationScore = Math.max(cell.rotationScore, rotation);
		cell.harvestScore = Math.max(cell.harvestScore, harvest);
		cell.growthHeat = Math.max(cell.growthHeat, heat);
		cell.spawnerPresent |= spawner;
		cell.lastTouchedTick = client.player.age;
	}

	/** True when the chunk is close enough that the server cannot still be masking it. */
	private static boolean withinVerdictRadius(MinecraftClient client, int chunkX, int chunkZ) {
		int playerChunkX = client.player.getBlockPos().getX() >> 4;
		int playerChunkZ = client.player.getBlockPos().getZ() >> 4;
		return Math.abs(playerChunkX - chunkX) <= VERDICT_RADIUS_CHUNKS
				&& Math.abs(playerChunkZ - chunkZ) <= VERDICT_RADIUS_CHUNKS;
	}

	/**
	 * True for a pillar block turned off the axis world generation would have
	 * given it. Absent the axis property entirely, the answer is no — a block
	 * that cannot record a rotation cannot record a wrong one.
	 */
	private static boolean improperlyRotated(BlockState state) {
		if (!NATURAL_Y_PILLARS.contains(state.getBlock())) return false;
		String axis = propertyValue(state, "axis");
		return !axis.isEmpty() && !axis.equals("y");
	}

	/** Blocks that only exist where an ancient city or the deep dark generated. */
	private static boolean ancientCityMarker(BlockState state) {
		return state.isOf(Blocks.REINFORCED_DEEPSLATE) || state.isOf(Blocks.SCULK_CATALYST)
				|| state.isOf(Blocks.SCULK_SHRIEKER) || state.isOf(Blocks.SCULK_SENSOR);
	}

	/**
	 * Reads a block property by name, empty string when absent. By name rather
	 * than by {@code Properties} constant so a mapping rename downgrades a
	 * signal to silence instead of breaking the build.
	 */
	private static String propertyValue(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) return valueOf(state, property);
		}
		return "";
	}

	private static <T extends Comparable<T>> String valueOf(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	/**
	 * Follow the block-light gradient uphill from a lit air cell to its peak,
	 * then look for a rendered emitter around that peak. Light data is sent
	 * separately from block data, so on withholding servers the gradient often
	 * continues below y=0 into "air" the server never populated — and at the
	 * peak nothing luminous exists in our copy. That is a light source the
	 * server is hiding: somebody's torch, lantern or lava farm under the floor.
	 */
	private static boolean isOrphanLight(ClientWorld world, int x, int y, int z) {
		BlockPos.Mutable pos = new BlockPos.Mutable(x, y, z);
		BlockPos.Mutable probe = new BlockPos.Mutable();
		int level = world.getLightLevel(LightType.BLOCK, pos);
		for (int step = 0; step < 20; step++) {
			int bestLevel = level;
			int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
			int nx = bx, ny = by, nz = bz;
			for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
				probe.set(bx + dir.getOffsetX(), by + dir.getOffsetY(), bz + dir.getOffsetZ());
				if (probe.getY() < -8) continue;
				int neighbour = world.getLightLevel(LightType.BLOCK, probe);
				if (neighbour > bestLevel) {
					bestLevel = neighbour;
					nx = probe.getX();
					ny = probe.getY();
					nz = probe.getZ();
				}
			}
			if (bestLevel == level && nx == bx && ny == by && nz == bz) break;
			level = bestLevel;
			pos.set(nx, ny, nz);
		}
		// At the peak: any rendered block within a 3x3x3 that actually emits?
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					probe.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
					if (world.getBlockState(probe).getLuminance() > 0) return false;
				}
			}
		}
		// No visible emitter — orphan light. Only trust it when the peak sits
		// at or under the send boundary, where a hidden source must live.
		return pos.getY() <= 2;
	}

	private static ChunkSection validSection(WorldChunk chunk, int index) {
		ChunkSection[] sections = chunk.getSectionArray();
		return index >= 0 && index < sections.length ? sections[index] : null;
	}

	private static boolean isContainerType(BlockEntityType<?> type) {
		return type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
				|| type == BlockEntityType.BARREL || type == BlockEntityType.SHULKER_BOX
				|| type == BlockEntityType.FURNACE || type == BlockEntityType.BLAST_FURNACE
				|| type == BlockEntityType.SMOKER || type == BlockEntityType.HOPPER
				|| type == BlockEntityType.BREWING_STAND || type == BlockEntityType.ENDER_CHEST
				|| type == BlockEntityType.DISPENSER || type == BlockEntityType.DROPPER
				|| type == BlockEntityType.CRAFTER;
	}

	// ── Flags and rectangle merging ───────────────────────────────────────────

	private double threshold() {
		double weight = MathHelper.clamp(config.donutPrimeChunkWeight, 0, 100) / 100.0;
		return MathHelper.lerp(weight, THRESHOLD_STRICT, THRESHOLD_LOOSE);
	}

	private void refreshFlags() {
		if (!rectsDirty) return;
		double line = threshold();
		Set<Long> nextFlags = new HashSet<>();
		Set<Long> nextStrong = new HashSet<>();
		// First pass: intrinsic scores.
		Map<Long, Double> totals = new HashMap<>();
		for (Map.Entry<Long, Evidence> entry : evidence.entrySet()) {
			double total = entry.getValue().total();
			if (total > 0.0) totals.put(entry.getKey(), total);
		}
		// Second pass: adjacency amplification — only for chunks with their own
		// voice, so one loud chunk cannot paint a silent neighbourhood.
		for (Map.Entry<Long, Double> entry : totals.entrySet()) {
			double own = entry.getValue();
			long key = entry.getKey();
			int chunkX = ChunkPos.getPackedX(key);
			int chunkZ = ChunkPos.getPackedZ(key);
			int loudNeighbours = 0;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					Double neighbour = totals.get(ChunkPos.toLong(chunkX + dx, chunkZ + dz));
					if (neighbour != null && neighbour >= line) loudNeighbours++;
				}
			}
			// The floor gates only the amplification — any chunk may still flag
			// on its own merits, which is what lets Weight 100 actually behave
			// like Weight 100.
			double bonus = own >= NEIGHBOUR_NEEDS_OWN
					? Math.min(NEIGHBOUR_BONUS_CAP, loudNeighbours * NEIGHBOUR_BONUS) : 0.0;
			double amplified = own * (1.0 + bonus);
			if (amplified >= line) {
				nextFlags.add(key);
				if (amplified >= line * STRONG_TIER) nextStrong.add(key);
			}
		}
		rectsDirty = false;
		if (nextFlags.equals(flagged) && nextStrong.equals(strong)) return;
		flagged.clear();
		flagged.addAll(nextFlags);
		strong.clear();
		strong.addAll(nextStrong);
		rebuildRects();
	}

	/**
	 * Greedy row-run merge: grow east along a row, then south while every
	 * column of the strip matches, per tier. A base becomes one sheet.
	 */
	private void rebuildRects() {
		List<Rect> merged = new ArrayList<>();
		Set<Long> consumed = new HashSet<>();
		List<Long> ordered = new ArrayList<>(flagged);
		ordered.sort(Comparator.comparingInt(ChunkPos::getPackedZ).thenComparingInt(ChunkPos::getPackedX));
		for (long seed : ordered) {
			if (consumed.contains(seed) || merged.size() >= MAX_RECTS) break;
			if (consumed.contains(seed)) continue;
			boolean tier = strong.contains(seed);
			int startX = ChunkPos.getPackedX(seed);
			int startZ = ChunkPos.getPackedZ(seed);
			int endX = startX;
			while (isMergeable(endX + 1, startZ, tier, consumed)) endX++;
			int endZ = startZ;
			outer:
			while (true) {
				for (int x = startX; x <= endX; x++) {
					if (!isMergeable(x, endZ + 1, tier, consumed)) break outer;
				}
				endZ++;
			}
			for (int x = startX; x <= endX; x++) {
				for (int z = startZ; z <= endZ; z++) {
					consumed.add(ChunkPos.toLong(x, z));
				}
			}
			merged.add(new Rect(startX, startZ, endX, endZ, tier));
		}
		rects = List.copyOf(merged);
	}

	private boolean isMergeable(int chunkX, int chunkZ, boolean tier, Set<Long> consumed) {
		long key = ChunkPos.toLong(chunkX, chunkZ);
		return flagged.contains(key) && !consumed.contains(key) && strong.contains(key) == tier;
	}

	// ── Rendering ─────────────────────────────────────────────────────────────

	public void renderWorld(WorldRenderContext ctx) {
		if (failedClosed || !config.enabled || !config.donutPrimeChunk || rects.isEmpty()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;

			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();

			// The immediate provider owns one active buffer at a time: finish
			// all fills before the lines layer flushes them.
			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (Rect rect : rects) {
				int color = rect.strongTier() ? COLOR_STRONG : COLOR_PALE;
				float alpha = rect.strongTier() ? 0.32F : 0.24F;
				DonutWorldRenderer.drawFlatTop(fills, pos, rect.box(), camera, color, alpha);
			}

			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			for (Rect rect : rects) {
				int color = rect.strongTier() ? COLOR_STRONG : COLOR_PALE;
				DonutWorldRenderer.drawOutline(lines, pos, entry, rect.box(), camera, color, 0.85F);
			}

			if (config.donutPrimeChunkTracers) {
				NovaTracers.Basis basis = NovaTracers.basisFor(mc.gameRenderer.getCamera());
				List<Rect> nearest = new ArrayList<>(rects);
				nearest.sort(Comparator.comparingDouble(rect ->
						rect.centre().squaredDistanceTo(mc.player.getEntityPos())));
				int drawn = 0;
				for (Rect rect : nearest) {
					if (drawn++ >= MAX_TRACERS) break;
					NovaTracers.draw(lines, pos, entry, basis, rect.centre(), COLOR_STRONG, 0.9F);
				}
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Prime Chunk Finder render failed; disabling it to protect the client.", exception);
			reset();
			config.donutPrimeChunk = false;
			failedClosed = true;
		}
	}

	private static Set<Block> buildManMadeSet() {
		Set<Block> set = new HashSet<>(List.of(
				// Farm and machinery blocks: none of these generate below zero,
				// and unlike light evidence they SURVIVE the mask lifting when
				// the player gets close — which keeps real-base flags stable.
				Blocks.BAMBOO, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.MELON, Blocks.PUMPKIN,
				Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
				Blocks.NETHER_WART, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.COCOA,
				Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.OBSERVER, Blocks.REDSTONE_LAMP,
				Blocks.TARGET, Blocks.REDSTONE_BLOCK,
				Blocks.GLASS, Blocks.GLASS_PANE, Blocks.TINTED_GLASS,
				Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
				Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
				Blocks.DAMAGED_ANVIL, Blocks.BEACON, Blocks.CONDUIT, Blocks.LODESTONE,
				Blocks.RESPAWN_ANCHOR, Blocks.LECTERN, Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF,
				Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE,
				Blocks.LOOM, Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.CAMPFIRE,
				Blocks.SOUL_CAMPFIRE, Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK,
				Blocks.EMERALD_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.HAY_BLOCK, Blocks.HONEY_BLOCK,
				Blocks.SLIME_BLOCK, Blocks.TNT, Blocks.ENDER_CHEST, Blocks.SHULKER_BOX));
		for (Block bed : new Block[]{Blocks.WHITE_BED, Blocks.RED_BED, Blocks.BLUE_BED, Blocks.CYAN_BED,
				Blocks.GREEN_BED, Blocks.YELLOW_BED, Blocks.BLACK_BED, Blocks.GRAY_BED, Blocks.PURPLE_BED,
				Blocks.ORANGE_BED, Blocks.PINK_BED, Blocks.LIME_BED, Blocks.BROWN_BED, Blocks.MAGENTA_BED,
				Blocks.LIGHT_BLUE_BED, Blocks.LIGHT_GRAY_BED}) {
			set.add(bed);
		}
		for (Block wool : new Block[]{Blocks.WHITE_WOOL, Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.CYAN_WOOL,
				Blocks.GREEN_WOOL, Blocks.YELLOW_WOOL, Blocks.BLACK_WOOL, Blocks.GRAY_WOOL, Blocks.PURPLE_WOOL,
				Blocks.ORANGE_WOOL, Blocks.PINK_WOOL, Blocks.LIME_WOOL, Blocks.BROWN_WOOL, Blocks.MAGENTA_WOOL,
				Blocks.LIGHT_BLUE_WOOL, Blocks.LIGHT_GRAY_WOOL}) {
			set.add(wool);
		}
		for (Block concrete : new Block[]{Blocks.WHITE_CONCRETE, Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE,
				Blocks.CYAN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.BLACK_CONCRETE,
				Blocks.GRAY_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.PINK_CONCRETE,
				Blocks.LIME_CONCRETE, Blocks.BROWN_CONCRETE, Blocks.MAGENTA_CONCRETE,
				Blocks.LIGHT_BLUE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE}) {
			set.add(concrete);
		}
		return set;
	}
}
