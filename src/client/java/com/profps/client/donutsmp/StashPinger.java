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
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
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
 * Classifies loaded chunks as player bases and renders tracers to them.
 *
 * <p>Per-chunk evidence is scored by block family, adjacent evidence chunks merge into
 * sites, and sites above the temperature-derived threshold are announced.
 */
public final class StashPinger {
	// Family weights.
	private static final double W_FUNCTIONAL = 1.1;   // per distinct hit, capped
	private static final double CAP_FUNCTIONAL = 4.4;
	private static final double W_CONTAINER = 1.0;
	private static final double CAP_CONTAINER = 4.0;
	private static final double W_REDSTONE = 0.8;
	private static final double CAP_REDSTONE = 3.2;
	private static final double W_RESOURCE = 1.2;     // craft-only mineral blocks
	private static final double CAP_RESOURCE = 3.6;
	private static final double W_CARVED = 1.6;       // per boxed-out section, capped
	private static final double CAP_CARVED = 3.2;
	private static final double W_CROP = 0.4;         // per sampled farm block, capped
	private static final double CAP_CROP = 3.0;
	private static final double W_DEBRIS = 1.0;       // anti-signal accumulator
	private static final double PRIME_CORROBORATION = 1.0;

	// Site threshold: temperature 0 maps to 8.0, temperature 1 to 1.5.
	private static final double THRESHOLD_STRICT = 8.0;
	private static final double THRESHOLD_LOOSE = 1.5;
	// Debris must exceed this multiple of furniture to suppress a site.
	private static final double DEBRIS_DOMINANCE = 2.0;

	private static final int BURIED_MARGIN = 10;      // blocks below local surface before evidence counts
	private static final int SKY_MIN_Y = 100;         // minimum Y for a floating platform
	private static final int SKY_AIR_GAP = 16;        // required drop under a platform
	private static final int RESCAN_INTERVAL_TICKS = 240;
	private static final int ACTIONBAR_INTERVAL_TICKS = 10;
	private static final int MAX_TRACERS = 12;
	private static final int TRACER_COLOR = 0xF2D24B; // stash yellow
	private static final int PING_COLOR = 0xFFE066;

	private static final Style NOVA_FONT = Style.EMPTY
			.withFont(new StyleSpriteSource.Font(Identifier.of(ProFPS.MOD_ID, "nova")));

