/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import java.util.Arrays;
import java.util.List;

/**
 * The Water Board as plain data: a small 3D box of cells, read once from the world and then only
 * simulated - no Minecraft type appears here, so the flow and the planner are testable without a game.
 *
 * <p>Coordinates are board-local: {@code x} along the board, {@code y} up (0 = lowest row read),
 * {@code z} the board's depth. A cell is {@link #OPEN}, {@link #SOLID}, or a lever-group block
 * ({@code GROUP_BASE + k}) that counts as solid until lever {@code k} removes it.
 *
 * <p><b>Outlets</b> are open cells whose wetting counts as water "reaching" a colour; {@code
 * outletColor[i]} is an index into {@link #colors}. The source is the cell the water starts from once
 * the water lever is pulled.
 *
 * @param width        cells along x
 * @param height       cells along y
 * @param depth        cells along z
 * @param cells        {@code [x + width * (z + depth * y)]}
 * @param groups       the material names of the lever groups, index = group number (max 6)
 * @param colors       the gate colour names, index = colour number (max 8)
 * @param outletCells  cell indices of the outlets
 * @param outletColor  colour index per outlet, parallel to {@code outletCells}
 * @param source       cell index of the water source, or -1 when none was found
 */
public record WaterGrid(int width, int height, int depth, byte[] cells, List<String> groups,
                        List<String> colors, int[] outletCells, int[] outletColor, int source) {

    public static final byte OPEN = 0;
    public static final byte SOLID = 1;
    public static final byte GROUP_BASE = 2;

    public WaterGrid {
        if (cells.length != width * height * depth) {
            throw new IllegalArgumentException("cells " + cells.length + " != " + width * height * depth);
        }
        if (outletCells.length != outletColor.length) {
            throw new IllegalArgumentException("outlet arrays differ in length");
        }
        if (groups.size() > 6 || colors.size() > 8) {
            throw new IllegalArgumentException("too many groups or colours");
        }
    }

    public int index(int x, int y, int z) {
        return x + width * (z + depth * y);
    }

    /** Whether water can occupy the cell with the given removed-group mask. Outside the box is solid. */
    public boolean open(int x, int y, int z, int removedMask) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
            return false;
        }
        byte cell = cells[index(x, y, z)];
        if (cell == OPEN) {
            return true;
        }
        if (cell == SOLID) {
            return false;
        }
        return (removedMask & (1 << (cell - GROUP_BASE))) != 0;
    }

    /** The colour mask of every outlet whose colour is in the given colour mask - for tests and logs. */
    public int colorMask(String... names) {
        int mask = 0;
        for (String name : names) {
            int i = colors.indexOf(name);
            if (i >= 0) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /**
     * Parses a board drawn as text, one string per row <b>top first</b>, single depth layer. {@code #}
     * solid, {@code .} open, {@code S} source (open), digits {@code 0-5} lever-group blocks, letters
     * {@code a-h} outlets of colour {@code colors[letter - 'a']} (open). Used by tests and handy for
     * reading a logged dump back.
     */
    public static WaterGrid parse(List<String> rowsTopFirst, List<String> groups, List<String> colors) {
        int height = rowsTopFirst.size();
        int width = rowsTopFirst.get(0).length();
        byte[] cells = new byte[width * height];
        int[] outlets = new int[width * height];
        int[] outletColor = new int[width * height];
        int outletCount = 0;
        int source = -1;
        for (int row = 0; row < height; row++) {
            String line = rowsTopFirst.get(row);
            if (line.length() != width) {
                throw new IllegalArgumentException("ragged row " + row);
            }
            int y = height - 1 - row;
            for (int x = 0; x < width; x++) {
                char c = line.charAt(x);
                int i = x + width * y;
                if (c == '#') {
                    cells[i] = SOLID;
                } else if (c >= '0' && c <= '5') {
                    cells[i] = (byte) (GROUP_BASE + (c - '0'));
                } else if (c >= 'a' && c <= 'h') {
                    outlets[outletCount] = i;
                    outletColor[outletCount++] = c - 'a';
                } else if (c == 'S') {
                    source = i;
                } else if (c != '.') {
                    throw new IllegalArgumentException("unknown cell '" + c + "'");
                }
            }
        }
        return new WaterGrid(width, height, 1, cells, List.copyOf(groups), List.copyOf(colors),
                Arrays.copyOf(outlets, outletCount), Arrays.copyOf(outletColor, outletCount), source);
    }

    /** One layer as text, top row first, in the {@link #parse} alphabet - what the probe logs. */
    public String dumpLayer(int z) {
        StringBuilder out = new StringBuilder((width + 1) * height);
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int i = index(x, y, z);
                char c;
                if (i == source) {
                    c = 'S';
                } else {
                    c = switch (cells[i]) {
                        case OPEN -> '.';
                        case SOLID -> '#';
                        default -> (char) ('0' + cells[i] - GROUP_BASE);
                    };
                    for (int o = 0; o < outletCells.length; o++) {
                        if (outletCells[o] == i) {
                            c = (char) ('a' + outletColor[o]);
                        }
                    }
                }
                out.append(c);
            }
            out.append('/');
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "WaterGrid[" + width + "x" + height + "x" + depth + ", groups=" + groups + ", colors="
                + colors + ", outlets=" + outletCells.length + ", source=" + source + "]";
    }
}
