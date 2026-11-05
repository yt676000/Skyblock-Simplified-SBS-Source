/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Arrow Align device as a small grid of cells, in the device's own frame of reference - no world
 * coordinates, so a capture from one run matches the same device in the next whichever way it faces.
 *
 * <p><b>Cell codes.</b> {@link #NONE} = no item frame there, {@link #FRAME} = a frame holding no
 * arrow (empty or another item), {@code 0..7} = a frame holding an arrow at that rotation step. The
 * text form is one string per row, top row first, one character per cell: {@code .} / {@code #} /
 * the digit. That string form is exactly what the probe logs and what the bundled layout file stores,
 * so a capture is turned into data by copying its line.
 *
 * <p><b>Orientation.</b> Columns run left to right <i>as seen by a player looking at the wall</i>.
 * A frame's rotation counts in the frame's own plane, so it means the same on every wall.
 *
 * <p>Pure Java: no Minecraft types, so the normalisation and matching are unit-tested.
 */
public final class ArrowAlignGrid {

    public static final int NONE = -2;
    public static final int FRAME = -1;
    /** Rotation steps of an item frame (vanilla {@code ItemFrame.NUM_ROTATIONS}). */
    public static final int STEPS = 8;
    /** The device is ESTIMATED to be 5x5; anything bigger is not the device and is never solved. */
    public static final int MAX_SIZE = 5;

    private final int width;
    private final int height;
    /** Row-major, row 0 = top. */
    private final int[] cells;

    public ArrowAlignGrid(int width, int height, int[] cells) {
        if (width <= 0 || height <= 0 || cells.length != width * height) {
            throw new IllegalArgumentException("bad grid " + width + "x" + height + "/" + cells.length);
        }
        this.width = width;
        this.height = height;
        this.cells = cells.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int cell(int column, int row) {
        return cells[row * width + column];
    }

    public int cellAt(int index) {
        return cells[index];
    }

    public int size() {
        return cells.length;
    }

    /** Whether this fits the device's estimated 5x5. */
    public boolean fitsDevice() {
        return width <= MAX_SIZE && height <= MAX_SIZE;
    }

    /** Clicks to turn {@code current} into {@code target}: one click is one step forward, mod 8. */
    public static int clicks(int current, int target) {
        return Math.floorMod(target - current, STEPS);
    }

    /**
     * Same dimensions and the same kind of cell everywhere (no frame / frame / arrow) - rotations
     * ignored. Players only ever rotate arrows, so this is what stays fixed while the device is worked.
     */
    public boolean sameShape(ArrowAlignGrid other) {
        if (other.width != width || other.height != height) {
            return false;
        }
        for (int i = 0; i < cells.length; i++) {
            if (kind(cells[i]) != kind(other.cells[i])) {
                return false;
            }
        }
        return true;
    }

    private static int kind(int code) {
        return code >= 0 ? 0 : code;
    }

    /** How many cells hold an arrow. */
    public int arrowCount() {
        int count = 0;
        for (int code : cells) {
            if (code >= 0) {
                count++;
            }
        }
        return count;
    }

    // ---- text form ---------------------------------------------------------------------------

    public List<String> rows() {
        List<String> rows = new ArrayList<>(height);
        StringBuilder row = new StringBuilder(width);
        for (int r = 0; r < height; r++) {
            row.setLength(0);
            for (int c = 0; c < width; c++) {
                int code = cell(c, r);
                row.append(code == NONE ? '.' : code == FRAME ? '#' : (char) ('0' + code));
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /** The rows as one JSON array literal, for log lines that are pasted into the data file. */
    public String rowsJson() {
        StringBuilder out = new StringBuilder("[");
        List<String> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            out.append(i == 0 ? "" : ",").append('"').append(rows.get(i)).append('"');
        }
        return out.append(']').toString();
    }

    /** Parses the text form; {@code null} when the rows are empty, ragged or carry a bad character. */
    public static ArrowAlignGrid parse(List<String> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null || rows.get(0).isEmpty()) {
            return null;
        }
        int width = rows.get(0).length();
        int[] cells = new int[width * rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            if (row == null || row.length() != width) {
                return null;
            }
            for (int c = 0; c < width; c++) {
                char ch = row.charAt(c);
                int code;
                if (ch == '.') {
                    code = NONE;
                } else if (ch == '#') {
                    code = FRAME;
                } else if (ch >= '0' && ch < '0' + STEPS) {
                    code = ch - '0';
                } else {
                    return null;
                }
                cells[r * width + c] = code;
            }
        }
        return new ArrowAlignGrid(width, rows.size(), cells);
    }

    // ---- from the world ----------------------------------------------------------------------

    /**
     * One item frame as the scan saw it: block position, the horizontal direction it faces out of the
     * wall ({@code nx, nz}, one of them ±1), and its cell code.
     */
    public record Frame(int x, int y, int z, int nx, int nz, int code) {
    }

    /**
     * A grid plus where each cell came from: {@code frameAt[cell]} is the index into the scanned frame
     * list, or {@code -1}. {@code frames} is the plane's frames only.
     */
    public record Placement(ArrowAlignGrid grid, int[] frameAt, List<Frame> frames, int nx, int nz) {
    }

    /**
     * Builds the grid from every frame the scan found. Frames are grouped by wall (facing + plane);
     * the wall with the most frames is the device. Floor and ceiling frames ({@code nx = nz = 0}) are
     * ignored. The grid is the bounding box of that wall's frames. {@code null} when nothing is left.
     */
    public static Placement fromFrames(List<Frame> frames) {
        Map<Long, List<Frame>> walls = new HashMap<>();
        for (Frame frame : frames) {
            if ((frame.nx == 0) == (frame.nz == 0)) {
                continue;
            }
            int plane = frame.x * frame.nx + frame.z * frame.nz;
            long key = ((long) (frame.nx + 1) << 40) ^ ((long) (frame.nz + 1) << 36) ^ (plane & 0xFFFFFFFFL);
            walls.computeIfAbsent(key, k -> new ArrayList<>()).add(frame);
        }
        List<Frame> wall = null;
        for (List<Frame> candidate : walls.values()) {
            if (wall == null || candidate.size() > wall.size()) {
                wall = candidate;
            }
        }
        if (wall == null) {
            return null;
        }
        int nx = wall.get(0).nx;
        int nz = wall.get(0).nz;
        // The viewer looks along -n; their right hand points (nz, -nx).
        int rx = nz;
        int rz = -nx;
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Frame frame : wall) {
            int u = frame.x * rx + frame.z * rz;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, frame.y);
            maxY = Math.max(maxY, frame.y);
        }
        int width = maxU - minU + 1;
        int height = maxY - minY + 1;
        if ((long) width * height > 4096) {
            return null; // nonsense spread - not a device
        }
        int[] cells = new int[width * height];
        Arrays.fill(cells, NONE);
        int[] frameAt = new int[cells.length];
        Arrays.fill(frameAt, -1);
        for (int i = 0; i < wall.size(); i++) {
            Frame frame = wall.get(i);
            int column = frame.x * rx + frame.z * rz - minU;
            int row = maxY - frame.y;
            int index = row * width + column;
            cells[index] = frame.code;
            frameAt[index] = i;
        }
        return new Placement(new ArrowAlignGrid(width, height, cells), frameAt, wall, nx, nz);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArrowAlignGrid grid && grid.width == width && grid.height == height
                && Arrays.equals(grid.cells, cells);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(cells);
    }

    @Override
    public String toString() {
        return String.join("/", rows());
    }
}