	private static final Set<Block> FUNCTIONAL = Set.of(
			Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
			Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
			Blocks.DAMAGED_ANVIL, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
			Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LECTERN,
			Blocks.BEACON, Blocks.ENDER_CHEST, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
			Blocks.BELL, Blocks.COMPOSTER, Blocks.CAULDRON, Blocks.WATER_CAULDRON);
	// Rails are excluded: mineshafts generate them below zero.
	private static final Set<Block> REDSTONE = Set.of(
			Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.OBSERVER, Blocks.REPEATER,
			Blocks.COMPARATOR, Blocks.REDSTONE_WIRE, Blocks.REDSTONE_BLOCK, Blocks.REDSTONE_LAMP,
			Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.NOTE_BLOCK, Blocks.TARGET,
			Blocks.LEVER, Blocks.DAYLIGHT_DETECTOR, Blocks.CRAFTER);
	// Craft-only blocks. Amethyst, bone and copper are excluded because worldgen produces them.
	private static final Set<Block> RESOURCE = Set.of(
			Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
			Blocks.NETHERITE_BLOCK, Blocks.COAL_BLOCK, Blocks.REDSTONE_BLOCK,
			Blocks.LAPIS_BLOCK, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK,
			Blocks.HAY_BLOCK, Blocks.DRIED_KELP_BLOCK);
	// Ancient-city signature; presence zeroes carved scoring and halves furniture.
	private static final Set<Block> CITY_MARKERS = Set.of(
			Blocks.SCULK, Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR,
			Blocks.SOUL_LANTERN, Blocks.REINFORCED_DEEPSLATE);
	private static final Set<Block> DEBRIS = Set.of(
			Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.RESPAWN_ANCHOR, Blocks.GLOWSTONE);
	private static final Set<Block> CONTAINERS = Set.of(
			Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
	// None of these generate underground; kelp and seagrass are excluded because oceans do.
	private static final Set<Block> CROPS = Set.of(
			Blocks.BAMBOO, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.MELON, Blocks.PUMPKIN,
			Blocks.MELON_STEM, Blocks.PUMPKIN_STEM, Blocks.WHEAT, Blocks.CARROTS,
			Blocks.POTATOES, Blocks.BEETROOTS, Blocks.NETHER_WART, Blocks.SWEET_BERRY_BUSH,
			Blocks.COCOA, Blocks.FARMLAND, Blocks.DIRT_PATH);

	private static StashPinger instance;

	private final ProFPSConfig config;

	private static final class ChunkEvidence {
		double functional, container, redstone, resource, carved, crops, debris;
		boolean playerSpawner;
		double ySum;
		int ySamples;

		double furniture() {
			return functional + container + redstone + resource + carved + crops;
		}
	}

	private record Site(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
			Vec3d centre, double score, boolean spawnerSite, long id) {}

	private final Map<Long, ChunkEvidence> evidence = new HashMap<>();
	private final ArrayDeque<long[]> scanQueue = new ArrayDeque<>();
	private List<Site> sites = List.of();
	private int nextRescanTick;
	private int nextActionBarTick;
	private int scanCentreChunkX = Integer.MIN_VALUE;
	private int scanCentreChunkZ = Integer.MIN_VALUE;
	private ClientWorld trackedWorld;
	private boolean failedClosed;

	public StashPinger(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	public void tick(MinecraftClient client) {
		if (failedClosed || !config.enabled || !config.donutStashPinger) {
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
			int tick = client.player.age;
			if (scanQueue.isEmpty() && (tick >= nextRescanTick
					|| ScanBudget.leftScanArea(client, scanCentreChunkX, scanCentreChunkZ, 4))) {
				nextRescanTick = tick + RESCAN_INTERVAL_TICKS;
				enqueueScans(client);
			}
			boolean scanned = drainScanQueue(client, tick);
			if (scanned) rebuildSites(client);
			if (tick >= nextActionBarTick) {
				nextActionBarTick = tick + ACTIONBAR_INTERVAL_TICKS;
				updateActionBar(client);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Stash Pinger failed; disabling it to protect the client.", exception);
			reset();
			config.donutStashPinger = false;
			// Intentionally not saved, so a transient failure does not persist.
			failedClosed = true;
		}
	}

	private void reset() {
		evidence.clear();
		scanQueue.clear();
		sites = List.of();
		trackedWorld = null;
		scanCentreChunkX = Integer.MIN_VALUE;
		scanCentreChunkZ = Integer.MIN_VALUE;
	}

	private void enqueueScans(MinecraftClient client) {
		Vec3d centre = client.player.getEntityPos();
		scanCentreChunkX = MathHelper.floor(centre.x) >> 4;
		scanCentreChunkZ = MathHelper.floor(centre.z) >> 4;
		int radius = MathHelper.clamp((int) Math.ceil(
				MathHelper.clamp(config.donutStashRange, 48, 1024) / 16.0), 3, 32);
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

	private boolean drainScanQueue(MinecraftClient client, int tick) {
		if (scanQueue.isEmpty() || ScanBudget.isChunkLoadBusy(client)) return false;
		long pool = ScanBudget.takeBudget(tick, ScanBudget.Lane.STASH_PINGER, config);
		if (pool <= 0) return false;
		long start = System.nanoTime();
		boolean any = false;
		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) break;
			long[] next = scanQueue.poll();
			scanChunk(client, (int) next[0], (int) next[1]);
			any = true;
		}
		ScanBudget.reportUsed(tick, ScanBudget.Lane.STASH_PINGER, System.nanoTime() - start);
		return any;
	}

	private void scanChunk(MinecraftClient client, int chunkX, int chunkZ) {
		ClientWorld world = client.world;
		WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
		if (chunk == null) return;
		long key = ChunkPos.toLong(chunkX, chunkZ);
		ChunkEvidence cell = new ChunkEvidence();

		// Evidence counts only below the surface line, or inside a floating platform's deck band.
		int surfaceMin = Integer.MAX_VALUE;
		for (int corner = 0; corner < 4; corner++) {
			int x = (chunkX << 4) + ((corner & 1) == 0 ? 2 : 13);
			int z = (chunkZ << 4) + ((corner & 2) == 0 ? 2 : 13);
			surfaceMin = Math.min(surfaceMin, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z));
		}
		int hiddenBelow = surfaceMin - BURIED_MARGIN;
		int[] skyBand = skyPlatformBand(world, chunk, chunkX, chunkZ, surfaceMin);

		boolean cityContext = false;
		int containers = 0;
		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			BlockPos pos = entry.getKey();
			if (!inCountedSpace(pos.getY(), hiddenBelow, skyBand)) continue;
			BlockEntityType<?> type = entry.getValue().getType();
			if (type == BlockEntityType.MOB_SPAWNER) {
				if (!isNaturalSpawnerContext(world, pos)) cell.playerSpawner = true;
			} else if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
					|| type == BlockEntityType.BARREL || type == BlockEntityType.SHULKER_BOX
					|| type == BlockEntityType.HOPPER || type == BlockEntityType.FURNACE
					|| type == BlockEntityType.BLAST_FURNACE || type == BlockEntityType.SMOKER
					|| type == BlockEntityType.BREWING_STAND) {
				containers++;
				touchY(cell, pos.getY());
			}
		}
		cell.container = Math.min(CAP_CONTAINER, containers * W_CONTAINER);

