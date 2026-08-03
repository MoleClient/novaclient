package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Suspicious Chunks — chunks that probably contain a player base, found without
 * ever being able to look at one.
 *
 * <p>The problem this exists for: on a server like DonutSMP the bases worth
 * finding are at deepslate depth, and from the surface there is nothing to see.
 * Activity Chunks answers "has something been happening here", which flags a
 * great deal of ground. This answers the much narrower question "is there a
 * <em>base</em> down there", and it is meant to be rare enough that every hit is
 * worth flying out to, digging down, and checking by hand.
 *
 * <p>Scarcity comes from what it refuses, not from what it finds. Underground
 * generated structures are full of exactly the things a naive storage-and-light
 * detector looks for — mineshafts have chests, rails and torches; trial chambers
 * have chests, dispensers and lit copper; ancient cities have chests, wool and
 * soul lanterns. A detector without structure vetoes flags all of them and is
 * worthless. So each chunk is first fingerprinted against the generated
 * structures that live at this depth, and a match cancels most of its score.
 *
 * <p>What survives that is evidence no world generator produces:
 * <ul>
 *   <li><b>Impossible blocks.</b> A shulker box, ender chest, observer, hopper,
 *       piston or beacon at deepslate did not grow there. This is the strongest
 *       single signal and it is nearly impossible to produce by accident.</li>
 *   <li><b>Containers.</b> Read from the chunk's block-entity map, which a server
 *       still has to send even when it obfuscates the block palette around
 *       them — so a stash tends to leak even when the walls do not.</li>
 *   <li><b>Unexplained light.</b> Light arrives as its own data, separate from
 *       block states. Deep underground there is no natural light but lava, so a
 *       lit cell with no visible emitter is somebody's torch.</li>
 *   <li><b>Entities.</b> Minecarts, villagers, iron golems and loose items below
 *       the ceiling are farm and storage infrastructure, and entities are sent
 *       independently of blocks.</li>
 * </ul>
 */
public final class SuspiciousChunksRenderer {
	private static final int SCAN_INTERVAL_TICKS = 100;
	private static final int MAX_CHUNKS = 96;
	private static final int TTL_TICKS = 9_000;
	private static final int FADE_TICKS = 14;

	// Show nothing below this. Set so an untouched deepslate chunk scores zero
	// and a lone stray chest is not worth interrupting anybody for.
	private static final double SHOW_THRESHOLD = 30.0D;
	private static final double YELLOW_THRESHOLD = 60.0D;
	private static final double RED_THRESHOLD = 105.0D;

	private static final int GREEN = 0xFF38FF7A;
	private static final int YELLOW = 0xFFFFD44A;
	private static final int RED = 0xFFFF3B30;

	private final ProFPSConfig config;
	private final Map<Long, Suspicion> found = new HashMap<>();
	private int nextScanTick;
	private boolean failedClosed;
	private ClientWorld lastWorld;

	private final List<long[]> scanQueue = new ArrayList<>();
	private Map<Long, EntityEvidence> pendingEntities;
	private int scanCeiling;

