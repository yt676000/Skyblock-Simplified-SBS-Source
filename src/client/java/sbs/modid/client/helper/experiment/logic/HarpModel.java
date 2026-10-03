/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Melody's Harp, the decisions only: board -> notes, consecutive boards -> the step interval, and
 * when each note reaches the hit row. No game types, so it is unit-tested on captured boards.
 *
 * <p><b>The board (CONFIRMED, Layout Recorder 2026-10-01).</b> A 6x9 chest titled
 * {@code "Harp - <Song>"}. Columns 0 and 8 are black panes; columns 1-7 are the seven lanes. Rows
 * 0-3 and 5 are lane-coloured panes, row 4 is lane-coloured terracotta, and a note is lane-coloured
 * wool in place of a pane. Notes were seen in rows 0, 1, 2, 3 and 5, never in row 4.
 *
 * <p><b>UNVERIFIED:</b> which row a click counts in, and the step time. The default hit row is 4
 * (the terracotta, the only row Hypixel marks), and the step is measured, never assumed: until two
 * steps have been seen there is no prediction at all.
 */
public final class HarpModel {

    public static final int ROWS = 6;
    public static final int LANES = 7;
    public static final char WOOL = 'W';
    public static final char TERRACOTTA = 'T';
    public static final char PANE = '.';

    /** A step longer than this is a pause (song start, lag), not a step. */
    static final long MAX_STEP_MS = 2_000L;
    private static final int SAMPLES = 8;

    /** One note: its lane (0-6, left to right) and row (0 top). */
    public record Note(int lane, int row) {
    }

    /** A note with the time it is predicted to reach the hit row. */
    public record Arrival(Note note, long atMs) {
    }

    private char[][] last;
    private long lastStepAt = -1;
    private final Deque<Long> intervals = new ArrayDeque<>();

    // ------------------------------------------------------------------ board

    /**
     * The 6x7 lane grid from the chest's 54 item ids ({@code "minecraft:lime_wool"} ...), or
     * {@code null} when the list is not a 54-slot board.
     */
    public static char[][] grid(List<String> ids) {
        if (ids == null || ids.size() < ROWS * 9) {
            return null;
        }
        char[][] grid = new char[ROWS][LANES];
        for (int row = 0; row < ROWS; row++) {
            for (int lane = 0; lane < LANES; lane++) {
                String id = ids.get(row * 9 + lane + 1);
                id = id == null ? "" : id;
                grid[row][lane] = id.endsWith("_wool") ? WOOL
                        : id.endsWith("_terracotta") ? TERRACOTTA : PANE;
            }
        }
        return grid;
    }

    /** "...W...|.......|..." - rows top to bottom, for the log and the tests. */
    public static String compact(char[][] grid) {
        StringBuilder out = new StringBuilder(ROWS * (LANES + 1));
        for (int row = 0; row < ROWS; row++) {
            if (row > 0) {
                out.append('|');
            }
            out.append(grid[row]);
        }
        return out.toString();
    }

    /** {@link #compact} read back. */
    public static char[][] parse(String compact) {
        String[] rows = compact.split("\\|");
        char[][] grid = new char[ROWS][];
        for (int row = 0; row < ROWS; row++) {
            grid[row] = rows[row].toCharArray();
        }
        return grid;
    }

    public static List<Note> notes(char[][] grid) {
        List<Note> out = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int lane = 0; lane < LANES; lane++) {
                if (grid[row][lane] == WOOL) {
                    out.add(new Note(lane, row));
                }
            }
        }
        return out;
    }

    /**
     * Whether {@code next} is {@code prev} moved down one row: at least one note continues from the
     * row above in its lane, and none appeared below row 0 from nowhere. A board where only new
     * notes entered at the top is not counted - it cannot be told from a note being hit. Row 5 may continue from
     * row 3 too, since row 4 never shows wool.
     */
    static boolean isStep(char[][] prev, char[][] next) {
        int continued = 0;
        for (Note note : notes(next)) {
            if (note.row() == 0) {
                continue;   // a new note entering at the top
            }
            boolean fromAbove = prev[note.row() - 1][note.lane()] == WOOL
                    || (note.row() == 5 && prev[3][note.lane()] == WOOL);
            if (!fromAbove) {
                return false;
            }
            continued++;
        }
        return continued > 0;
    }

    // ------------------------------------------------------------------ timing

    /** Feeds one board seen at {@code nowMs}. Returns whether it was a step. */
    public boolean onFrame(char[][] grid, long nowMs) {
        if (grid == null) {
            return false;
        }
        boolean step = false;
        if (last != null && !Arrays.deepEquals(last, grid) && isStep(last, grid)) {
            step = true;
            if (lastStepAt >= 0) {
                long gap = nowMs - lastStepAt;
                if (gap > 0 && gap <= MAX_STEP_MS) {
                    intervals.addLast(gap);
                    if (intervals.size() > SAMPLES) {
                        intervals.removeFirst();
                    }
                }
            }
            lastStepAt = nowMs;
        }
        if (last == null || !Arrays.deepEquals(last, grid)) {
            last = copy(grid);
        }
        return step;
    }

    /** The measured step in ms (median of the last few), or -1 before two steps were seen. */
    public long stepMs() {
        if (intervals.isEmpty()) {
            return -1;
        }
        long[] sorted = intervals.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    /**
     * For each lane, the next note at or above {@code hitRow} and when it reaches it; lanes with no
     * such note are left out. Empty until the step has been measured.
     */
    public List<Arrival> nextArrivals(int hitRow) {
        List<Arrival> out = new ArrayList<>();
        long step = stepMs();
        if (last == null || step <= 0 || lastStepAt < 0) {
            return out;
        }
        for (int lane = 0; lane < LANES; lane++) {
            for (int row = Math.min(hitRow, ROWS - 1); row >= 0; row--) {
                if (last[row][lane] == WOOL) {
                    out.add(new Arrival(new Note(lane, row), lastStepAt + (hitRow - row) * step));
                    break;
                }
            }
        }
        return out;
    }

    /** Every note on the current board. */
    public List<Note> notes() {
        return last == null ? List.of() : notes(last);
    }

    public void reset() {
        last = null;
        lastStepAt = -1;
        intervals.clear();
    }

    /**
     * The click lead: one round trip (the board reaches you one way late and your click reaches the
     * server one way late) plus the reaction time.
     */
    public static long leadMs(int pingMs, int reactionMs) {
        return Math.max(0, pingMs) + Math.max(0, reactionMs);
    }

    private static char[][] copy(char[][] grid) {
        char[][] out = new char[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            out[i] = grid[i].clone();
        }
        return out;
    }
}
