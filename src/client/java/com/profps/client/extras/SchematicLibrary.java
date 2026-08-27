package com.profps.client.extras;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads {@code .litematic} files from the game's {@code schematics/} folder and exposes
 * them as a bounding box plus a per-position state lookup.
 */
public final class SchematicLibrary {
	private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
	private static final int MAX_LISTED = 256;

	private static List<Path> files = List.of();
	private static List<String> names = List.of();
	private static LitematicFile loaded;
	private static String loadedLabel = "";
	private static BlockPos origin = BlockPos.ORIGIN;
	private static String status = "";
	// Latched because names() is read from the render loop and would otherwise rescan every frame.
	private static boolean scanned;
	/** Bumped on every load and unload so the builder notices without polling contents. */
	private static long revision;

	private SchematicLibrary() {
	}

	private static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("schematics");
	}

	/** Re-reads the schematics folder. */
	public static void rescan() {
		scanned = true;
		Path root = folder();
		if (!Files.isDirectory(root)) {
			files = List.of();
			names = List.of();
			status = "No schematics folder at " + root.getFileName();
			return;
		}
		List<Path> found = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root, 4)) {
			walk.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".litematic"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
					.limit(MAX_LISTED)
					.forEach(found::add);
		} catch (IOException | RuntimeException exception) {
			status = "Could not read the schematics folder: " + exception.getMessage();
			return;
		}
		files = List.copyOf(found);
		List<String> labels = new ArrayList<>(found.size());
		for (Path path : found) {
			String label = path.getFileName().toString();
			labels.add(label.substring(0, label.length() - ".litematic".length()));
		}
		names = List.copyOf(labels);
		if (names.isEmpty()) status = "No .litematic files in the schematics folder";
	}

	/** Dropdown contents; never empty. */
	public static List<String> names() {
		if (!scanned) rescan();
		return names.isEmpty() ? List.of("(no schematics found)") : names;
	}

	/** Loads the selected file, anchoring its minimum corner at the player's feet. */
	public static boolean load(MinecraftClient client, ProFPSConfig config, int index) {
		if (names.isEmpty()) rescan();
		if (index < 0 || index >= files.size()) {
			status = "Pick a schematic first";
			announce(Formatting.YELLOW);
			return false;
		}
		if (client == null || client.player == null) {
			status = "Join a world before loading";
			announce(Formatting.YELLOW);
			return false;
		}
		BlockPos anchor = client.player.getBlockPos();
		if (!read(files.get(index), anchor)) return false;

		config.schematicLibraryFile = files.get(index).getFileName().toString();
		config.schematicLibraryOriginX = anchor.getX();
		config.schematicLibraryOriginY = anchor.getY();
		config.schematicLibraryOriginZ = anchor.getZ();
		// Auto Move is left as the player set it.
		config.schematicBuildEnabled = true;
		// Button presses bypass the screen's save path.
		config.save();
		return true;
	}

	/** Re-opens the last loaded schematic at its saved origin. */
	public static void restore(ProFPSConfig config) {
		if (config.schematicLibraryFile == null || config.schematicLibraryFile.isEmpty()) return;
		rescan();
		for (Path path : files) {
			if (path.getFileName().toString().equals(config.schematicLibraryFile)) {
				read(path, new BlockPos(config.schematicLibraryOriginX,
						config.schematicLibraryOriginY, config.schematicLibraryOriginZ));
				return;
			}
		}
		status = "Saved schematic '" + config.schematicLibraryFile + "' is no longer in the folder";
	}

	private static boolean read(Path path, BlockPos anchor) {
		try {
			if (Files.size(path) > MAX_FILE_BYTES) {
				status = "That schematic is too large to load";
				return false;
			}
			LitematicFile file = LitematicFile.read(path);
			loaded = file;
			origin = anchor;
			loadedLabel = file.name();
			revision++;
			status = "Loaded " + loadedLabel + " (" + width() + "x" + height() + "x" + depth() + ") at "
					+ anchor.getX() + " " + anchor.getY() + " " + anchor.getZ();
			ProFPS.LOGGER.info("[AutoBuild] {}", status);
			announce(Formatting.GREEN);
			return true;
		} catch (IOException | RuntimeException exception) {
			loaded = null;
			revision++;
			status = "Could not read that schematic: " + exception.getMessage();
			ProFPS.LOGGER.warn("[AutoBuild] failed to read {}", path, exception);
			announce(Formatting.RED);
			return false;
		}
	}

	public static void unload() {
		if (loaded == null) return;
		loaded = null;
		loadedLabel = "";
		revision++;
		status = "Unloaded";
	}

	public static void clear(ProFPSConfig config) {
		unload();
		config.schematicLibraryFile = "";
		config.save();
		announce(Formatting.GRAY);
	}

	public static boolean isLoaded() {
		return loaded != null;
	}

	public static long revision() {
		return revision;
	}

	/** World-space {@code {minX,minY,minZ,maxX,maxY,maxZ}}, or null when nothing is loaded. */
	public static int[] bounds() {
		LitematicFile file = loaded;
		if (file == null) return null;
		return new int[]{
				origin.getX() + file.minX(), origin.getY() + file.minY(), origin.getZ() + file.minZ(),
				origin.getX() + file.maxX(), origin.getY() + file.maxY(), origin.getZ() + file.maxZ()};
	}

	/** The block wanted at a world position, or null. */
	public static BlockState stateAt(BlockPos pos) {
		LitematicFile file = loaded;
		if (file == null) return null;
		return file.relativeState(pos.getX() - origin.getX(),
				pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
	}

	/** Puts the last status on the hotbar overlay. */
	private static void announce(Formatting color) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.inGameHud == null) return;
		client.inGameHud.setOverlayMessage(Text.literal("Auto Build ")
				.formatted(Formatting.AQUA, Formatting.BOLD)
				.append(Text.literal("• " + status).formatted(color)), false);
	}

	private static int width() {
		LitematicFile file = loaded;
		return file == null ? 0 : file.maxX() - file.minX() + 1;
	}

	private static int height() {
		LitematicFile file = loaded;
		return file == null ? 0 : file.maxY() - file.minY() + 1;
	}

	private static int depth() {
		LitematicFile file = loaded;
		return file == null ? 0 : file.maxZ() - file.minZ() + 1;
	}
}