		// Section passes: palette prescreen first, strided count only on a hit.
		int bottomY = world.getBottomY();
		ChunkSection[] sections = chunk.getSectionArray();
		for (int index = 0; index < sections.length; index++) {
			ChunkSection section = sections[index];
			if (section == null || section.isEmpty()) continue;
			int sectionBottom = bottomY + (index << 4);
			if (!sectionCounted(sectionBottom, hiddenBelow, skyBand)) continue;
			boolean furniturePalette = section.hasAny(state -> {
				Block block = state.getBlock();
				return FUNCTIONAL.contains(block) || REDSTONE.contains(block)
						|| RESOURCE.contains(block) || CONTAINERS.contains(block)
						|| CROPS.contains(block);
			});
			boolean debrisPalette = section.hasAny(state -> DEBRIS.contains(state.getBlock()));
			if (furniturePalette || debrisPalette) {
				countSection(world, chunk, cell, chunkX, chunkZ, sectionBottom);
			}
			if (section.hasAny(state -> CITY_MARKERS.contains(state.getBlock()))) {
				cityContext = true;
			}
			// No carving credit below Y -48, where ancient-city platforms generate flat floors.
			if (sectionBottom < hiddenBelow && sectionBottom >= -48) {
				cell.carved += carvedRoomScore(world, chunkX, chunkZ, sectionBottom);
			}
		}
		cell.functional = Math.min(CAP_FUNCTIONAL, cell.functional);
		cell.redstone = Math.min(CAP_REDSTONE, cell.redstone);
		cell.resource = Math.min(CAP_RESOURCE, cell.resource);
		cell.carved = Math.min(CAP_CARVED, cell.carved);
		cell.crops = Math.min(CAP_CROP, cell.crops);
		if (cityContext) {
			cell.carved = 0.0;
			cell.functional *= 0.5;
			cell.container *= 0.5;
			cell.redstone *= 0.5;
			cell.resource *= 0.5;
		}

