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
 * Flags chunks likely to hold a base below the depth-hiding mask, and renders a wash
 * over them.
 *
 * <p>Evidence accumulates per chunk from independent channels (light through masked
 * blocks, leaked block entities, palette anomalies, off-axis pillars, growth maturity,
 * live packet traffic, entity census) and only the combined score crosses the flag line.
 * Static channels latch at their high-water mark and are retired by a point-blank verdict
 * rather than by decay; see {@link #VERDICT_RADIUS_CHUNKS}.
 */
public final class PrimeChunkFinder {
	// Evidence weights, summed against the threshold.
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
	// Growth maturity is an odometer of loaded time, capped below every threshold so it
	// can only amplify chunks that already have other evidence.
	private static final double CAP_GROWTH_HEAT = 1.2;
	private static final double W_HARVESTED_GEODE = 1.5;   // buds stripped from budding amethyst
	private static final double W_HARVESTED_BERRIES = 1.2; // cave vines picked bare
	private static final double CAP_HARVEST = 2.4;
	// Off-axis pillars have no natural explanation, so this channel is trusted at every depth.
	private static final double W_ROTATION = 2.6;
	private static final double CAP_ROTATION = 3.9;

	// Per-tick decay for live traffic; half-life is roughly 2 minutes.
	private static final double TRAFFIC_DECAY = 0.99985;
	// Threshold line: weight 0 maps to 5.5, weight 1 to 0.7 (default 0.40 gives 3.58).
	private static final double THRESHOLD_STRICT = 5.5;
	private static final double THRESHOLD_LOOSE = 0.7;
	// Strong tier begins at this multiple of the threshold.
	private static final double STRONG_TIER = 1.6;
	// Adjacency: +15% per flagged neighbour, at most +45%, and never from a zero score.
	private static final double NEIGHBOUR_BONUS = 0.15;
	private static final double NEIGHBOUR_BONUS_CAP = 0.45;
	private static final double NEIGHBOUR_NEEDS_OWN = 1.0;

	// Light scan band. Deeper bands are excluded: lava seas and ancient-city lanterns
	// would drown the signal in natural light.
	private static final int LIGHT_MIN_Y = -40;
	private static final int LIGHT_MAX_Y = -1;
	private static final int LIGHT_STRIDE_XZ = 4;
	private static final int LIGHT_STRIDE_Y = 4;
	// Above-zero evidence counts only this far under the heightmap.
	private static final int BURIED_MARGIN = 12;

	// Within this range any server streams real blocks, so a scan is a verdict rather than
	// a sample: surviving evidence confirms the chunk, absent evidence clears it for good.
	private static final int VERDICT_RADIUS_CHUNKS = 3;
	// Beyond this many tracked cells the quietest distant ones are evicted.
	private static final int MAX_TRACKED_CHUNKS = 20_000;

	private static final int RESCAN_INTERVAL_TICKS = 200; // static leaks re-read every 10s
	private static final int CENSUS_INTERVAL_TICKS = 20;
	private static final int MAX_RECTS = 192;
	private static final int MAX_TRACERS = 24;
	// The flat wash sits just above the deepslate boundary.
	private static final double SHEET_Y = 0.05;

	private static final int COLOR_STRONG = 0xE03434; // saturated red
	private static final int COLOR_PALE = 0xE8D8B8;   // borderline cream

	/** Blocks that do not occur below zero unless a player brought them. */
	private static final Set<Block> MAN_MADE = buildManMadeSet();

	// Pillar blocks worldgen only lays on the Y axis. Vanilla orients a placed pillar
	// along the clicked face, so an X- or Z-axis one was placed by hand.
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
		double lightScore;      // static scan, latched at its peak
		double blockEntityScore;
		double paletteScore;
		double rotationScore;   // natural pillars turned off-axis by hand
		double censusScore;
		double growthHeat;      // loaded-time odometer, capped sub-threshold
		double harvestScore;    // stripped geodes and picked vines
		double trafficScore;    // live packets, decays
		int soundCount, updateCount, eventCount, particleCount;
		boolean spawnerPresent;
		int lastTouchedTick;

		double total() {
			// A cleared verdict is permanent; this is the release valve on latched evidence.
			if (cleared) return 0.0;
			double containers = blockEntityScore;
			if (spawnerPresent && lightScore < 0.5) {
				containers = Math.min(containers, CAP_CONTAINER_DUNGEON);
			}
			double core = lightScore + containers + paletteScore + rotationScore + censusScore
					+ trafficScore + harvestScore + (spawnerPresent ? W_SPAWNER : 0.0);
			// Heat only counts once another channel has scored.
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

	/** Fast gate for the packet mixin so intake costs nothing while the module is off. */
	public static boolean listening() {
		return instance != null && !instance.failedClosed
				&& instance.config.enabled && instance.config.donutPrimeChunk;
	}

	/** True when the chunk is currently prime-flagged. */
	public static boolean isFlagged(long chunkKey) {
		return instance != null && instance.flagged.contains(chunkKey);
	}

	// Packet intake, called from PrimeSignalMixin on the client thread.

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

	/** True within 12 blocks of the player, so the player's own activity never flags a chunk. */
	private static boolean nearSelf(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return true;
		return client.player.squaredDistanceTo(x, y, z) < 12.0 * 12.0;
	}

	private void addTraffic(Evidence cell, double amount, boolean capped) {
		if (!capped) {
			cell.trafficScore += amount;
			rectsDirty = true; // the rebuild itself is gated by a flag-set comparison
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

	// Tick: decay, census, budgeted static scans.

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
			// The signal model only applies to the overworld's hidden depths.
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
			// Intentionally not saved, so a transient failure does not persist.
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

	/** Bounds the evidence map by evicting quiet, distant, unjudged cells. */
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
		// Entity tracking only reaches about 5 chunks, so sightings decay slowly rather
		// than resetting, and a fresh sweep can only raise a score.
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
			// Below the send boundary entities are still tracked while blocks are hidden.
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
		// Farm output stays entity-tracked when blocks are withheld. Natural drops despawn
		// in five minutes, so a standing population below zero implies a running farm.
		if (entity instanceof net.minecraft.entity.ItemEntity) return 0.7;
		if (entity instanceof net.minecraft.entity.ExperienceOrbEntity) return 1.1;
		if (entity.hasCustomName()) return W_NAMED_MOB;
		if (entity instanceof PassiveEntity) return W_MOVED_ANIMAL;
		return 0.0; // hostiles spawn naturally in caves
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

	/** Runs one chunk's static leak audit: light, block entities, palette, rotation, growth. */
	private void scanChunk(MinecraftClient client, int chunkX, int chunkZ) {
		ClientWorld world = client.world;
		WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
		if (chunk == null) return;
		long key = ChunkPos.toLong(chunkX, chunkZ);
		Evidence cell = evidence.computeIfAbsent(key, ignored -> new Evidence());

		// Block light registered inside render-solid stone below y=0.
		int litSamples = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int baseX = chunkX << 4, baseZ = chunkZ << 4;
		for (int y = LIGHT_MIN_Y; y <= LIGHT_MAX_Y; y += LIGHT_STRIDE_Y) {
			for (int x = 0; x < 16; x += LIGHT_STRIDE_XZ) {
				for (int z = 0; z < 16; z += LIGHT_STRIDE_XZ) {
					pos.set(baseX + x, y, baseZ + z);
					if (world.getLightLevel(LightType.BLOCK, pos) <= 0) continue;
					BlockState visible = world.getBlockState(pos);
					// Glowing air is natural; light inside an opaque block means the block is a mask.
					if (visible.isOpaqueFullCube()) litSamples++;
				}
			}
		}
		// Orphan light: handles servers that withhold everything below zero rather than
		// masking it. A hidden emitter still lights the rendered y=0..11 band while the
		// emitter block itself is absent from the client's copy.
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

		// Palette anomalies in below-zero sections. The -48..-33 band gets half weight for
		// ancient cities and aquifers; below -48 is ignored.
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

		// Off-axis pillars, scanned across the whole hidden column rather than the
		// palette channel's window, since no depth band generates sideways deepslate.
		double rotation = 0.0;
		boolean deepDark = false;
		for (int sectionY = bottomY; sectionY < 0; sectionY += 16) {
			int index = (sectionY - bottomY) >> 4;
			ChunkSection section = validSection(chunk, index);
			if (section == null || section.isEmpty()) continue;
			if (section.hasAny(PrimeChunkFinder::improperlyRotated)) rotation += W_ROTATION;
			if (!deepDark && section.hasAny(PrimeChunkFinder::ancientCityMarker)) deepDark = true;
		}
		// Ancient-city templates are rotated as a unit, so their deepslate does lie
		// sideways naturally. Stand the channel down when city markers are present.
		double freshRotation = deepDark ? 0.0 : Math.min(CAP_ROTATION, rotation);

		// Kelp height as a growth clock: worldgen stalks are short, and stalks only grow
		// on random ticks, which fire while a player keeps the chunk loaded.
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

		// Maturity lives in the block state, so these checks all run off the section
		// palette without walking any columns.
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
			// Bamboo only reaches age=1 after many random ticks on a tall stalk.
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
		// Cave vines with no berries anywhere means they were picked, since berries regrow
		// on random ticks.
		if (caveVines && !berries) harvest += W_HARVESTED_BERRIES;
		double freshHarvest = Math.min(CAP_HARVEST, harvest);

		// Growth heat is capped below every threshold, so it can never flag alone.
		if (thickBamboo) heat += 0.5;
		if (ripeCocoa) heat += 0.5;
		if (berries) heat += 0.3;
		if (vines) heat += 0.2;
		commitEvidence(client, cell, chunkX, chunkZ, freshLight, freshContainers, spawner,
				freshPalette, freshRotation, freshHarvest, Math.min(CAP_GROWTH_HEAT, heat));
		rectsDirty = true;
	}

	/**
	 * Folds a fresh scan into a chunk's standing evidence, keeping static channels at
	 * their high-water mark. Approaching a chunk destroys its light evidence, so latching
	 * is paired with the point-blank verdict at {@link #VERDICT_RADIUS_CHUNKS}.
	 */
	private void commitEvidence(MinecraftClient client, Evidence cell, int chunkX, int chunkZ,
			double light, double containers, boolean spawner, double palette, double rotation,
			double harvest, double heat) {
		boolean pointBlank = withinVerdictRadius(client, chunkX, chunkZ);
		if (pointBlank) {
			// Judge on the channels that survive inspection: containers, palette, rotation,
			// harvest, spawners. Light still firing at this range means the server is masking
			// even here, so there are no real blocks to adjudicate against.
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

	/** True for a natural pillar block whose axis property is set to something other than y. */
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
	 * Reads a block property by name, returning an empty string when absent. Looked up by
	 * name so a mapping rename silences a signal rather than breaking the build.
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
	 * True when the block-light gradient from a lit air cell peaks with no rendered emitter
	 * nearby, meaning the light source was withheld. Light data is sent separately from
	 * block data, so the gradient can lead into unpopulated space.
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
		// No visible emitter. Only trust this when the peak sits at or under the send boundary.
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
		// Second pass: adjacency amplification, restricted to chunks with their own score.
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
			// NEIGHBOUR_NEEDS_OWN gates only the bonus; a chunk can still flag on its own score.
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

	/** Greedy row-run merge: grow east along a row, then south while every column matches tier. */
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

			// The immediate provider holds one active buffer, so all fills must finish
			// before requesting the lines layer.
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
				// Farm and machinery blocks; none of these generate below zero, and unlike
				// light evidence they survive the mask lifting as the player approaches.
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
