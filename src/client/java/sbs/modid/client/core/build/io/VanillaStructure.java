/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.io;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.StateStrings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Import and export of Minecraft's own structure file format - the {@code .nbt} a structure block
 * saves. Mojang's format, so supporting it is interoperability, not borrowing.
 *
 * <p>Layout (as the game writes it): {@code DataVersion}, {@code size} (three ints), {@code palette}
 * (a list of {@code {Name, Properties}}), {@code blocks} (each {@code {pos, state, nbt?}}) and
 * {@code entities} (always written empty here - entities are not part of a build). A file with the
 * multi-palette {@code palettes} form is read from its first palette.
 *
 * <p>Works on NBT tags only, no registry, so it runs in tests. Reading is bounded: the compressed file
 * through an {@link NbtAccounter} quota, and size, palette and block counts checked before use.
 * Whether each block name exists is decided later, when the build is shown - an unknown one draws as
 * nothing and is logged ({@code BlockStates}).
 */
public final class VanillaStructure {

    /** NBT quota for one file: enough for any structure a structure block can save, and then some. */
    private static final long NBT_QUOTA = 128L * 1024 * 1024;

    private VanillaStructure() {
    }

    /** Writes {@code schematic} as a structure tag; air cells are left out, as the game does for voids. */
    public static CompoundTag toTag(Schematic schematic, int dataVersion) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", dataVersion);
        root.put("size", ints(schematic.width(), schematic.height(), schematic.length()));
        ListTag palette = new ListTag();
        List<String> states = schematic.palette();
        for (String state : states) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", StateStrings.blockId(state));
            Map<String, String> properties = StateStrings.properties(state);
            if (!properties.isEmpty()) {
                CompoundTag props = new CompoundTag();
                properties.forEach(props::putString);
                entry.put("Properties", props);
            }
            palette.add(entry);
        }
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        for (int cell : schematic.nonAirCells()) {
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(schematic.xOf(cell), schematic.yOf(cell), schematic.zOf(cell)));
            block.putInt("state", schematic.paletteAt(cell));
            String entity = schematic.blockEntities().get(cell);
            if (entity != null) {
                try {
                    block.put("nbt", TagParser.parseCompoundFully(entity));
                } catch (CommandSyntaxException unreadable) {
                    // Keep the block, drop contents that would not parse; the file stays valid.
                }
            }
            blocks.add(block);
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    public static byte[] write(Schematic schematic, int dataVersion) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(toTag(schematic, dataVersion), out);
        return out.toByteArray();
    }

    /** Reads a gzip-compressed structure file. */
    public static Schematic read(byte[] data, String name) throws IOException {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(NBT_QUOTA));
        } catch (IOException | RuntimeException unreadable) {
            throw new IOException("not a readable structure file (" + unreadable.getMessage() + ")");
        }
        return fromTag(root, name);
    }

    /** Builds a schematic from a structure tag. */
    public static Schematic fromTag(CompoundTag root, String name) throws IOException {
        int[] size = intList(root.getListOrEmpty("size"));
        if (size == null) {
            throw new IOException("the structure has no size");
        }
        long volume = (long) size[0] * size[1] * size[2];
        if (size[0] <= 0 || size[1] <= 0 || size[2] <= 0 || volume > Schematic.MAX_VOLUME) {
            throw new IOException("the structure's size is invalid (" + size[0] + "x" + size[1] + "x" + size[2] + ")");
        }
        ListTag palette = root.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            ListTag palettes = root.getListOrEmpty("palettes");
            if (!palettes.isEmpty() && palettes.get(0) instanceof ListTag first) {
                palette = first;
            }
        }
        if (palette.size() > Schematic.MAX_PALETTE) {
            throw new IOException("the structure's block list is too long");
        }
        List<String> states = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompoundOrEmpty(i);
            String id = entry.getStringOr("Name", "minecraft:air");
            Map<String, String> properties = new LinkedHashMap<>();
            CompoundTag props = entry.getCompoundOrEmpty("Properties");
            for (String key : props.keySet()) {
                properties.put(key, props.getStringOr(key, ""));
            }
            states.add(StateStrings.compose(id, properties));
        }
        Schematic.Builder builder = Schematic.builder(size[0], size[1], size[2])
                .header(SchematicHeader.untitled().withName(name).withSource(SchematicHeader.Source.IMPORT)
                        .withCreatedAt(System.currentTimeMillis()));
        int[] remap = new int[states.size()];
        for (int i = 0; i < states.size(); i++) {
            String state = states.get(i);
            // Structure voids and air both mean "leave this alone" in a build.
            boolean empty = StateStrings.blockId(state).equals(Schematic.AIR)
                    || StateStrings.blockId(state).equals("minecraft:structure_void");
            remap[i] = empty ? 0 : builder.intern(state);
        }
        ListTag blocks = root.getListOrEmpty("blocks");
        if (blocks.size() > volume) {
            throw new IOException("the structure lists more blocks than fit in its size");
        }
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            int[] pos = intList(block.getListOrEmpty("pos"));
            int state = block.getIntOr("state", -1);
            if (pos == null || state < 0 || state >= remap.length
                    || pos[0] < 0 || pos[1] < 0 || pos[2] < 0 || pos[0] >= size[0] || pos[1] >= size[1] || pos[2] >= size[2]) {
                continue;   // one bad entry is skipped, not the whole file
            }
            int cell = pos[0] + size[0] * (pos[2] + size[2] * pos[1]);
            builder.setIndex(cell, remap[state]);
            block.getCompound("nbt").ifPresent(nbt -> builder.blockEntityAt(cell, nbt.toString()));
        }
        return builder.build();
    }

    private static ListTag ints(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }

    private static int[] intList(ListTag list) {
        if (list.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            Tag tag = list.get(i);
            if (!(tag instanceof IntTag value)) {
                return null;
            }
            out[i] = value.intValue();
        }
        return out;
    }
}
