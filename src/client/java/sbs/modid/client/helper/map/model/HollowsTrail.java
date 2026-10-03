/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import sbs.modid.client.core.location.hollows.HollowsGeometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where you have been in one Crystal Hollows lobby: the 8x8-block cells (X/Z) you have stood in,
 * kept separately for the upper cave and for Magma Fields underneath it.
 *
 * <p>Only ever the player's own position, sampled by {@code HollowsMapTracker}. A cell is a place
 * you stood, not a place you saw - nothing about the terrain is recorded.
 *
 * <p>A cell is keyed as {@code (cellX << 16) | (cellZ & 0xFFFF)}: both halves are signed 16-bit, which
 * covers ±262 thousand blocks and keeps the whole set a flat list of ints in the save file.
 */
public final class HollowsTrail {

    /** Side of one cell, in blocks. */
    public static final int CELL = 8;

    /** Which layer a cell was visited on. */
    public enum Layer {
        UPPER,
        MAGMA;

        /** The layer a Y puts you on, by the ESTIMATED Magma Fields height. */
        public static Layer of(int y) {
            return HollowsGeometry.belowMagma(y) ? MAGMA : UPPER;
        }
    }

    private final Set<Integer> upper = new HashSet<>();
    private final Set<Integer> magma = new HashSet<>();

    /** Bumped on every change, so a renderer can cache its runs until the trail grows. */
    private int generation;

    /**
     * Records the cell under a position. Returns whether that was a new cell.
     */
    public boolean sample(int x, int y, int z) {
        boolean added = set(Layer.of(y)).add(key(cell(x), cell(z)));
        if (added) {
            generation++;
        }
        return added;
    }

    public boolean visited(Layer layer, int cellX, int cellZ) {
        return set(layer).contains(key(cellX, cellZ));
    }

    public int size(Layer layer) {
        return set(layer).size();
    }

    public int generation() {
        return generation;
    }

    public static int cell(int coordinate) {
        return Math.floorDiv(coordinate, CELL);
    }

    public static int key(int cellX, int cellZ) {
        return (cellX << 16) | (cellZ & 0xFFFF);
    }

    public static int cellX(int key) {
        return key >> 16;
    }

    public static int cellZ(int key) {
        return (short) (key & 0xFFFF);
    }

    /** The layer's keys, sorted, for saving. */
    public int[] encode(Layer layer) {
        int[] out = new int[set(layer).size()];
        int i = 0;
        for (int key : set(layer)) {
            out[i++] = key;
        }
        Arrays.sort(out);
        return out;
    }

    /** Adds stored keys to a layer. */
    public void restore(Layer layer, int[] keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        Set<Integer> target = set(layer);
        for (int key : keys) {
            target.add(key);
        }
        generation++;
    }

    /** Takes every cell of {@code other} into this trail. */
    public void addAll(HollowsTrail other) {
        boolean changed = upper.addAll(other.upper) | magma.addAll(other.magma);
        if (changed) {
            generation++;
        }
    }

    /**
     * The layer as horizontal runs of adjacent cells, {@code {cellZ, firstCellX, lastCellX}}, sorted by
     * row then column. A row of twenty visited cells is one rectangle to draw instead of twenty.
     */
    public List<int[]> runs(Layer layer) {
        int[] keys = set(layer).stream().mapToInt(Integer::intValue)
                .map(k -> (cellZ(k) << 16) | (cellX(k) & 0xFFFF)).sorted().toArray();
        List<int[]> runs = new ArrayList<>();
        int[] current = null;
        for (int rowMajor : keys) {
            int cz = rowMajor >> 16;
            int cx = (short) (rowMajor & 0xFFFF);
            if (current != null && current[0] == cz && current[2] == cx - 1) {
                current[2] = cx;
            } else {
                current = new int[]{cz, cx, cx};
                runs.add(current);
            }
        }
        return runs;
    }

    private Set<Integer> set(Layer layer) {
        return layer == Layer.MAGMA ? magma : upper;
    }
}
