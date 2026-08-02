package com.profps.client.donutsmp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.profps.ProFPS;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent mask-piercing intel map.
 *
 * <p>The server hides block data below the deepslate line, but two packet
 * families carry the <b>real</b> truth through the mask:
 * <ul>
 *   <li><b>Block updates</b> — when anyone places or mines a block, the update
 *       packet contains the actual block state, even in masked regions.</li>
 *   <li><b>Block events</b> — chest/shulker lid animations, pistons and note
 *       blocks are broadcast with their exact position to every client
 *       tracking the chunk, straight through 60 blocks of fake deepslate.</li>
 * </ul>
 *
 * The old pipeline counted these and let them decay in 75 seconds. Here every
 * revelation is REMEMBERED: pinned to its exact coordinate, folded into the
 * chunk heat score, rendered as a marker, and saved per-server to disk — so
 * loitering near a busy base gradually maps it, and the map survives restarts.
 */
public final class RevealedIntel {
	private static final RevealedIntel INSTANCE = new RevealedIntel();
	private static final Gson GSON = new Gson();
	private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();
	private static final int MAX_ENTRIES = 4096;
	/** Reveals above this Y are visible anyway; only the masked zone is intel. */
	private static final int MAX_INTEL_Y = 16;

	private final Map<Long, Entry> entries = new HashMap<>();
	private boolean active;
	private String loadedFor = "";
	private boolean dirty;
	private int saveCooldown;

	private RevealedIntel() {}

	public static RevealedIntel get() {
		return INSTANCE;
	}

	public void setActive(boolean value) {
		active = value;
	}

	/** Per-tick housekeeping: per-server load, debounced save. Call from the chunk-activity tick. */
	public void tick(MinecraftClient client) {
		String key = NetherPortalMapper.serverKey(client);
		if (!key.equals(loadedFor)) {
			load(key);
			loadedFor = key;
		}
		if (saveCooldown > 0) saveCooldown--;
		if (dirty && saveCooldown <= 0) {
			save();
			dirty = false;
			saveCooldown = 100;
		}
	}

	// ── Intake (called from packet mixins on the client thread) ───────────────

	/** A block update leaked the real state of a position in the masked zone. */
	public void recordRevealedBlock(BlockPos pos, BlockState state) {
		if (!active || pos.getY() > MAX_INTEL_Y) return;
		double weight = revealWeight(state);
		if (weight <= 0.0) return;
		remember(pos, label(state), weight);
	}