		if (cell.playerSpawner || cell.furniture() > 0.0 || cell.debris > 0.0) {
			evidence.put(key, cell);
		} else {
			evidence.remove(key);
		}
	}

	/** Counts category hits on a stride-2 lattice through one 16-block section. */
	private void countSection(ClientWorld world, WorldChunk chunk, ChunkEvidence cell,
			int chunkX, int chunkZ, int sectionBottom) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int baseX = chunkX << 4, baseZ = chunkZ << 4;
		for (int y = sectionBottom; y < sectionBottom + 16; y += 2) {
			for (int x = 0; x < 16; x += 2) {
				for (int z = 0; z < 16; z += 2) {
					pos.set(baseX + x, y, baseZ + z);
					Block block = chunk.getBlockState(pos).getBlock();
					if (FUNCTIONAL.contains(block)) {
						cell.functional += W_FUNCTIONAL;
						touchY(cell, y);
					} else if (REDSTONE.contains(block)) {
						cell.redstone += W_REDSTONE;
						touchY(cell, y);
					} else if (RESOURCE.contains(block)) {
						cell.resource += W_RESOURCE;
						touchY(cell, y);
					} else if (CROPS.contains(block)) {
						cell.crops += W_CROP;
						touchY(cell, y);
					} else if (DEBRIS.contains(block)) {
						cell.debris += W_DEBRIS * 0.25; // scaled for the stride-2 sampling
					}
				}
			}
		}
	}

	/** True when mossy cobblestone or cobwebs sit within 4 blocks, marking a dungeon or mineshaft. */
	private static boolean isNaturalSpawnerContext(ClientWorld world, BlockPos spawner) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					pos.set(spawner.getX() + dx, spawner.getY() + dy, spawner.getZ() + dz);
					Block block = world.getBlockState(pos).getBlock();
					if (block == Blocks.MOSSY_COBBLESTONE || block == Blocks.COBWEB) return true;
				}
			}
		}
		return false;
	}

	/** Scores a section when the floors under its air pockets concentrate on one Y level. */
	private static double carvedRoomScore(ClientWorld world, int chunkX, int chunkZ, int sectionBottom) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int baseX = chunkX << 4, baseZ = chunkZ << 4;
		int[] floorHistogram = new int[18];
		int floorsFound = 0;
		for (int x = 1; x < 16; x += 2) {
			for (int z = 1; z < 16; z += 2) {
				for (int y = sectionBottom + 1; y < sectionBottom + 15; y++) {
					pos.set(baseX + x, y, baseZ + z);
					if (!world.getBlockState(pos).isAir()) continue;
					pos.setY(y + 1);
					if (!world.getBlockState(pos).isAir()) break;
					pos.setY(y - 1);
					BlockState floor = world.getBlockState(pos);
					// Farmland is 15/16 tall and farm channels are fluid, so both count as floors.
					if (floor.isOpaqueFullCube() || floor.getBlock() == Blocks.FARMLAND
							|| floor.getBlock() == Blocks.DIRT_PATH
							|| !floor.getFluidState().isEmpty()) {
						floorHistogram[y - sectionBottom]++;
						floorsFound++;
					}
					break;
				}
			}
		}
		if (floorsFound < 24) return 0.0;
		int dominant = 0;
		for (int count : floorHistogram) dominant = Math.max(dominant, count);
		// 64 columns sampled; require 60% of found floors on one level, minimum 24.
		return dominant >= floorsFound * 0.6 && dominant >= 24 ? W_CARVED : 0.0;
	}

	/** {minY, maxY} of a floating platform's counted band, or null. */
	private static int[] skyPlatformBand(ClientWorld world, WorldChunk chunk,
			int chunkX, int chunkZ, int surfaceMin) {
		if (surfaceMin < SKY_MIN_Y) return null;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int floating = 0, samples = 0;
		for (int corner = 0; corner < 4; corner++) {
			int x = (chunkX << 4) + ((corner & 1) == 0 ? 3 : 12);
			int z = (chunkZ << 4) + ((corner & 2) == 0 ? 3 : 12);
			int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
			if (top < SKY_MIN_Y) continue;
			samples++;
			// Walk down through the deck, at most 12 blocks, then require SKY_AIR_GAP of air.
			int y = top;
			int walked = 0;
			pos.set(x, y, z);
			while (y > world.getBottomY() && walked < 12 && !world.getBlockState(pos).isAir()) {
				y--;
				walked++;
				pos.set(x, y, z);
			}
			int gap = 0;
			while (y > world.getBottomY() && gap < SKY_AIR_GAP && world.getBlockState(pos).isAir()) {
				y--;
				gap++;
				pos.set(x, y, z);
			}
			if (gap >= SKY_AIR_GAP) floating++;
		}
		if (samples == 0 || floating < Math.max(2, samples / 2)) return null;
		return new int[]{surfaceMin - 14, surfaceMin + 6};
	}

	private static boolean inCountedSpace(int y, int hiddenBelow, int[] skyBand) {
		if (y < hiddenBelow) return true;
		return skyBand != null && y >= skyBand[0] && y <= skyBand[1];
	}

	private static boolean sectionCounted(int sectionBottom, int hiddenBelow, int[] skyBand) {
		if (sectionBottom + 16 <= hiddenBelow) return true;
		return skyBand != null && sectionBottom + 16 > skyBand[0] && sectionBottom <= skyBand[1];
	}

	private static void touchY(ChunkEvidence cell, int y) {
		cell.ySum += y;
		cell.ySamples++;
	}

	private double threshold() {
		double temperature = MathHelper.clamp(config.donutStashTemperature, 0, 100) / 100.0;
		return MathHelper.lerp(temperature, THRESHOLD_STRICT, THRESHOLD_LOOSE);
	}

	private void rebuildSites(MinecraftClient client) {
		double line = threshold();
		Set<Long> unvisited = new HashSet<>(evidence.keySet());
		List<Site> next = new ArrayList<>();
		while (!unvisited.isEmpty()) {
			long seed = unvisited.iterator().next();
			// Flood-fill adjacent evidence chunks into one candidate site.
			ArrayDeque<Long> frontier = new ArrayDeque<>();
			frontier.add(seed);
			unvisited.remove(seed);
			List<Long> members = new ArrayList<>();
			while (!frontier.isEmpty()) {
				long current = frontier.poll();
				members.add(current);
				int cx = ChunkPos.getPackedX(current);
				int cz = ChunkPos.getPackedZ(current);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						long neighbour = ChunkPos.toLong(cx + dx, cz + dz);
						if (unvisited.remove(neighbour)) frontier.add(neighbour);
					}
				}
			}
			Site site = buildSite(members, line);
			if (site != null) next.add(site);
		}
		next.sort(Comparator.comparingDouble(site ->
				site.centre().squaredDistanceTo(client.player.getEntityPos())));
		sites = List.copyOf(next);
	}

	private Site buildSite(List<Long> members, double line) {
		double debris = 0.0, ySum = 0.0;
		int ySamples = 0;
		boolean spawnerSite = false;
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		boolean primeCorroborated = false;
		List<Double> perChunk = new ArrayList<>();
		for (long key : members) {
			ChunkEvidence cell = evidence.get(key);
			if (cell == null) continue;
			perChunk.add(cell.furniture());
			debris += cell.debris;
			spawnerSite |= cell.playerSpawner;
			ySum += cell.ySum;
			ySamples += cell.ySamples;
			int cx = ChunkPos.getPackedX(key);
			int cz = ChunkPos.getPackedZ(key);
			minX = Math.min(minX, cx);
			maxX = Math.max(maxX, cx);
			minZ = Math.min(minZ, cz);
			maxZ = Math.max(maxZ, cz);
			primeCorroborated |= PrimeChunkFinder.isFlagged(key);
		}
		// Diminishing sum: strongest chunk at full weight, the rest at half, so evidence
		// spread thinly across many chunks cannot cross the threshold on width alone.
		perChunk.sort(Comparator.reverseOrder());
		double furniture = 0.0;
		for (int i = 0; i < perChunk.size(); i++) {
			furniture += perChunk.get(i) * (i == 0 ? 1.0 : 0.5);
		}
		double score = furniture + (primeCorroborated ? PRIME_CORROBORATION : 0.0);
		// Debris only suppresses sites with little furniture, so raided bases still score.
		if (!spawnerSite && furniture < 3.0 && debris > furniture * DEBRIS_DOMINANCE) return null;
		if (!spawnerSite && score < line) return null;
		double centreY = ySamples > 0 ? ySum / ySamples : 0.0;
		Vec3d centre = new Vec3d(((minX + maxX + 1) / 2.0) * 16.0, centreY, ((minZ + maxZ + 1) / 2.0) * 16.0);
		// Anchored to the bounding-box corner so identity survives rescans.
		long id = ChunkPos.toLong(minX, minZ);
		return new Site(minX, minZ, maxX, maxZ, centre, score, spawnerSite, id);
	}

	private void updateActionBar(MinecraftClient client) {
		if (sites.isEmpty()) return;
		Site nearest = sites.get(0);
		int distance = (int) Math.sqrt(nearest.centre().squaredDistanceTo(client.player.getEntityPos()));
		Text bar = Text.literal("Base Found ").setStyle(NOVA_FONT.withColor(PING_COLOR))
				.copy().append(Text.literal("x" + sites.size()).setStyle(NOVA_FONT.withColor(0xFFFFFF)))
				.append(Text.literal("  —  " + distance + "m away").setStyle(NOVA_FONT.withColor(0xD8D8D8)));
		client.player.sendMessage(bar, true);
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (failedClosed || !config.enabled || !config.donutStashPinger || sites.isEmpty()) return;
		if (!config.donutStashTracers) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			NovaTracers.Basis basis = NovaTracers.basisFor(mc.gameRenderer.getCamera());
			int drawn = 0;
			for (Site site : sites) {
				if (drawn++ >= MAX_TRACERS) break;
				NovaTracers.draw(lines, pos, entry, basis, site.centre(), TRACER_COLOR, 0.9F);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Stash Pinger render failed; disabling it to protect the client.", exception);
			reset();
			config.donutStashPinger = false;
			failedClosed = true;
		}
	}
}
