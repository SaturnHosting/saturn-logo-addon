package lat.saturn.addon.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// in memory representation of a schematic file
public final class Schematic {
    public record Entry(BlockPos pos, BlockState state) {}

    public final List<Entry> entries;

    private Schematic(List<Entry> entries) {
        entries.sort(Comparator.comparingInt(e -> e.pos().getY()));
        this.entries = entries;
    }

    public static Schematic load(Path file) throws Exception {
        CompoundTag root;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }

        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".litematic")) return loadLitematic(root);
        if (fileName.endsWith(".schem") || fileName.endsWith(".schematic")) return loadSponge(root);
        throw new IllegalArgumentException("Unsupported schematic type: " + fileName);
    }

    private static Schematic loadLitematic(CompoundTag root) {
        List<Entry> out = new ArrayList<>();
        CompoundTag regions = root.getCompoundOrEmpty("Regions");

        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(regionName);
            CompoundTag posTag = region.getCompoundOrEmpty("Position");
            CompoundTag sizeTag = region.getCompoundOrEmpty("Size");

            int sx = posInt(sizeTag, "x"), sy = posInt(sizeTag, "y"), sz = posInt(sizeTag, "z");
            int absX = Math.abs(sx), absY = Math.abs(sy), absZ = Math.abs(sz);
            if (absX == 0 || absY == 0 || absZ == 0) continue;

            // A negative size means the region grows in the negative direction from Position.
            int minX = posInt(posTag, "x") + (sx < 0 ? sx + 1 : 0);
            int minY = posInt(posTag, "y") + (sy < 0 ? sy + 1 : 0);
            int minZ = posInt(posTag, "z") + (sz < 0 ? sz + 1 : 0);

            BlockState[] palette = readLitematicPalette(region.getListOrEmpty("BlockStatePalette"));
            if (palette.length == 0) continue;

            long[] data = region.getLongArray("BlockStates").orElse(EMPTY_LONG);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));

            for (int y = 0; y < absY; y++) {
                for (int z = 0; z < absZ; z++) {
                    for (int x = 0; x < absX; x++) {
                        int index = (y * absZ + z) * absX + x;
                        int id = (int) bitAt(data, index, bits);
                        if (id < 0 || id >= palette.length) continue;

                        BlockState state = palette[id];
                        if (state == null || state.isAir()) continue;
                        out.add(new Entry(new BlockPos(minX + x, minY + y, minZ + z), state));
                    }
                }
            }
        }
        return new Schematic(out);
    }

    private static BlockState[] readLitematicPalette(ListTag paletteList) {
        List<BlockState> states = new ArrayList<>();
        for (Tag tag : paletteList) {
            if (!(tag instanceof CompoundTag entry)) { states.add(null); continue; }
            BlockState state = blockFromId(entry.getStringOr("Name", "")).defaultBlockState();
            CompoundTag props = entry.getCompoundOrEmpty("Properties");
            for (String key : props.keySet()) state = withProperty(state, key, props.getStringOr(key, ""));
            states.add(state);
        }
        return states.toArray(new BlockState[0]);
    }

    private static long bitAt(long[] arr, int index, int bits) {
        long maxVal = (1L << bits) - 1L;
        long startBit = (long) index * bits;
        int startIdx = (int) (startBit >> 6);
        int endIdx = (int) (((long) (index + 1) * bits - 1L) >> 6);
        if (startIdx >= arr.length) return 0;
        int offset = (int) (startBit & 63L);

        if (startIdx == endIdx) {
            return (arr[startIdx] >>> offset) & maxVal;
        }
        int endOffset = 64 - offset;
        return ((arr[startIdx] >>> offset) | (arr[endIdx] << endOffset)) & maxVal;
    }

    private static Schematic loadSponge(CompoundTag root) {
        List<Entry> out = new ArrayList<>();

        CompoundTag schem = hasCompound(root, "Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        CompoundTag container = hasCompound(schem, "Blocks") ? schem.getCompoundOrEmpty("Blocks") : schem;

        int width = schem.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schem.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schem.getShortOr("Length", (short) 0) & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) return new Schematic(out);

        CompoundTag paletteTag = container.getCompoundOrEmpty("Palette");
        byte[] data = container.getByteArray("Data")
            .orElseGet(() -> container.getByteArray("BlockData").orElse(EMPTY_BYTE));

        int max = 0;
        for (String key : paletteTag.keySet()) max = Math.max(max, paletteTag.getIntOr(key, 0));
        BlockState[] palette = new BlockState[max + 1];
        for (String key : paletteTag.keySet()) palette[paletteTag.getIntOr(key, 0)] = parseState(key);

        int i = 0, idx = 0, volume = width * height * length;
        while (i < data.length && idx < volume) {
            int value = 0, shift = 0, b;
            do {
                b = data[i++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);

            int x = idx % width;
            int z = (idx / width) % length;
            int y = idx / (width * length);
            idx++;

            if (value >= 0 && value < palette.length) {
                BlockState state = palette[value];
                if (state != null && !state.isAir()) out.add(new Entry(new BlockPos(x, y, z), state));
            }
        }
        return new Schematic(out);
    }

    private static BlockState parseState(String full) {
        String name = full;
        String propsStr = null;
        int br = full.indexOf('[');
        if (br >= 0) {
            name = full.substring(0, br);
            int end = full.indexOf(']');
            propsStr = full.substring(br + 1, end < 0 ? full.length() : end);
        }
        BlockState state = blockFromId(name).defaultBlockState();
        if (propsStr != null && !propsStr.isEmpty()) {
            for (String pair : propsStr.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) state = withProperty(state, kv[0].trim(), kv[1].trim());
            }
        }
        return state;
    }

    private static Block blockFromId(String id) {
        if (id == null || id.isEmpty()) return Blocks.AIR;
        Identifier loc = Identifier.tryParse(id);
        return loc == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(loc);
    }

    @SuppressWarnings("unchecked")
    private static BlockState withProperty(BlockState state, String name, String value) {
        Property<?> prop = state.getBlock().getStateDefinition().getProperty(name);
        if (prop == null) return state;
        return setValue(state, (Property<? extends Comparable<?>>) prop, value);
    }

    private static <T extends Comparable<T>> BlockState setValue(BlockState state, Property<T> prop, String raw) {
        return prop.getValue(raw).map(v -> state.setValue(prop, v)).orElse(state);
    }

    private static final long[] EMPTY_LONG = new long[0];
    private static final byte[] EMPTY_BYTE = new byte[0];

    private static int posInt(CompoundTag t, String k) { return t.getIntOr(k, 0); }

    private static boolean hasCompound(CompoundTag t, String k) {
        return !t.getCompoundOrEmpty(k).keySet().isEmpty();
    }
}