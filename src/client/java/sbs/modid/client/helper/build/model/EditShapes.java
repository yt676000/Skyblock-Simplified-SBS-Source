/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.BuildDir;
import sbs.modid.client.core.build.model.Selection;

import java.util.ArrayDeque;

/**
 * Which cells an edit touches - pure geometry over a {@link Selection}, no world.
 *
 * <p>Kept apart from the planner so the shapes are unit-tested: a wall that misses its corner
 * column, or a hollow that eats the shell, is a mistake the player only notices after a
 * thousand-block edit.
 */
public final class EditShapes {

    private EditShapes() {
    }

    /** The four vertical sides, full height - no floor, no ceiling. */
    public static boolean isWall(Selection box, int x, int y, int z) {
        return box.contains(x, y, z)
                && (x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ());
    }

    /** All six faces - the box's shell. */
    public static boolean isOutline(Selection box, int x, int y, int z) {
        return isWall(box, x, y, z) || (box.contains(x, y, z) && (y == box.minY() || y == box.maxY()));
    }

    /** Inside the shell: what {@code //hollow} clears. Empty for a box thinner than 3. */
    public static boolean isInterior(Selection box, int x, int y, int z) {
        return box.contains(x, y, z) && !isOutline(box, x, y, z);
    }

    /**
     * The offset of copy {@code i} (1-based) when stacking {@code box} along {@code dir}: each copy
     * sits flush against the previous one, one box-length further.
     */
    public static int[] stackOffset(Selection box, BuildDir dir, int i) {
        int step = switch (dir) {
            case UP, DOWN -> box.height();
            case NORTH, SOUTH -> box.length();
            case EAST, WEST -> box.width();
        };
        return new int[] {dir.dx * step * i, dir.dy * step * i, dir.dz * step * i};
    }

    /** Which cells a flood may enter. */
    public interface Passable {
        boolean test(int x, int y, int z);
    }

    /** The outcome of a bounded flood fill. */
    public record Flood(long[] cells, int count, boolean escaped) {
    }

    /**
     * Floods from {@code (sx, sy, sz)} through six-connected cells that {@code passable} allows,
     * staying inside {@code bounds} and stopping at {@code limit} cells.
     *
     * <p>{@link Flood#escaped()} is set when the flood reached the edge of {@code bounds} or ran into
     * the limit - either way the area was not closed, and {@code //fill} refuses rather than pour
     * blocks into the whole world.
     */
    public static Flood flood(int sx, int sy, int sz, Selection bounds, int limit, Passable passable) {
        if (!bounds.contains(sx, sy, sz) || !passable.test(sx, sy, sz)) {
            return new Flood(new long[0], 0, false);
        }
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        long[] out = new long[Math.min(limit, 1024)];
        int count = 0;
        boolean escaped = false;
        queue.add(new long[] {sx, sy, sz});
        seen.add(pack(sx, sy, sz));
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            long[] cell = queue.poll();
            int x = (int) cell[0];
            int y = (int) cell[1];
            int z = (int) cell[2];
            if (count == out.length) {
                if (count >= limit) {
                    escaped = true;
                    break;
                }
                out = java.util.Arrays.copyOf(out, Math.min(limit, out.length * 2));
            }
            out[count++] = pack(x, y, z);
            if (x == bounds.minX() || x == bounds.maxX() || y == bounds.minY() || y == bounds.maxY()
                    || z == bounds.minZ() || z == bounds.maxZ()) {
                escaped = true;
            }
            for (int[] step : steps) {
                int nx = x + step[0];
                int ny = y + step[1];
                int nz = z + step[2];
                if (!bounds.contains(nx, ny, nz)) {
                    continue;
                }
                long key = pack(nx, ny, nz);
                if (!seen.contains(key) && passable.test(nx, ny, nz)) {
                    seen.add(key);
                    queue.add(new long[] {nx, ny, nz});
                }
            }
        }
        return new Flood(java.util.Arrays.copyOf(out, count), count, escaped);
    }

    // Same bit layout as the game's BlockPos.asLong: 26 bits x, 26 bits z, 12 bits y.
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;

    /** Packs a position into a long, bit-compatible with {@code BlockPos.asLong}. */
    public static long pack(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int unpackX(long packed) {
        return (int) (packed << (64 - X_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int unpackY(long packed) {
        return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int unpackZ(long packed) {
        return (int) (packed << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }
}
