/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Boulder puzzle as pure data: a grid of boulder slots, each holding a boulder or empty, and a
 * breadth-first search over single pushes for the shortest sequence that opens a walkable path from
 * the entry side to the far (chest) side. No Minecraft types, so every rule here is unit-tested.
 *
 * <p><b>Frame.</b> Row 0 is the entry side, row {@link #ROWS}-1 the chest side; the glue flips the
 * room's grid so this holds. Columns run across. A board is a {@code long} bit mask, bit
 * {@code row * COLS + col} set = boulder.
 *
 * <p><b>Rules modelled</b> (wiki and estimated geometry, unverified in 26.2, see the spec - the side
 * lanes and pushing out of the grid are not verified in 26.2):
 * <ul>
 *   <li>A push moves one boulder one slot, away from the side it was pushed from. The target slot
 *       must be inside the grid and empty.</li>
 *   <li>The player pushes from the slot behind the boulder, which must be reachable: an empty slot
 *       connected to the entry side, or - for a row-0 boulder pushed inward - the entry corridor
 *       itself. The grid's left, right and far edges are treated as walls.</li>
 *   <li>Solved = an empty 4-connected path from row 0 to the last row.</li>
 * </ul>
 */
public final class BoulderBoard {

    public static final int COLS = 7;
    public static final int ROWS = 6;
    public static final int CELLS = COLS * ROWS;

    /** Default search caps: the state space is small, but a bad read must never stall a tick. */
    public static final int DEFAULT_MAX_STATES = 200_000;
    public static final int DEFAULT_MAX_DEPTH = 24;

    /** A push direction in the board frame. FORWARD moves a boulder towards the chest side. */
    public enum Push {
        FORWARD(1, 0), BACK(-1, 0), RIGHT(0, 1), LEFT(0, -1);

        public final int dRow;
        public final int dCol;

        Push(int dRow, int dCol) {
            this.dRow = dRow;
            this.dCol = dCol;
        }
    }

    /** One push: the boulder at (row, col) is moved one slot in {@code push}. */
    public record Move(int row, int col, Push push) {
        @Override
        public String toString() {
            return "(" + row + "," + col + ")" + push;
        }
    }

    public enum Status {
        /** The path is already open; nothing to push. */
        OPEN,
        /** {@link Result#moves()} opens the path. */
        SOLVED,
        /** Every reachable board was searched and none opens the path. */
        NO_SOLUTION,
        /** The search hit its state or depth cap first. Draw nothing. */
        CAP_HIT
    }

    public record Result(Status status, List<Move> moves, int statesSearched) {
    }

    private BoulderBoard() {
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * A board from rows of text, row 0 (entry) first: {@code '#'} boulder, anything else empty. The
     * form the solver logs, so a logged board pastes straight into a test.
     */
    public static long parse(String... rows) {
        if (rows.length != ROWS) {
            throw new IllegalArgumentException("need " + ROWS + " rows, got " + rows.length);
        }
        long mask = 0;
        for (int r = 0; r < ROWS; r++) {
            if (rows[r].length() != COLS) {
                throw new IllegalArgumentException("row " + r + " needs " + COLS + " cells");
            }
            for (int c = 0; c < COLS; c++) {
                if (rows[r].charAt(c) == '#') {
                    mask |= bit(r, c);
                }
            }
        }
        return mask;
    }

    /** A board from per-slot samples in board order ({@code row * COLS + col}). */
    public static long fromSamples(boolean[] occupied) {
        long mask = 0;
        for (int i = 0; i < CELLS; i++) {
            if (occupied[i]) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    /** The board as {@link #parse} text, rows joined by {@code '/'}, for one log line. */
    public static String format(long mask) {
        StringBuilder sb = new StringBuilder(CELLS + ROWS);
        for (int r = 0; r < ROWS; r++) {
            if (r > 0) {
                sb.append('/');
            }
            for (int c = 0; c < COLS; c++) {
                sb.append(occupied(mask, r, c) ? '#' : '.');
            }
        }
        return sb.toString();
    }

    public static boolean occupied(long mask, int row, int col) {
        return (mask & bit(row, col)) != 0;
    }

    private static long bit(int row, int col) {
        return 1L << (row * COLS + col);
    }

    private static boolean inside(int row, int col) {
        return row >= 0 && row < ROWS && col >= 0 && col < COLS;
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /** Empty slots the player can walk to from the entry side, as a bit mask. */
    public static long reachable(long mask) {
        long seen = 0;
        int[] stack = new int[CELLS];
        int top = 0;
        for (int c = 0; c < COLS; c++) {
            if (!occupied(mask, 0, c)) {
                seen |= bit(0, c);
                stack[top++] = c;
            }
        }
        while (top > 0) {
            int i = stack[--top];
            int r = i / COLS;
            int c = i % COLS;
            for (Push p : Push.values()) {
                int nr = r + p.dRow;
                int nc = c + p.dCol;
                if (inside(nr, nc) && !occupied(mask, nr, nc) && (seen & bit(nr, nc)) == 0) {
                    seen |= bit(nr, nc);
                    stack[top++] = nr * COLS + nc;
                }
            }
        }
        return seen;
    }

    /** Whether an empty path already joins the entry row to the chest row. */
    public static boolean open(long mask) {
        long lastRow = ((1L << COLS) - 1) << ((ROWS - 1) * COLS);
        return (reachable(mask) & lastRow) != 0;
    }

    /** The board after {@code move}, or {@code -1} when the move is not legal on {@code mask}. */
    public static long apply(long mask, Move move) {
        return apply(mask, reachable(mask), move.row(), move.col(), move.push());
    }

    private static long apply(long mask, long reach, int r, int c, Push p) {
        if (!inside(r, c) || !occupied(mask, r, c)) {
            return -1;
        }
        int tr = r + p.dRow;
        int tc = c + p.dCol;
        if (!inside(tr, tc) || occupied(mask, tr, tc)) {
            return -1;
        }
        int sr = r - p.dRow;
        int sc = c - p.dCol;
        boolean standable = inside(sr, sc) ? (reach & bit(sr, sc)) != 0 : sr == -1 && sc >= 0 && sc < COLS;
        if (!standable) {
            return -1;
        }
        return (mask & ~bit(r, c)) | bit(tr, tc);
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    public static Result solve(long mask) {
        return solve(mask, DEFAULT_MAX_STATES, DEFAULT_MAX_DEPTH);
    }

    /**
     * Shortest push sequence that opens the path, by breadth-first search. Bounded by
     * {@code maxStates} distinct boards and {@code maxDepth} pushes; hitting either returns
     * {@link Status#CAP_HIT}, never a partial answer.
     */
    public static Result solve(long start, int maxStates, int maxDepth) {
        if (open(start)) {
            return new Result(Status.OPEN, List.of(), 1);
        }
        // board -> (parent board << 8 | encoded move); start maps to -1
        Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        Long2LongOpenHashMap depth = new Long2LongOpenHashMap();
        parent.put(start, -1L);
        depth.put(start, 0L);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start);
        boolean capped = false;
        while (!queue.isEmpty()) {
            long board = queue.dequeueLong();
            long d = depth.get(board);
            if (d >= maxDepth) {
                capped = true;
                continue;
            }
            long reach = reachable(board);
            for (int i = 0; i < CELLS; i++) {
                if ((board & (1L << i)) == 0) {
                    continue;
                }
                int r = i / COLS;
                int c = i % COLS;
                for (Push p : Push.values()) {
                    long next = apply(board, reach, r, c, p);
                    if (next < 0 || parent.containsKey(next)) {
                        continue;
                    }
                    if (parent.size() >= maxStates) {
                        return new Result(Status.CAP_HIT, List.of(), parent.size());
                    }
                    parent.put(next, (board << 8) | encode(r, c, p));
                    depth.put(next, d + 1);
                    if (open(next)) {
                        return new Result(Status.SOLVED, path(parent, next), parent.size());
                    }
                    queue.enqueue(next);
                }
            }
        }
        return new Result(capped ? Status.CAP_HIT : Status.NO_SOLUTION, List.of(), parent.size());
    }

    // Boards use 42 bits, so (board << 8 | move) fits a long; a move is cell * 4 + push.
    private static long encode(int r, int c, Push p) {
        return (long) (r * COLS + c) * 4 + p.ordinal();
    }

    private static List<Move> path(Long2LongOpenHashMap parent, long end) {
        List<Move> moves = new ArrayList<>();
        long board = end;
        long link = parent.get(board);
        while (link != -1L) {
            int code = (int) (link & 0xFF);
            int cell = code / 4;
            moves.add(new Move(cell / COLS, cell % COLS, Push.values()[code % 4]));
            board = link >>> 8;
            link = parent.get(board);
        }
        Collections.reverse(moves);
        return List.copyOf(moves);
    }
}
