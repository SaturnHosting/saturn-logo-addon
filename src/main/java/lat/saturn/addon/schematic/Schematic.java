package lat.saturn.addon.schematic;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.datafixer.fix.BlockStateFlattening;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// in memory representation of a schematic file
public final class Schematic {
    private static final int DATA_VERSION_1_13_2 = 1631;

    public record Entry(BlockPos pos, BlockState state) {}

    public final List<Entry> entries;

    private Schematic(List<Entry> entries) {
        entries.sort(Comparator.comparingInt(e -> e.pos().getY()));
        this.entries = entries;
    }

    public static Schematic load(Path file) throws Exception {
        NbtCompound root;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }

        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".litematic")) return loadLitematic(root);
        if (fileName.endsWith(".schem") || fileName.endsWith(".schematic")) return loadSpongeOrLegacy(root);
        throw new IllegalArgumentException("Unsupported schematic type: " + fileName);
    }

    private static Schematic loadLitematic(NbtCompound root) {
        List<Entry> out = new ArrayList<>();
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        int dataVersion = root.getInt("MinecraftDataVersion", currentDataVersion());

        for (String regionName : regions.getKeys()) {
            NbtCompound region = regions.getCompoundOrEmpty(regionName);
            NbtCompound posTag = region.getCompoundOrEmpty("Position");
            NbtCompound sizeTag = region.getCompoundOrEmpty("Size");

            int sx = posInt(sizeTag, "x"), sy = posInt(sizeTag, "y"), sz = posInt(sizeTag, "z");
            int absX = Math.abs(sx), absY = Math.abs(sy), absZ = Math.abs(sz);
            if (absX == 0 || absY == 0 || absZ == 0) continue;

            // A negative size means the region grows in the negative direction from Position.
            int minX = posInt(posTag, "x") + (sx < 0 ? sx + 1 : 0);
            int minY = posInt(posTag, "y") + (sy < 0 ? sy + 1 : 0);
            int minZ = posInt(posTag, "z") + (sz < 0 ? sz + 1 : 0);

            BlockState[] palette = readLitematicPalette(region.getListOrEmpty("BlockStatePalette"), dataVersion);
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

    private static BlockState[] readLitematicPalette(NbtList paletteList, int dataVersion) {
        List<BlockState> states = new ArrayList<>();
        for (NbtElement tag : paletteList) {
            if (!(tag instanceof NbtCompound entry)) { states.add(null); continue; }
            states.add(readPaletteState(entry, dataVersion));
        }
        return states.toArray(new BlockState[0]);
    }

    private static long bitAt(long[] arr, int index, int bits) {
        long maxVal = (1L << bits) - 1L;
        long startBit = (long) index * bits;
        int startIdx = (int) (startBit >> 6);
        int endIdx = (int) (((long) (index + 1) * bits - 1L) >> 6);
        if (startIdx >= arr.length || endIdx >= arr.length) return 0;
        int offset = (int) (startBit & 63L);

        if (startIdx == endIdx) {
            return (arr[startIdx] >>> offset) & maxVal;
        }
        int endOffset = 64 - offset;
        return ((arr[startIdx] >>> offset) | (arr[endIdx] << endOffset)) & maxVal;
    }

    private static Schematic loadSpongeOrLegacy(NbtCompound root) {
        List<Entry> out = new ArrayList<>();

        boolean nestedV3 = hasCompound(root, "Schematic");
        NbtCompound schem = nestedV3 ? root.getCompoundOrEmpty("Schematic") : root;
        if (schem.getByteArray("Blocks").isPresent()) return loadLegacy(schem);

        NbtCompound container = hasCompound(schem, "Blocks") ? schem.getCompoundOrEmpty("Blocks") : schem;
        int version = schem.getInt("Version", nestedV3 ? 3 : 1);
        int dataVersion = schem.getInt("DataVersion", version == 1 ? DATA_VERSION_1_13_2 : currentDataVersion());
        BlockPos offset = spongeOffset(schem, version);

        int width = schem.getShort("Width", (short) 0) & 0xFFFF;
        int height = schem.getShort("Height", (short) 0) & 0xFFFF;
        int length = schem.getShort("Length", (short) 0) & 0xFFFF;
        long volumeLong = (long) width * height * length;
        if (volumeLong <= 0 || volumeLong > Integer.MAX_VALUE) return new Schematic(out);

        NbtCompound paletteTag = container.getCompoundOrEmpty("Palette");
        byte[] data = container.getByteArray("Data")
            .orElseGet(() -> container.getByteArray("BlockData").orElse(EMPTY_BYTE));

        int max = 0;
        for (String key : paletteTag.getKeys()) max = Math.max(max, paletteTag.getInt(key, 0));
        BlockState[] palette = new BlockState[max + 1];
        for (String key : paletteTag.getKeys()) palette[paletteTag.getInt(key, 0)] = parseState(key, dataVersion);

        int byteIndex = 0;
        int blockIndex = 0;
        int volume = (int) volumeLong;
        while (byteIndex < data.length && blockIndex < volume) {
            int value = 0, shift = 0, currentByte;
            do {
                currentByte = data[byteIndex++] & 0xFF;
                value |= (currentByte & 0x7F) << shift;
                shift += 7;
            } while ((currentByte & 0x80) != 0 && byteIndex < data.length);

            int x = blockIndex % width;
            int z = (blockIndex / width) % length;
            int y = blockIndex / (width * length);
            blockIndex++;

            if (value >= 0 && value < palette.length) {
                BlockState state = palette[value];
                if (state != null && !state.isAir()) out.add(new Entry(offset.add(x, y, z), state));
            }
        }
        return new Schematic(out);
    }

    private static Schematic loadLegacy(NbtCompound root) {
        List<Entry> out = new ArrayList<>();
        int width = root.getShort("Width", (short) 0) & 0xFFFF;
        int height = root.getShort("Height", (short) 0) & 0xFFFF;
        int length = root.getShort("Length", (short) 0) & 0xFFFF;
        long volume = (long) width * height * length;
        if (volume <= 0 || volume > Integer.MAX_VALUE) return new Schematic(out);

        byte[] blocks = root.getByteArray("Blocks").orElse(EMPTY_BYTE);
        byte[] metadata = root.getByteArray("Data").orElse(EMPTY_BYTE);
        byte[] addBlocks = root.getByteArray("AddBlocks").orElse(EMPTY_BYTE);
        int count = Math.min((int) volume, blocks.length);
        BlockPos offset = legacyOffset(root);

        for (int index = 0; index < count; index++) {
            int blockId = blocks[index] & 0xFF;
            int addIndex = index >> 1;
            if (addIndex < addBlocks.length) {
                int high = (index & 1) == 0 ? addBlocks[addIndex] & 0x0F : (addBlocks[addIndex] >>> 4) & 0x0F;
                blockId |= high << 8;
            }
            int meta = index < metadata.length ? metadata[index] & 0x0F : 0;

            NbtElement flattened = (NbtElement) BlockStateFlattening.lookupState((blockId << 4) | meta).getValue();
            if (!(flattened instanceof NbtCompound stateTag)) continue;
            BlockState state = readPaletteState(stateTag, DATA_VERSION_1_13_2);
            if (state.isAir()) continue;

            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            out.add(new Entry(offset.add(x, y, z), state));
        }
        return new Schematic(out);
    }

    private static BlockState readPaletteState(NbtCompound entry, int dataVersion) {
        entry = updateStateTag(entry, dataVersion);
        BlockState state = blockFromId(entry.getString("Name", "")).getDefaultState();
        NbtCompound props = entry.getCompoundOrEmpty("Properties");
        for (String key : props.getKeys()) state = withProperty(state, key, props.getString(key, ""));
        return state;
    }

    private static BlockState parseState(String full, int dataVersion) {
        String name = full;
        String propsStr = null;
        int br = full.indexOf('[');
        if (br >= 0) {
            name = full.substring(0, br);
            int end = full.indexOf(']');
            propsStr = full.substring(br + 1, end < 0 ? full.length() : end);
        }
        NbtCompound tag = new NbtCompound();
        tag.putString("Name", name);
        if (propsStr != null && !propsStr.isEmpty()) {
            NbtCompound properties = new NbtCompound();
            for (String pair : propsStr.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) properties.putString(kv[0].trim(), kv[1].trim());
            }
            if (!properties.isEmpty()) tag.put("Properties", properties);
        }
        return readPaletteState(tag, dataVersion);
    }

    private static NbtCompound updateStateTag(NbtCompound tag, int dataVersion) {
        int currentVersion = currentDataVersion();
        if (dataVersion <= 0 || dataVersion >= currentVersion) return tag;

        Dynamic<NbtElement> input = new Dynamic<>(NbtOps.INSTANCE, tag.copy());
        NbtElement updated = MinecraftClient.getInstance().getDataFixer()
            .update(TypeReferences.BLOCK_STATE, input, dataVersion, currentVersion)
            .getValue();
        return updated instanceof NbtCompound compound ? compound : tag;
    }

    private static int currentDataVersion() {
        return SharedConstants.getGameVersion().dataVersion().id();
    }

    private static BlockPos spongeOffset(NbtCompound schematic, int version) {
        if (version >= 3) return intArrayPos(schematic, "Offset");

        NbtCompound metadata = schematic.getCompoundOrEmpty("Metadata");
        return new BlockPos(
            metadata.getInt("WEOffsetX", 0),
            metadata.getInt("WEOffsetY", 0),
            metadata.getInt("WEOffsetZ", 0)
        );
    }

    private static BlockPos legacyOffset(NbtCompound schematic) {
        return new BlockPos(
            schematic.getInt("WEOffsetX", 0),
            schematic.getInt("WEOffsetY", 0),
            schematic.getInt("WEOffsetZ", 0)
        );
    }

    private static BlockPos intArrayPos(NbtCompound tag, String key) {
        int[] values = tag.getIntArray(key).orElse(EMPTY_INT);
        return values.length >= 3 ? new BlockPos(values[0], values[1], values[2]) : BlockPos.ORIGIN;
    }

    private static Block blockFromId(String id) {
        if (id == null || id.isEmpty()) return Blocks.AIR;
        Identifier loc = Identifier.tryParse(id);
        return loc == null ? Blocks.AIR : Registries.BLOCK.get(loc);
    }

    @SuppressWarnings("unchecked")
    private static BlockState withProperty(BlockState state, String name, String value) {
        Property<?> prop = state.getBlock().getStateManager().getProperty(name);
        if (prop == null) return state;
        return setValue(state, (Property<? extends Comparable<?>>) prop, value);
    }

    private static <T extends Comparable<T>> BlockState setValue(BlockState state, Property<T> prop, String raw) {
        return prop.parse(raw).map(v -> state.with(prop, v)).orElse(state);
    }

    private static final long[] EMPTY_LONG = new long[0];
    private static final byte[] EMPTY_BYTE = new byte[0];
    private static final int[] EMPTY_INT = new int[0];

    private static int posInt(NbtCompound t, String k) { return t.getInt(k, 0); }

    private static boolean hasCompound(NbtCompound t, String k) {
        return !t.getCompoundOrEmpty(k).getKeys().isEmpty();
    }
}
