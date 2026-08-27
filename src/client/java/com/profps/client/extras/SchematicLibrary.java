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
 * Loads {@code .litematic} files ourselves, out of the game's own
 * {@code schematics/} folder.
 *
 * <p>This exists because going through Litematica's placement API proved
 * unreliable: it depends on the user having created and enabled a placement,
 * on several reflected method names staying put across versions, and it fails
 * closed and silently when any of that drifts. Reading the file is a shorter
 * path with no moving parts — pick a file, press Load, and the build is
 * anchored where you stand.
 *
 * <p>The loaded schematic is exposed the same way the Litematica bridge was — a
 * bounding box plus a per-position state lookup — so the builder's scanner
 * consumes it without knowing the difference. Remember captures are untouched
 * and still take priority over both.
 */
public final class SchematicLibrary {
	/** Guard against a stray huge file eating the frame budget on load. */
	private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
	private static final int MAX_LISTED = 256;

	private static List<Path> files = List.of();
	private static List<String> names = List.of();
	private static LitematicFile loaded;
	private static String loadedLabel = "";
	private static BlockPos origin = BlockPos.ORIGIN;
	private static String status = "";
	// names() is read from the render loop, so the one-shot auto scan must
	// latch: without this a folder with no schematics walks the disk every frame.
	private static boolean scanned;
	/** Bumped on every load/unload so the builder notices without polling contents. */
	private static long revision;

	private SchematicLibrary() {
	}

	// ── Folder ─────────────────────────────────────────────────────────────────

	/** Where Litematica and every other tool puts schematics. */
	private static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("schematics");
	}

	/**
	 * Re-reads the schematics folder. Cheap enough to call whenever the UI
	 * opens, which is what keeps the dropdown honest without a file watcher.
	 */
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

	/** Dropdown contents. Never empty, so the UI always has something to render. */
	public static List<String> names() {
		if (!scanned) rescan();
		return names.isEmpty() ? List.of("(no schematics found)") : names;
	}

	// ── Loading ────────────────────────────────────────────────────────────────

	/**
	 * Loads the selected file and anchors it at the player's feet. The corner
	 * the schematic considers its own minimum lands on the block you are
	 * standing on, so what you see in the world is what you walked to.
	 */
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
		// "Load and go" — the point of the button is that the builder then runs.
		// Auto Move is deliberately left as the player set it: with it off, the
		// builder places what the crosshair serves while the player walks, and
		// forcing it on here used to silently revert that choice on every load.
		config.schematicBuildEnabled = true;
		// Button presses do not go through the screen's own save path, so the
		// selection would be forgotten by the next launch without this.
		config.save();
		return true;
	}

	/** Re-opens the last loaded schematic at its saved origin, after a restart. */
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

	// ── Queries used by the builder ────────────────────────────────────────────

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

	/** Puts the last status on the hotbar overlay, where a button press is felt. */
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
