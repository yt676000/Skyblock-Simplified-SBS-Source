/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.client.dungeons.puzzle.model.WaterGrid;

import java.util.Arrays;

/**
 * Where water settles on a {@link WaterGrid}, and which lever groups to remove so it reaches exactly
 * the target colours. Pure: no Minecraft type, no state between calls.
 *
 * <p><b>The flow is the vanilla steady state, not the timed race.</b> Water falls into an open cell
 * below and then restarts at full strength; on a floor it spreads sideways up to {@link #REACH} cells,
 * and only toward the nearest drop within {@link #SLOPE_FIND} cells when one exists (all open sides
 * otherwise). A source spreads sideways even above a drop, flowing water does not. These are the
 * vanilla fluid rules; whether the Hypixel board needs timed lever flips mid-flow (the board is known
 * to be solved with timing) is exactly what this model cannot express, so a plan it finds is a
 * candidate for the probe log and not yet something to draw.
 */
public final class WaterFlow {

    /** Horizontal cells flowing water reaches from a full cell. */
    static final int REACH = 7;
    /** How far sideways flowing water looks for a drop to steer toward. */
    static final int SLOPE_FIND = 4;

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private WaterFlow() {
    }

    /**
     * The wet cells once water from {@link WaterGrid#source()} has settled with the given lever
     * groups removed. Indexed like {@link WaterGrid#cells()}; all false when there is no source.
     */
    public static boolean[] wet(WaterGrid grid, int removedMask) {
        int size = grid.cells().length;
        boolean[] wet = new boolean[size];
        if (grid.source() < 0) {
            return wet;
        }
        // distance[i] = horizontal cells travelled since the water last fell or left the source.
        int[] distance = new int[size];
        Arrays.fill(distance, Integer.MAX_VALUE);
        int[] queue = new int[size * (REACH + 2)];
        int head = 0;
        int tail = 0;
        distance[grid.source()] = 0;
        queue[tail++] = grid.source();
        while (head < tail) {
            int cell = queue[head++];
            wet[cell] = true;
            int x = cell % grid.width();
            int z = (cell / grid.width()) % grid.depth();
            int y = cell / (grid.width() * grid.depth());
            int d = distance[cell];
            boolean source = cell == grid.source();
            boolean canFall = grid.open(x, y - 1, z, removedMask);
            if (canFall) {
                int below = grid.index(x, y - 1, z);
                if (distance[below] > 0 && tail < queue.length) {
                    distance[below] = 0;
                    queue[tail++] = below;
                }
                if (!source) {
                    continue;
                }
            }
            if (d >= REACH) {
                continue;
            }
            int best = Integer.MAX_VALUE;
            int[] slope = new int[4];
            for (int dir = 0; dir < 4; dir++) {
                slope[dir] = dropDistance(grid, x + DX[dir], y, z + DZ[dir], dir, removedMask, 1);
                best = Math.min(best, slope[dir]);
            }
            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                if (!grid.open(nx, y, nz, removedMask)) {
                    continue;
                }
                if (best != Integer.MAX_VALUE && slope[dir] != best) {
                    continue;
                }
                int next = grid.index(nx, y, nz);
                if (d + 1 < distance[next] && tail < queue.length) {
                    distance[next] = d + 1;
                    queue[tail++] = next;
                }
            }
        }
        return wet;
    }

    /**
     * Steps from an open cell to the nearest drop, moving sideways only, never back the way it came.
     * {@link Integer#MAX_VALUE} when there is none within {@link #SLOPE_FIND}.
     */
    private static int dropDistance(WaterGrid grid, int x, int y, int z, int fromDir, int mask, int step) {
        if (!grid.open(x, y, z, mask)) {
            return Integer.MAX_VALUE;
        }
        if (grid.open(x, y - 1, z, mask)) {
            return step;
        }
        if (step >= SLOPE_FIND) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        int back = fromDir ^ 1;   // directions come in opposite pairs: 0/1 and 2/3
        for (int dir = 0; dir < 4; dir++) {
            if (dir != back) {
                best = Math.min(best,
                        dropDistance(grid, x + DX[dir], y, z + DZ[dir], dir, mask, step + 1));
            }
        }
        return best;
    }

    /** The colour mask of every outlet the settled water reaches. */
    public static int reachedColors(WaterGrid grid, boolean[] wet) {
        int mask = 0;
        for (int o = 0; o < grid.outletCells().length; o++) {
            if (wet[grid.outletCells()[o]]) {
                mask |= 1 << grid.outletColor()[o];
            }
        }
        return mask;
    }

    /**
     * The lever-group mask that sends water to exactly {@code targetColors}, using as few groups as
     * possible. {@code -1} when no mask does, <b>and also when two different smallest masks do</b> -
     * a tie is a guess, and the solver contract is to draw nothing rather than guess.
     */
    public static int plan(WaterGrid grid, int targetColors) {
        int groups = grid.groups().size();
        int found = -1;
        int foundBits = Integer.MAX_VALUE;
        boolean tied = false;
        for (int mask = 0; mask < 1 << groups; mask++) {
            int bits = Integer.bitCount(mask);
            if (bits > foundBits) {
                continue;
            }
            if (reachedColors(grid, wet(grid, mask)) != targetColors) {
                continue;
            }
            if (bits < foundBits) {
                found = mask;
                foundBits = bits;
                tied = false;
            } else {
                tied = true;
            }
        }
        return tied ? -1 : found;
    }
}
