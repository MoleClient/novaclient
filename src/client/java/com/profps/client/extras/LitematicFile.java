package com.profps.client.extras;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for {@code .litematic} files (gzipped NBT: named regions, each with a
 * block-state palette and a bit-packed index array).
 */
final class LitematicFile {
	private final String name;
	private final List<Region> regions;
	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;

	private LitematicFile(String name, List<Region> regions) {
		this.name = name;
		this.regions = regions;
		int lowX = Integer.MAX_VALUE, lowY = Integer.MAX_VALUE, lowZ = Integer.MAX_VALUE;
		int highX = Integer.MIN_VALUE, highY = Integer.MIN_VALUE, highZ = Integer.MIN_VALUE;
		for (Region region : regions) {
			lowX = Math.min(lowX, region.minX);
			lowY = Math.min(lowY, region.minY);
			lowZ = Math.min(lowZ, region.minZ);
			highX = Math.max(highX, region.minX + region.sizeX - 1);
			highY = Math.max(highY, region.minY + region.sizeY - 1);
			highZ = Math.max(highZ, region.minZ + region.sizeZ - 1);
		}
		this.minX = lowX;
		this.minY = lowY;
		this.minZ = lowZ;
		this.maxX = highX;
		this.maxY = highY;
		this.maxZ = highZ;
	}

	static LitematicFile read(Path path) throws IOException {
		NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
		NbtCompound regionsTag = root.getCompoundOrEmpty("Regions");
		List<Region> regions = new ArrayList<>();
		for (String key : regionsTag.getKeys()) {
			Region region = readRegion(regionsTag.getCompoundOrEmpty(key));
			if (region != null) regions.add(region);
		}
		if (regions.isEmpty()) throw new IOException("no readable regions");
		String name = root.getCompoundOrEmpty("Metadata").getString("Name", "");
		return new LitematicFile(name.isEmpty() ? path.getFileName().toString() : name, regions);
	}

	private static Region readRegion(NbtCompound tag) {
		int[] position = readVec(tag, "Position");
		int[] size = readVec(tag, "Size");
		int sizeX = Math.abs(size[0]);
		int sizeY = Math.abs(size[1]);
		int sizeZ = Math.abs(size[2]);
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return null;

		NbtList paletteTag = tag.getListOrEmpty("BlockStatePalette");
		if (paletteTag.isEmpty()) return null;
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < palette.length; i++) palette[i] = readState(paletteTag.getCompoundOrEmpty(i));

		long[] data = tag.getLongArray("BlockStates").orElse(null);
		if (data == null) return null;

		int bits = bitsFor(palette.length);
		long volume = (long) sizeX * sizeY * sizeZ;
		if (((volume * bits) + 63L) / 64L > data.length) return null;

		return new Region(
				minCorner(position[0], size[0]),
				minCorner(position[1], size[1]),
				minCorner(position[2], size[2]),
				sizeX, sizeY, sizeZ, palette, data, bits);
	}

	/** Lower corner of an axis given one corner and a signed extent; region sizes may be negative. */
	static int minCorner(int position, int extent) {
		return Math.min(position, position + (extent > 0 ? extent - 1 : extent + 1));
	}

	/** Bits each palette index occupies: {@code max(2, ceil(log2(size)))}. */
	static int bitsFor(int paletteSize) {
		return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(0, paletteSize - 1)));
	}

	/** Reads one entry from the array. Entries are packed end to end and may straddle two longs. */
	static int unpack(long[] data, int bits, int index) {
		long startOffset = (long) index * bits;
		int startArray = (int) (startOffset >> 6);
		int endArray = (int) ((((long) (index + 1)) * bits - 1L) >> 6);
		if (startArray < 0 || endArray >= data.length) return -1;
		int startBit = (int) (startOffset & 63L);
		long mask = (1L << bits) - 1L;
		if (startArray == endArray) return (int) ((data[startArray] >>> startBit) & mask);
		return (int) (((data[startArray] >>> startBit) | (data[endArray] << (64 - startBit))) & mask);
	}

	private static int[] readVec(NbtCompound parent, String key) {
		NbtCompound vec = parent.getCompoundOrEmpty(key);
		return new int[]{vec.getInt("x", 0), vec.getInt("y", 0), vec.getInt("z", 0)};
	}

	private static BlockState readState(NbtCompound entry) {
		Identifier id = Identifier.tryParse(entry.getString("Name", ""));
		if (id == null) return Blocks.AIR.getDefaultState();
		Block block = Registries.BLOCK.get(id);
		BlockState state = block.getDefaultState();
		NbtCompound properties = entry.getCompoundOrEmpty("Properties");
		StateManager<Block, BlockState> manager = block.getStateManager();
		for (String propertyName : properties.getKeys()) {
			Property<?> property = manager.getProperty(propertyName);
			// Skip properties this game version no longer has rather than failing the block.
			if (property != null) state = withValue(state, property, properties.getString(propertyName, ""));
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String raw) {
		return property.parse(raw).map(value -> state.with(property, value)).orElse(state);
	}

	String name() {
		return name;
	}

	int minX() {
		return minX;
	}

	int minY() {
		return minY;
	}

	int minZ() {
		return minZ;
	}

	int maxX() {
		return maxX;
	}

	int maxY() {
		return maxY;
	}

	int maxZ() {
		return maxZ;
	}

	/** Block at a position relative to the schematic's own origin, or null for air. */
	BlockState relativeState(int x, int y, int z) {
		for (Region region : regions) {
			BlockState state = region.at(x, y, z);
			if (state != null && !state.isAir()) return state;
		}
		return null;
	}

	private record Region(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
			BlockState[] palette, long[] data, int bits) {

		BlockState at(int x, int y, int z) {
			int localX = x - minX;
			int localY = y - minY;
			int localZ = z - minZ;
			if (localX < 0 || localY < 0 || localZ < 0
					|| localX >= sizeX || localY >= sizeY || localZ >= sizeZ) return null;
			int index = (localY * sizeX * sizeZ) + localZ * sizeX + localX;
			int paletteIndex = unpack(data, bits, index);
			return paletteIndex >= 0 && paletteIndex < palette.length ? palette[paletteIndex] : null;
		}
	}
}