	public SuspiciousChunksRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutSuspiciousChunks) {
			found.clear();
			scanQueue.clear();
			pendingEntities = null;
			failedClosed = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;
		if (client.world != lastWorld) {
			lastWorld = client.world;
			found.clear();
			scanQueue.clear();
			pendingEntities = null;
			nextScanTick = 0;
		}
		if (nextScanTick > client.player.age + SCAN_INTERVAL_TICKS) nextScanTick = 0;
		try {
			int age = client.player.age;
			found.values().removeIf(suspicion -> age - suspicion.seenTick > TTL_TICKS);
			if (scanQueue.isEmpty()) {
				if (age < nextScanTick) return;
				if (!ScanBudget.tryClaim(age)) return;
				nextScanTick = age + SCAN_INTERVAL_TICKS;
				beginScan(client);
			} else {
				stepScan(client);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Suspicious Chunks scan failed; disabling it to protect the client.", exception);
			disable(client);
		}
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutSuspiciousChunks || failedClosed || found.isEmpty()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;

			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			float renderTick = mc.player.age + mc.getRenderTickCounter().getTickProgress(false);
			double range = MathHelper.clamp(config.donutSuspiciousChunksRange, 48, 1024);
			double rangeSq = range * range;

			List<Suspicion> visible = new ArrayList<>();
			for (Suspicion suspicion : found.values()) {
				if (camera.squaredDistanceTo(suspicion.center()) > rangeSq) continue;
				if (suspicion.fade(renderTick) <= 0.01F) continue;
				visible.add(suspicion);
			}
			if (visible.isEmpty()) return;

			// One buffer is active at a time: every fill has to be emitted before
			// the lines layer is requested, and text after both.
			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (Suspicion suspicion : visible) {
				float fade = suspicion.fade(renderTick);
				float pulse = 0.82F + 0.18F * (float) Math.sin(renderTick * 0.11F + suspicion.seed);
				DonutWorldRenderer.drawFilledBox(fills, pos, suspicion.box(), camera, suspicion.color(),
						(0.09F + 0.05F * pulse) * fade);
			}
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			for (Suspicion suspicion : visible) {
				float fade = suspicion.fade(renderTick);
				float pulse = 0.82F + 0.18F * (float) Math.sin(renderTick * 0.11F + suspicion.seed);
				// The outline is drawn in full on every chunk rather than only around
				// a cluster's perimeter: each hit is its own place to go dig.
				DonutWorldRenderer.drawOutline(lines, pos, entry, suspicion.box(), camera,
						suspicion.color(), (0.90F + 0.10F * pulse) * fade);
			}
			if (config.donutSuspiciousChunksLabels) {
				for (Suspicion suspicion : visible) {
					drawLabel(ctx, matrices, mc, camera, suspicion, suspicion.fade(renderTick));
				}
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Suspicious Chunks render failed; disabling it to protect the client.", exception);
			disable(MinecraftClient.getInstance());
		}
	}

	private void disable(MinecraftClient client) {
		found.clear();
		scanQueue.clear();
		pendingEntities = null;
		config.donutSuspiciousChunks = false;
		config.save();
		failedClosed = true;
		if (client != null) ChunkActivityRenderer.announceDisabled(client, "Suspicious Chunks");
	}

	// ── Scanning ──────────────────────────────────────────────────────────────

	private void beginScan(MinecraftClient client) {
		int centerChunkX = client.player.getBlockX() >> 4;
		int centerChunkZ = client.player.getBlockZ() >> 4;
		int range = MathHelper.clamp(config.donutSuspiciousChunksRange, 48, 1024);
		int viewDistance = client.options == null ? 12 : client.options.getViewDistance().getValue();
		int radius = MathHelper.clamp(MathHelper.ceil(range / 16.0F), 2, Math.min(16, viewDistance + 1));
		scanCeiling = config.donutSuspiciousChunksCeiling;

		scanQueue.clear();
		for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
			for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
				scanQueue.add(new long[]{chunkX, chunkZ});
			}
		}
		scanQueue.sort(Comparator.comparingInt(c -> {
			int dx = (int) c[0] - centerChunkX;
			int dz = (int) c[1] - centerChunkZ;
			return -(dx * dx + dz * dz);
		}));
		pendingEntities = censusEntities(client);
	}

	/**
	 * One entity sweep per cycle, bucketed by chunk. Entities reach the client
	 * independently of block data, so this keeps working on a server that hides
	 * the blocks underneath the player.
	 */
	private Map<Long, EntityEvidence> censusEntities(MinecraftClient client) {
		Map<Long, EntityEvidence> census = new HashMap<>();
		for (Entity entity : client.world.getEntities()) {
			if (entity.getBlockY() > scanCeiling) continue;
			long key = ChunkPos.toLong(entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
			census.computeIfAbsent(key, ignored -> new EntityEvidence()).accept(entity);
		}
		return census;
	}

	private void stepScan(MinecraftClient client) {
		ClientWorld world = client.world;
		long pool = ScanBudget.takeBudget(client.player.age, ScanBudget.Lane.SUSPICIOUS_CHUNKS, config);
		if (pool <= 0L) return;
		long start = System.nanoTime();

		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) break;
			long[] coord = scanQueue.remove(scanQueue.size() - 1);
			int chunkX = (int) coord[0];
			int chunkZ = (int) coord[1];
			if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
			WorldChunk chunk = world.getChunk(chunkX, chunkZ);
			if (chunk == null || chunk.isEmpty()) continue;
			evaluate(client, world, chunk, chunkX, chunkZ);
		}

		ScanBudget.reportUsed(client.player.age, ScanBudget.Lane.SUSPICIOUS_CHUNKS, System.nanoTime() - start);
		if (scanQueue.isEmpty()) pendingEntities = null;
	}

	private void evaluate(MinecraftClient client, ClientWorld world, WorldChunk chunk, int chunkX, int chunkZ) {
		Evidence evidence = new Evidence();
		int floor = world.getBottomY();
		int ceiling = Math.min(scanCeiling, world.getBottomY() + world.getHeight() - 1);
		if (ceiling <= floor) return;

		scanBlocks(chunk, floor, ceiling, evidence);
		scanContainers(chunk, ceiling, evidence);
		scanLight(world, chunk, floor, ceiling, evidence);
		EntityEvidence entities = pendingEntities == null ? null
				: pendingEntities.get(ChunkPos.toLong(chunkX, chunkZ));

		double score = evidence.score(entities);
		if (score < SHOW_THRESHOLD) return;

		long key = ChunkPos.toLong(chunkX, chunkZ);
		Suspicion existing = found.get(key);
		int age = client.player.age;
		if (existing != null) {
			existing.refresh(age, score, evidence, entities);
			return;
		}
		if (found.size() >= MAX_CHUNKS) {
			// Full: only displace the weakest, and only for something better.
			Map.Entry<Long, Suspicion> weakest = null;
			for (Map.Entry<Long, Suspicion> entry : found.entrySet()) {
				if (weakest == null || entry.getValue().score < weakest.getValue().score) weakest = entry;
			}
			if (weakest == null || weakest.getValue().score >= score) return;
			found.remove(weakest.getKey());
		}
		found.put(key, new Suspicion(chunkX, chunkZ, age, score, evidence, entities));
	}

	/**
	 * Palette-first block sweep. A whole 16-cube is dismissed by one predicate
	 * test against its palette, so the overwhelmingly common case — deepslate and
	 * stone and nothing else — never costs a single block read.
	 */
	private void scanBlocks(WorldChunk chunk, int floor, int ceiling, Evidence evidence) {
		ChunkSection[] sections = chunk.getSectionArray();
		for (int index = 0; index < sections.length; index++) {
			ChunkSection section = sections[index];
			if (section == null || section.isEmpty()) continue;
			int baseY = chunk.sectionIndexToCoord(index) << 4;
			if (baseY > ceiling || baseY + 15 < floor) continue;
			boolean interesting = section.hasAny(SuspiciousChunksRenderer::isImpossibleNatural);
			boolean structural = section.hasAny(SuspiciousChunksRenderer::isStructureMarker);
			if (!interesting && !structural) continue;

			for (int y = 0; y < 16; y++) {
				int worldY = baseY + y;
				if (worldY < floor || worldY > ceiling) continue;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (isImpossibleNatural(state)) {
							evidence.impossible++;
							evidence.note(worldY);
						} else if (isStructureMarker(state)) {
							evidence.structure++;
						}
					}
				}
			}
		}
	}

	private void scanContainers(WorldChunk chunk, int ceiling, Evidence evidence) {
		for (Map.Entry<BlockPos, BlockEntity> found : chunk.getBlockEntities().entrySet()) {
			BlockPos pos = found.getKey();
			if (pos.getY() > ceiling) continue;
			BlockEntity blockEntity = found.getValue();
			if (blockEntity instanceof ShulkerBoxBlockEntity) evidence.shulkers++;
			else if (blockEntity instanceof EnderChestBlockEntity) evidence.enderChests++;
			else if (blockEntity instanceof BeaconBlockEntity) evidence.beacons++;
			else if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity) {
				evidence.chests++;
			} else if (blockEntity instanceof HopperBlockEntity || blockEntity instanceof DispenserBlockEntity
					|| blockEntity instanceof CrafterBlockEntity) {
				evidence.machines++;
			} else if (blockEntity instanceof AbstractFurnaceBlockEntity
					|| blockEntity instanceof BrewingStandBlockEntity) {
				evidence.utility++;
			} else {
				continue;
			}
			evidence.note(pos.getY());
		}
	}

	/**
	 * Light is transmitted separately from block states, so a server that hides
	 * the blocks often still reveals that something down there is lit. Anything
	 * lava can explain is thrown away, and so is anything with a visible emitter,
	 * leaving light that had to be placed by hand.
	 */
	private void scanLight(ClientWorld world, WorldChunk chunk, int floor, int ceiling, Evidence evidence) {
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		int top = Math.min(ceiling, 8);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = Math.max(floor + 2, -60); y <= top; y += 4) {
			for (int z = 0; z < 16; z += 4) {
				for (int x = 0; x < 16; x += 4) {
					pos.set(startX + x, y, startZ + z);
					if (world.getLightLevel(LightType.BLOCK, pos) < 9) continue;
					if (hasNaturalEmitter(world, pos)) continue;
					evidence.litCells++;
					evidence.note(y);
				}
			}
		}
	}

	/** Lava and other world-generated glow within reach of the sampled cell. */
	private boolean hasNaturalEmitter(ClientWorld world, BlockPos center) {
		BlockPos.Mutable probe = new BlockPos.Mutable();
		for (int dy = -3; dy <= 3; dy++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dx = -3; dx <= 3; dx++) {
					probe.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = world.getBlockState(probe);
					if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.MAGMA_BLOCK)
							|| state.isOf(Blocks.GLOW_LICHEN) || state.isOf(Blocks.SHROOMLIGHT)
							|| state.isOf(Blocks.CRYING_OBSIDIAN) || state.isOf(Blocks.SCULK_CATALYST)) return true;
				}
			}
		}
		return false;
	}

	// ── Evidence classification ───────────────────────────────────────────────

	/**
	 * Blocks that no world generator places underground. Deliberately strict:
	 * anything that appears in a mineshaft, stronghold, trial chamber, ancient
	 * city or dungeon is excluded no matter how base-like it looks, because a
	 * single false entry here would flag every one of those structures on the
	 * server and drown the real hits.
	 */
	private static boolean isImpossibleNatural(BlockState state) {
		return state.isIn(net.minecraft.registry.tag.BlockTags.SHULKER_BOXES)
				|| state.isOf(Blocks.ENDER_CHEST) || state.isOf(Blocks.BEACON)
				|| state.isOf(Blocks.HOPPER) || state.isOf(Blocks.DROPPER)
				|| state.isOf(Blocks.OBSERVER) || state.isOf(Blocks.PISTON)
				|| state.isOf(Blocks.STICKY_PISTON) || state.isOf(Blocks.CRAFTER)
				|| state.isOf(Blocks.NOTE_BLOCK) || state.isOf(Blocks.REDSTONE_LAMP)
				|| state.isOf(Blocks.TARGET) || state.isOf(Blocks.DAYLIGHT_DETECTOR)
				|| state.isOf(Blocks.REDSTONE_BLOCK) || state.isOf(Blocks.IRON_BLOCK)
				|| state.isOf(Blocks.DIAMOND_BLOCK) || state.isOf(Blocks.EMERALD_BLOCK)
				|| state.isOf(Blocks.NETHERITE_BLOCK) || state.isOf(Blocks.RESPAWN_ANCHOR)
				|| state.isOf(Blocks.LODESTONE) || state.isOf(Blocks.CONDUIT)
				|| state.isOf(Blocks.SPONGE) || state.isOf(Blocks.WET_SPONGE)
				|| state.isOf(Blocks.SLIME_BLOCK) || state.isOf(Blocks.HONEY_BLOCK)
				|| state.isOf(Blocks.DRIED_KELP_BLOCK) || state.isOf(Blocks.HAY_BLOCK)
				|| state.isOf(Blocks.COMPOSTER) || state.isOf(Blocks.BEEHIVE)
				|| state.isOf(Blocks.LIGHTNING_ROD) || state.isOf(Blocks.ANVIL)
				|| state.isIn(net.minecraft.registry.tag.BlockTags.WOOL_CARPETS)
				|| isGlazedTerracotta(state) || isConcrete(state);
	}

	private static boolean isGlazedTerracotta(BlockState state) {
		return state.isOf(Blocks.WHITE_GLAZED_TERRACOTTA) || state.isOf(Blocks.ORANGE_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.MAGENTA_GLAZED_TERRACOTTA) || state.isOf(Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.YELLOW_GLAZED_TERRACOTTA) || state.isOf(Blocks.LIME_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.PINK_GLAZED_TERRACOTTA) || state.isOf(Blocks.GRAY_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA) || state.isOf(Blocks.CYAN_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.PURPLE_GLAZED_TERRACOTTA) || state.isOf(Blocks.BLUE_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.BROWN_GLAZED_TERRACOTTA) || state.isOf(Blocks.GREEN_GLAZED_TERRACOTTA)
				|| state.isOf(Blocks.RED_GLAZED_TERRACOTTA) || state.isOf(Blocks.BLACK_GLAZED_TERRACOTTA);
	}

	private static boolean isConcrete(BlockState state) {
		return state.isOf(Blocks.WHITE_CONCRETE) || state.isOf(Blocks.ORANGE_CONCRETE)
				|| state.isOf(Blocks.MAGENTA_CONCRETE) || state.isOf(Blocks.LIGHT_BLUE_CONCRETE)
				|| state.isOf(Blocks.YELLOW_CONCRETE) || state.isOf(Blocks.LIME_CONCRETE)
				|| state.isOf(Blocks.PINK_CONCRETE) || state.isOf(Blocks.GRAY_CONCRETE)
				|| state.isOf(Blocks.LIGHT_GRAY_CONCRETE) || state.isOf(Blocks.CYAN_CONCRETE)
				|| state.isOf(Blocks.PURPLE_CONCRETE) || state.isOf(Blocks.BLUE_CONCRETE)
				|| state.isOf(Blocks.BROWN_CONCRETE) || state.isOf(Blocks.GREEN_CONCRETE)
				|| state.isOf(Blocks.RED_CONCRETE) || state.isOf(Blocks.BLACK_CONCRETE);
	}

	/**
	 * Fingerprints of the generated structures that live at this depth. These are
	 * the false positives that matter: every one of them is full of chests,
	 * torches and machinery, and without this the module would flag them all.
	 */
	private static boolean isStructureMarker(BlockState state) {
		return state.isOf(Blocks.SCULK) || state.isOf(Blocks.SCULK_VEIN)
				|| state.isOf(Blocks.SCULK_CATALYST) || state.isOf(Blocks.SCULK_SENSOR)
				|| state.isOf(Blocks.SCULK_SHRIEKER) || state.isOf(Blocks.REINFORCED_DEEPSLATE)
				|| state.isOf(Blocks.COBWEB) || state.isOf(Blocks.SPAWNER)
				|| state.isOf(Blocks.TRIAL_SPAWNER) || state.isOf(Blocks.VAULT)
				|| state.isOf(Blocks.CHISELED_TUFF) || state.isOf(Blocks.CHISELED_TUFF_BRICKS)
				|| state.isOf(Blocks.TUFF_BRICKS) || state.isOf(Blocks.COPPER_BULB)
				|| state.isOf(Blocks.WAXED_COPPER_BULB) || state.isOf(Blocks.INFESTED_STONE)
				|| state.isOf(Blocks.INFESTED_DEEPSLATE) || state.isOf(Blocks.MOSSY_STONE_BRICKS)
				|| state.isOf(Blocks.CRACKED_STONE_BRICKS) || state.isOf(Blocks.END_PORTAL_FRAME)
				|| state.isOf(Blocks.MOSSY_COBBLESTONE) || state.isOf(Blocks.SOUL_LANTERN)
				|| state.isOf(Blocks.SOUL_SAND) || state.isOf(Blocks.SOUL_FIRE)
				|| state.isOf(Blocks.RAIL) || state.isOf(Blocks.POWERED_RAIL)
				|| state.isOf(Blocks.DETECTOR_RAIL) || state.isOf(Blocks.ACTIVATOR_RAIL);
	}

	private void drawLabel(WorldRenderContext ctx, MatrixStack matrices, MinecraftClient mc,
			Vec3d camera, Suspicion suspicion, float fade) {
		Vec3d anchor = suspicion.center();
		double distance = Math.sqrt(camera.squaredDistanceTo(anchor));
		if (distance < 8.0D || distance > 512.0D) return;
		String label = (int) distance + "m · y" + suspicion.evidenceY + " · " + suspicion.why;
		float scale = 0.045F * (float) Math.max(1.0D, distance / 28.0D);
		int alpha = MathHelper.clamp(Math.round(255 * fade), 0, 255);
		int color = (alpha << 24) | (suspicion.color() & 0xFFFFFF);
		int background = Math.round(0x70 * fade) << 24;

		matrices.push();
		matrices.translate(anchor.x - camera.x, suspicion.box().maxY - camera.y + 1.1D, anchor.z - camera.z);
		matrices.multiply(mc.gameRenderer.getCamera().getRotation());
		matrices.scale(-scale, -scale, scale);
		float width = mc.textRenderer.getWidth(label);
		mc.textRenderer.draw(label, -width * 0.5F, 0.0F, color, true, matrices.peek().getPositionMatrix(),
				ctx.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, background, 0x00F000F0);
		matrices.pop();
	}

	// ── Evidence + result types ───────────────────────────────────────────────

	private static final class Evidence {
		int impossible;
		int structure;
		int shulkers;
		int enderChests;
		int beacons;
		int chests;
		int machines;
		int utility;
		int litCells;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;

		void note(int y) {
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
		}

		/**
		 * Every category is capped so one prolific signal cannot carry a chunk on
		 * its own — a long lit corridor or a wall of chests still has to be
		 * corroborated by something else to reach a warm tier.
		 */
		double score(EntityEvidence entities) {
			double total = Math.min(impossible * 14.0D, 60.0D);
			double containers = shulkers * 18.0D + enderChests * 20.0D + beacons * 25.0D
					+ chests * 6.0D + machines * 8.0D + utility * 5.0D;
			total += Math.min(containers, 70.0D);
			total += Math.min(litCells * 3.0D, 24.0D);
			if (entities != null) total += entities.score();
			// A generated structure explains the same evidence far more cheaply
			// than a base does, so a confident match takes most of the score away.
			if (structure >= 6) total -= 55.0D;
			else if (structure >= 2) total -= 25.0D;
			return Math.max(0.0D, total);
		}

		String why() {
			if (beacons > 0) return "beacon";
			if (shulkers > 0) return shulkers + " shulker" + (shulkers == 1 ? "" : "s");
			if (enderChests > 0) return "ender chest";
			if (impossible >= 6) return "redstone build";
			if (chests >= 6) return chests + " containers";
			if (machines > 0) return "machinery";
			if (chests > 0) return chests + " container" + (chests == 1 ? "" : "s");
			if (litCells > 0) return "lit hollow";
			return "anomaly";
		}
	}

	private static final class EntityEvidence {
		int minecarts;
		int villagers;
		int golems;
		int items;

		void accept(Entity entity) {
			if (entity instanceof AbstractMinecartEntity) minecarts++;
			else if (entity instanceof VillagerEntity) villagers++;
			else if (entity instanceof IronGolemEntity) golems++;
			else if (entity instanceof ItemEntity) items++;
			else if (entity instanceof MobEntity) { /* ambient mobs prove nothing down here */ }
		}

		double score() {
			return Math.min(minecarts * 10.0D + villagers * 8.0D + golems * 10.0D
					+ Math.min(items * 1.0D, 8.0D), 30.0D);
		}
	}

	private static final class Suspicion {
		private final int chunkX;
		private final int chunkZ;
		private final float seed;
		private int firstTick;
		private int seenTick;
		private double score;
		private int evidenceY;
		private int lowY;
		private int highY;
		private String why;

		Suspicion(int chunkX, int chunkZ, int tick, double score, Evidence evidence, EntityEvidence entities) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.firstTick = tick;
			this.seed = (float) ((chunkX * 0.37D + chunkZ * 0.61D) % (Math.PI * 2.0D));
			refresh(tick, score, evidence, entities);
		}

		void refresh(int tick, double score, Evidence evidence, EntityEvidence entities) {
			this.seenTick = tick;
			// The tier only ever climbs. A later sweep that happens to catch less
			// of the same chunk must not quietly downgrade a confirmed find.
			this.score = Math.max(this.score, score);
			this.why = evidence.why();
			int low = evidence.minY == Integer.MAX_VALUE ? -59 : evidence.minY;
			int high = evidence.maxY == Integer.MIN_VALUE ? 0 : evidence.maxY;
			this.lowY = Math.min(low, high);
			this.highY = Math.max(low, high);
			this.evidenceY = this.lowY;
		}

		Box box() {
			double x = chunkX << 4;
			double z = chunkZ << 4;
			// At least a few blocks tall so a single-layer find is still a solid
			// target to fly at from a distance.
			double top = Math.max(highY + 1.0D, lowY + 4.0D);
			return new Box(x, lowY, z, x + 16.0D, top, z + 16.0D);
		}

		Vec3d center() {
			Box box = box();
			return new Vec3d((box.minX + box.maxX) * 0.5D, (box.minY + box.maxY) * 0.5D,
					(box.minZ + box.maxZ) * 0.5D);
		}

		int color() {
			return score >= RED_THRESHOLD ? RED : score >= YELLOW_THRESHOLD ? YELLOW : GREEN;
		}

		float fade(float renderTick) {
			float age = renderTick - firstTick;
			if (age < 0.0F) return 1.0F;
			if (age < FADE_TICKS) return MathHelper.clamp(age / FADE_TICKS, 0.0F, 1.0F);
			return 1.0F;
		}
	}
}
