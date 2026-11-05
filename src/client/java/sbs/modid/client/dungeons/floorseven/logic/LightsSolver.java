/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

/**
 * Lights-out over GF(2): which levers to flip so that every lamp ends up lit.
 *
 * <p>Lever {@code i} toggles the lamps in {@code columns[i]}. Flipping a set {@code x} of levers
 * toggles {@code XOR of columns[i] for i in x}, and that has to equal the set of lamps currently off.
 * Toggles commute and a lever flipped twice cancels, so the answer is a <i>set</i> - the order the
 * levers are flipped in does not matter, and nothing is numbered.
 *
 * <p>Gauss-Jordan elimination gives the solution space; when it has free variables (more levers
 * than independent lamps) every assignment of them is tried, up to {@link #MAX_FREE} of them, and
 * the one with the fewest flips wins - ties to the lowest lever mask, so the same board always gives
 * the same answer. Past that bound the particular solution is returned and marked not minimal.
 *
 * <p>Only levers in {@code knownMask} take part: a lever whose wiring has not been seen is left out
 * rather than guessed, so a solution found with part of the wiring is still a correct one.
 */
public final class LightsSolver {

    /** Free variables enumerated exhaustively; 2^16 parity checks is well under a millisecond. */
    public static final int MAX_FREE = 16;

    /** Outcome of one solve. {@code levers} is a lever mask, valid only when {@code solvable}. */
    public record Solution(boolean solvable, long levers, boolean minimal) {
        public int flips() {
            return Long.bitCount(levers);
        }
    }

    private static final Solution UNSOLVABLE = new Solution(false, 0L, false);

    private LightsSolver() {
    }

    /**
     * @param columns   per lever, the lamp mask it toggles
     * @param knownMask levers whose column is known; the rest are not used
     * @param lampCount number of lamps (at most 64)
     * @param litMask   lamps lit right now
     */
    public static Solution solve(long[] columns, long knownMask, int lampCount, long litMask) {
        long all = lampCount >= 64 ? -1L : (1L << lampCount) - 1;
        long target = ~litMask & all;
        if (target == 0) {
            return new Solution(true, 0L, true);
        }
        int levers = Math.min(columns.length, 64);
        // One row per lamp: which levers toggle it, and whether it must change.
        long[] rows = new long[lampCount];
        boolean[] rhs = new boolean[lampCount];
        for (int r = 0; r < lampCount; r++) {
            long row = 0L;
            for (int c = 0; c < levers; c++) {
                if ((knownMask & (1L << c)) != 0 && (columns[c] & (1L << r)) != 0) {
                    row |= 1L << c;
                }
            }
            rows[r] = row;
            rhs[r] = (target & (1L << r)) != 0;
        }
        int[] pivotCol = new int[lampCount];
        int rank = 0;
        long pivotMask = 0L;
        for (int c = 0; c < levers && rank < lampCount; c++) {
            long bit = 1L << c;
            int found = -1;
            for (int r = rank; r < lampCount; r++) {
                if ((rows[r] & bit) != 0) {
                    found = r;
                    break;
                }
            }
            if (found < 0) {
                continue;
            }
            swap(rows, rhs, rank, found);
            for (int r = 0; r < lampCount; r++) {
                if (r != rank && (rows[r] & bit) != 0) {
                    rows[r] ^= rows[rank];
                    rhs[r] ^= rhs[rank];
                }
            }
            pivotCol[rank] = c;
            pivotMask |= bit;
            rank++;
        }
        for (int r = rank; r < lampCount; r++) {
            if (rhs[r]) {
                return UNSOLVABLE; // 0 = 1: these levers cannot light every lamp
            }
        }
        long freeMask = knownMask & ~pivotMask & (levers >= 64 ? -1L : (1L << levers) - 1);
        int freeCount = Long.bitCount(freeMask);
        int[] freeCols = new int[freeCount];
        for (int i = 0, c = 0; c < levers; c++) {
            if ((freeMask & (1L << c)) != 0) {
                freeCols[i++] = c;
            }
        }
        boolean exhaustive = freeCount <= MAX_FREE;
        long combos = exhaustive ? 1L << freeCount : 1L;
        long best = 0L;
        int bestWeight = Integer.MAX_VALUE;
        for (long k = 0; k < combos; k++) {
            long assignment = 0L;
            for (int i = 0; i < freeCount; i++) {
                if ((k & (1L << i)) != 0) {
                    assignment |= 1L << freeCols[i];
                }
            }
            long x = assignment;
            for (int r = 0; r < rank; r++) {
                boolean value = rhs[r] ^ ((Long.bitCount(rows[r] & assignment) & 1) == 1);
                if (value) {
                    x |= 1L << pivotCol[r];
                }
            }
            int weight = Long.bitCount(x);
            if (weight < bestWeight || (weight == bestWeight && Long.compareUnsigned(x, best) < 0)) {
                best = x;
                bestWeight = weight;
            }
        }
        return new Solution(true, best, exhaustive);
    }

    private static void swap(long[] rows, boolean[] rhs, int a, int b) {
        if (a == b) {
            return;
        }
        long t = rows[a];
        rows[a] = rows[b];
        rows[b] = t;
        boolean u = rhs[a];
        rhs[a] = rhs[b];
        rhs[b] = u;
    }
}