	/**
	 * EXPERIMENTAL: an exact container / utility block-entity read straight from a
	 * loaded chunk's block-entity list. Anti-xray rewrites the block PALETTE but
	 * not the block-entity list, so this pierces the deepslate mask and pins the
	 * real loot position without anyone having to touch it. Only the masked zone
	 * is intel — shallower containers are visible anyway.
	 */
	public void recordLeakedBlockEntity(BlockPos pos, BlockEntityType<?> type) {
		if (!active || pos.getY() > MAX_INTEL_Y) return;
		double weight;
		String label;
		if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST) {
			weight = 12.0;
			label = "Chest";
		} else if (type == BlockEntityType.BARREL) {
			weight = 12.0;
			label = "Barrel";
		} else if (type == BlockEntityType.SHULKER_BOX) {
			weight = 13.0;
			label = "Shulker";
		} else if (type == BlockEntityType.ENDER_CHEST) {
			weight = 11.0;
			label = "Ender Chest";
		} else if (type == BlockEntityType.HOPPER || type == BlockEntityType.DISPENSER || type == BlockEntityType.DROPPER) {
			weight = 7.0;
			label = "Redstone";
		} else if (type == BlockEntityType.FURNACE || type == BlockEntityType.BLAST_FURNACE
				|| type == BlockEntityType.SMOKER || type == BlockEntityType.BREWING_STAND
				|| type == BlockEntityType.ENCHANTING_TABLE || type == BlockEntityType.BEACON) {
			weight = 8.0;
			label = "Station";
		} else if (type == BlockEntityType.MOB_SPAWNER || type == BlockEntityType.TRIAL_SPAWNER) {
			weight = 12.0;
			label = "Spawner";
		} else {
			return;
		}
		remember(pos, label, weight);
	}

	/** A container lid / mechanism animated at this exact position. */
	public void recordBlockEvent(BlockPos pos, Block block) {
		if (!active || pos.getY() > MAX_INTEL_Y) return;
		if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST
				|| block instanceof ShulkerBoxBlock || block == Blocks.BARREL) {
			remember(pos, "Active " + label(block.getDefaultState()), 14.0);
		} else if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) {
			remember(pos, "Piston", 6.0);
		}
	}

	private void remember(BlockPos pos, String what, double weight) {
		long key = pos.asLong();
		Entry existing = entries.get(key);
		if (existing != null) {
			// Same spot revealed again — keep the strongest interpretation.
			if (weight > existing.w) {
				existing.w = weight;
				existing.label = what;
				dirty = true;
			}
			existing.t = System.currentTimeMillis();
			return;
		}
		if (entries.size() >= MAX_ENTRIES) {
			entries.values().stream()
					.min(Comparator.comparingDouble((Entry entry) -> entry.w).thenComparingLong(entry -> entry.t))
					.ifPresent(weakest -> entries.remove(BlockPos.asLong(weakest.x, weakest.y, weakest.z)));
		}
		entries.put(key, new Entry(pos.getX(), pos.getY(), pos.getZ(), what, weight, System.currentTimeMillis()));
		dirty = true;
	}

	// ── Reads ──────────────────────────────────────────────────────────────────

	/** Permanent (non-decaying) intel score for one chunk. */
	public double chunkScore(int chunkX, int chunkZ) {
		double score = 0.0;
		for (Entry entry : entries.values()) {
			if ((entry.x >> 4) == chunkX && (entry.z >> 4) == chunkZ) score += entry.w;
		}
		return Math.min(score, 140.0);
	}

	/** Strongest intel entries near a position, for world markers. */
	public List<Marker> markersNear(double x, double z, double range, int max) {
		List<Entry> near = new ArrayList<>();
		double rangeSq = range * range;
		for (Entry entry : entries.values()) {
			double dx = entry.x + 0.5 - x;
			double dz = entry.z + 0.5 - z;
			if (dx * dx + dz * dz <= rangeSq) near.add(entry);
		}
		near.sort(Comparator.comparingDouble((Entry entry) -> entry.w).reversed());
		List<Marker> markers = new ArrayList<>();
		for (int i = 0; i < near.size() && i < max; i++) {
			Entry entry = near.get(i);
			markers.add(new Marker(new Box(entry.x, entry.y, entry.z, entry.x + 1, entry.y + 1, entry.z + 1)
					.expand(0.12, 0.12, 0.12), entry.label, entry.w));
		}
		return markers;
	}

	// ── Classification ─────────────────────────────────────────────────────────

	private double revealWeight(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
				|| block instanceof ShulkerBoxBlock) return 12.0;
		if (block == Blocks.ENDER_CHEST || block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER) return 12.0;
		if (block == Blocks.HOPPER || block == Blocks.PISTON || block == Blocks.STICKY_PISTON
				|| block == Blocks.OBSERVER || block == Blocks.DISPENSER || block == Blocks.DROPPER
				|| block == Blocks.REDSTONE_BLOCK) return 7.0;
		if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
				|| block == Blocks.CRAFTING_TABLE || block == Blocks.ENCHANTING_TABLE
				|| block == Blocks.ANVIL || block == Blocks.BREWING_STAND || block == Blocks.BEACON) return 8.0;
		if (PlayerPlacedBlocks.isBuildDecor(state, false)) return 3.0;
		// Mined-to-air below the mask is still someone working down there,
		// but it's the weakest signal — the monitor already counts it live.
		return 0.0;
	}

	private String label(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return "Chest";
		if (block == Blocks.BARREL) return "Barrel";
		if (block instanceof ShulkerBoxBlock) return "Shulker";
		if (block == Blocks.ENDER_CHEST) return "Ender Chest";
		if (block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER) return "Spawner";
		if (block == Blocks.BEACON) return "Beacon";
		return block.getName().getString();
	}

	// ── Persistence ────────────────────────────────────────────────────────────

	private Path fileFor(String key) {
		return FabricLoader.getInstance().getConfigDir().resolve("profps_intel").resolve(key + ".json");
	}

	private void load(String key) {
		entries.clear();
		Path path = fileFor(key);
		if (!Files.exists(path)) return;
		try {
			List<Entry> loaded = GSON.fromJson(Files.newBufferedReader(path), LIST_TYPE);
			if (loaded != null) {
				for (Entry entry : loaded) {
					entries.put(BlockPos.asLong(entry.x, entry.y, entry.z), entry);
				}
			}
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to load revealed intel for {}.", key, exception);
		}
	}

	private void save() {
		Path path = fileFor(loadedFor);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(new ArrayList<>(entries.values()), LIST_TYPE));
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to save revealed intel.", exception);
		}
	}

	private static final class Entry {
		int x, y, z;
		String label;
		double w;
		long t;

		Entry(int x, int y, int z, String label, double w, long t) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.label = label;
			this.w = w;
			this.t = t;
		}
	}

	public record Marker(Box box, String label, double weight) {
	}
}
