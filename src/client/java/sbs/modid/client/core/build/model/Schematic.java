/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A captured box of blocks: {@code width × height × length} cells, each a palette index.
 *
 * <p><b>Pure Java on purpose.</b> The palette holds block states as their command-syntax strings
 * ({@code minecraft:oak_stairs[facing=north,half=bottom]}), not {@code BlockState} objects, so the
 * selection maths, rotation, the file format and the undo history are unit-testable without a
 * bootstrapped registry - which the test source set here cannot provide. The one place a string
 * becomes a {@code BlockState} is {@code core/build/logic/BlockStates}, once per palette entry.
 *
 * <p>Palette index {@code 0} is always {@link #AIR}. Cells are stored dense, x fastest, then z, then
 * y ({@link #index}), as a {@code char[]}: 16 unsigned bits cover every block state the game has with
 * room to spare, at half the memory of an {@code int[]} - a full Garden plot is ~960k cells.
 *
 * <p>Instances are immutable. Transforms ({@link SchematicTransform}) and edits build new ones
 * through {@link Builder}.
 */
public final class Schematic {

    /** The palette entry every schematic starts with, at index 0. */
    public static final String AIR = "minecraft:air";

    /** Largest box a schematic may describe - a 256-cube is 16.7M cells, well past any real build. */
    public static final long MAX_VOLUME = 16_777_216L;

    /** Most distinct states one palette may hold; the cell array is 16-bit. */
    public static final int MAX_PALETTE = 65_535;

    private final int width;
    private final int height;
    private final int length;
    private final List<String> palette;
    private final char[] cells;
    private final Map<Integer, String> blockEntities;
    private final SchematicHeader header;

    /** Lazily built list of non-air cell indices; benign race, every thread computes the same array. */
    private volatile int[] nonAir;

    private Schematic(int width, int height, int length, List<String> palette, char[] cells,
                      Map<Integer, String> blockEntities, SchematicHeader header) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.cells = cells;
        this.blockEntities = blockEntities;
        this.header = header;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    public long volume() {
        return (long) width * height * length;
    }

    /** Palette entries, index 0 = {@link #AIR}. Unmodifiable. */
    public List<String> palette() {
        return palette;
    }

    public SchematicHeader header() {
        return header;
    }

    /** Same blocks, different header - renaming or tagging never copies the cells. */
    public Schematic withHeader(SchematicHeader newHeader) {
        Schematic copy = new Schematic(width, height, length, palette, cells, blockEntities, newHeader);
        copy.nonAir = nonAir;
        return copy;
    }

    /** Cell index of {@code (x, y, z)}: x fastest, then z, then y. */
    public int index(int x, int y, int z) {
        return x + width * (z + length * y);
    }

    public int xOf(int index) {
        return index % width;
    }

    public int zOf(int index) {
        return (index / width) % length;
    }

    public int yOf(int index) {
        return index / (width * length);
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < length;
    }

    /** Palette index at a cell index. */
    public int paletteAt(int index) {
        return cells[index];
    }

    /** Palette index at {@code (x, y, z)}, or 0 (air) outside the box. */
    public int paletteAt(int x, int y, int z) {
        return contains(x, y, z) ? cells[index(x, y, z)] : 0;
    }

    /** The block-state string at {@code (x, y, z)}. */
    public String stateAt(int x, int y, int z) {
        return palette.get(paletteAt(x, y, z));
    }

    /** Block-entity data (SNBT) by cell index. Unmodifiable; usually empty. */
    public Map<Integer, String> blockEntities() {
        return blockEntities;
    }

    /** Every non-air cell index, ascending. Computed once. */
    public int[] nonAirCells() {
        int[] cached = nonAir;
        if (cached != null) {
            return cached;
        }
        int count = 0;
        for (char cell : cells) {
            if (cell != 0) {
                count++;
            }
        }
        int[] out = new int[count];
        int at = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0) {
                out[at++] = i;
            }
        }
        nonAir = out;
        return out;
    }

    public int nonAirCount() {
        return nonAirCells().length;
    }

    /** How many cells use each palette entry, keyed by the state string, air excluded. Sorted by name. */
    public Map<String, Integer> countByState() {
        int[] counts = new int[palette.size()];
        for (char cell : cells) {
            counts[cell]++;
        }
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > 0) {
                out.merge(palette.get(i), counts[i], Integer::sum);
            }
        }
        return out;
    }

    /** A copy of the raw cells, for codecs and transforms. */
    public char[] cellsCopy() {
        return cells.clone();
    }

    /** {@code W×H×L}, the way sizes are shown everywhere. */
    public String sizeLabel() {
        return width + "×" + height + "×" + length;
    }

    public static Builder builder(int width, int height, int length) {
        return new Builder(width, height, length);
    }

    /** A schematic from a decoded palette and raw cells; see {@link Builder#fromRaw}. */
    public static Schematic fromRaw(int width, int height, int length, List<String> palette,
                                    char[] cells, Map<Integer, String> blockEntities,
                                    SchematicHeader header) {
        return Builder.fromRaw(width, height, length, palette, cells, blockEntities, header);
    }

    /**
     * Assembles a schematic cell by cell, interning states into the palette as they come.
     *
     * <p>Not thread-safe; build on one thread and hand the result over.
     */
    public static final class Builder {

        private final int width;
        private final int height;
        private final int length;
        private final char[] cells;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> paletteIndex = new HashMap<>();
        private final Map<Integer, String> blockEntities = new HashMap<>();
        private SchematicHeader header = SchematicHeader.untitled();

        private Builder(int width, int height, int length) {
            if (width <= 0 || height <= 0 || length <= 0) {
                throw new IllegalArgumentException("size must be positive: " + width + "x" + height
                        + "x" + length);
            }
            long volume = (long) width * height * length;
            if (volume > MAX_VOLUME) {
                throw new IllegalArgumentException("volume " + volume + " exceeds " + MAX_VOLUME);
            }
            this.width = width;
            this.height = height;
            this.length = length;
            this.cells = new char[(int) volume];
            intern(AIR);
        }

        /** The palette index for {@code state}, adding it when new. */
        public int intern(String state) {
            Integer existing = paletteIndex.get(state);
            if (existing != null) {
                return existing;
            }
            if (palette.size() >= MAX_PALETTE) {
                throw new IllegalStateException("palette holds more than " + MAX_PALETTE + " states");
            }
            int index = palette.size();
            palette.add(state);
            paletteIndex.put(state, index);
            return index;
        }

        public Builder set(int x, int y, int z, String state) {
            return setIndex(x + width * (z + length * y), intern(state));
        }

        /** Sets a cell to an already-interned palette index. */
        public Builder setIndex(int cellIndex, int paletteIndexValue) {
            cells[cellIndex] = (char) paletteIndexValue;
            return this;
        }

        public Builder blockEntity(int x, int y, int z, String snbt) {
            if (snbt == null) {
                blockEntities.remove(x + width * (z + length * y));
            } else {
                blockEntities.put(x + width * (z + length * y), snbt);
            }
            return this;
        }

        public Builder blockEntityAt(int cellIndex, String snbt) {
            blockEntities.put(cellIndex, snbt);
            return this;
        }

        public Builder header(SchematicHeader value) {
            this.header = value;
            return this;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int length() {
            return length;
        }

        public Schematic build() {
            // Drop block entities standing on air: a stale entry would resurrect a chest's contents
            // wherever a later edit happens to put one.
            blockEntities.keySet().removeIf(index -> cells[index] == 0);
            return new Schematic(width, height, length, Collections.unmodifiableList(new ArrayList<>(palette)),
                    cells, Collections.unmodifiableMap(new HashMap<>(blockEntities)), header);
        }

        /**
         * Builds with a pre-made palette and raw cells, as a decoder does. Validates every index
         * against the palette so a corrupt file cannot index past it later.
         */
        public static Schematic fromRaw(int width, int height, int length, List<String> palette,
                                        char[] cells, Map<Integer, String> blockEntities,
                                        SchematicHeader header) {
            if (palette.isEmpty() || !AIR.equals(palette.get(0))) {
                throw new IllegalArgumentException("palette must start with " + AIR);
            }
            long volume = (long) width * height * length;
            if (width <= 0 || height <= 0 || length <= 0 || volume > MAX_VOLUME || cells.length != volume) {
                throw new IllegalArgumentException("bad size " + width + "x" + height + "x" + length);
            }
            int size = palette.size();
            for (char cell : cells) {
                if (cell >= size) {
                    throw new IllegalArgumentException("cell index " + (int) cell + " past palette " + size);
                }
            }
            Map<Integer, String> entities = new HashMap<>();
            for (Map.Entry<Integer, String> entry : blockEntities.entrySet()) {
                int index = entry.getKey();
                if (index >= 0 && index < cells.length && cells[index] != 0) {
                    entities.put(index, entry.getValue());
                }
            }
            return new Schematic(width, height, length, Collections.unmodifiableList(new ArrayList<>(palette)),
                    cells, Collections.unmodifiableMap(entities), header);
        }
    }
}
