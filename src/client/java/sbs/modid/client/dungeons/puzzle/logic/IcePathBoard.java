/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/**
 * Ice Path, the pure half: a grid of open and blocked cells, a slide rule, and a breadth-first search
 * for the fewest hits that bring the silverfish to rest on the goal cell. No Minecraft types, so the
 * whole of it is unit-tested.
 *
 * <p><b>Rule modelled</b> ({@code WIKI}, Catacombs "Ice Path"; never watched in 26.2): hitting the
 * silverfish sends it away from the player in one of four directions, and it slides until the next
 * cell is blocked or off the grid. The search therefore runs over resting cells only, one edge per
 * direction. A hit that does not move it is not an edge.
 *
 * <p>The goal counts only when the silverfish <b>stops</b> on it. Passing over it mid-slide is not
 * treated as reaching it, because whether the room accepts a pass-through is unverified, and the
 * stricter reading can only cost an extra hit, never a wrong one.
 *
 * <p>Cells are indexed {@code z * width + x}; x grows east, z grows south, as in the world.
 */
public final class IcePathBoard {

    /** Directions, in the order the search tries them. */
    public static final int NORTH = 0;
    public static final int EAST = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 3;

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};
    private static final char[] LETTER = {'N', 'E', 'S', 'W'};

    private final int width;
    private final int depth;
    private final boolean[] blocked;

    public IcePathBoard(int width, int depth, boolean[] blocked) {
        if (width <= 0 || depth <= 0 || blocked.length != width * depth) {
            throw new IllegalArgumentException("board " + width + "x" + depth + " with " + blocked.length + " cells");
        }
        this.width = width;
        this.depth = depth;
        this.blocked = blocked.clone();
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public int index(int x, int z) {
        return x < 0 || z < 0 || x >= width || z >= depth ? -1 : z * width + x;
    }

    public boolean open(int cell) {
        return cell >= 0 && cell < blocked.length && !blocked[cell];
    }

    /** Where a silverfish resting on {@code cell} stops after a hit in {@code dir}. */
    public int slide(int cell, int dir) {
        int x = cell % width;
        int z = cell / width;
        while (true) {
            int next = index(x + DX[dir], z + DZ[dir]);
            if (next < 0 || blocked[next]) {
                return z * width + x;
            }
            x += DX[dir];
            z += DZ[dir];
        }
    }

    /**
     * The fewest hits from {@code start} that leave the silverfish resting on {@code goal}, as
     * direction constants, or {@code null} when the goal cannot be reached. An empty array means it
     * is already there.
     */
    public int[] solve(int start, int goal) {
        if (!open(start) || !open(goal)) {
            return null;
        }
        if (start == goal) {
            return new int[0];
        }
        int[] from = new int[blocked.length];
        int[] via = new int[blocked.length];
        Arrays.fill(from, -1);
        from[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (int dir = 0; dir < 4; dir++) {
                int stop = slide(cell, dir);
                if (stop == cell || from[stop] >= 0) {
                    continue;
                }
                from[stop] = cell;
                via[stop] = dir;
                if (stop == goal) {
                    return path(from, via, start, goal);
                }
                queue.add(stop);
            }
        }
        return null;
    }

    /**
     * The exit: open cells outside the board's core rectangle. The core is the rows and columns
     * holding at least half as many open cells as the fullest one; what is open beyond it is the
     * pocket the fish escapes into. In the maintainer's 2026-10-17 room that was a 3-wide opening on
     * the board's edge, and the fish vanished on its first cell. Empty when the board has none.
     */
    public boolean[] exitCells() {
        int[] rowOpen = new int[depth];
        int[] colOpen = new int[width];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                if (!blocked[z * width + x]) {
                    rowOpen[z]++;
                    colOpen[x]++;
                }
            }
        }
        int maxRow = Arrays.stream(rowOpen).max().orElse(0);
        int maxCol = Arrays.stream(colOpen).max().orElse(0);
        boolean[] exit = new boolean[blocked.length];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int i = z * width + x;
                boolean core = rowOpen[z] * 2 >= maxRow && colOpen[x] * 2 >= maxCol;
                exit[i] = !blocked[i] && !core;
            }
        }
        return exit;
    }

    /**
     * The fewest hits from {@code start} that carry the fish into any {@code exit} cell - entering
     * one mid-slide counts, the exit swallows it - as direction constants, or {@code null} when no
     * hit sequence gets there. Each step's resting cell is written to {@code stops} when non-null
     * (the last entry is the exit cell reached).
     */
    public int[] solveToExit(int start, boolean[] exit, List<Integer> stops) {
        if (!open(start)) {
            return null;
        }
        int[] from = new int[blocked.length];
        int[] via = new int[blocked.length];
        Arrays.fill(from, -1);
        from[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (int dir = 0; dir < 4; dir++) {
                int reached = exitAlong(cell, dir, exit);
                int stop = reached >= 0 ? reached : slide(cell, dir);
                if (stop == cell || from[stop] >= 0) {
                    continue;
                }
                from[stop] = cell;
                via[stop] = dir;
                if (reached >= 0) {
                    int[] dirs = path(from, via, start, stop);
                    if (stops != null) {
                        stops.clear();
                        int c = start;
                        for (int d : dirs) {
                            int e = exitAlong(c, d, exit);
                            c = e >= 0 ? e : slide(c, d);
                            stops.add(c);
                        }
                    }
                    return dirs;
                }
                queue.add(stop);
            }
        }
        return null;
    }

    /** The first exit cell a slide from {@code cell} in {@code dir} enters, or -1. */
    private int exitAlong(int cell, int dir, boolean[] exit) {
        int x = cell % width;
        int z = cell / width;
        while (true) {
            int next = index(x + DX[dir], z + DZ[dir]);
            if (next < 0 || blocked[next]) {
                return -1;
            }
            if (exit[next]) {
                return next;
            }
            x += DX[dir];
            z += DZ[dir];
        }
    }

    private static int[] path(int[] from, int[] via, int start, int goal) {
        int length = 0;
        for (int cell = goal; cell != start; cell = from[cell]) {
            length++;
        }
        int[] dirs = new int[length];
        for (int cell = goal, i = length - 1; cell != start; cell = from[cell], i--) {
            dirs[i] = via[cell];
        }
        return dirs;
    }

    /** {@code "ENS"}-style text for a move list, for the log. */
    public static String describe(int[] dirs) {
        if (dirs == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(dirs.length);
        for (int dir : dirs) {
            sb.append(LETTER[dir]);
        }
        return sb.toString();
    }

    /** Rows of {@code #} (blocked) and {@code .} (open), north first - the probe's dump format. */
    public String dump() {
        StringBuilder sb = new StringBuilder(depth * (width + 1));
        for (int z = 0; z < depth; z++) {
            if (z > 0) {
                sb.append('/');
            }
            for (int x = 0; x < width; x++) {
                sb.append(blocked[z * width + x] ? '#' : '.');
            }
        }
        return sb.toString();
    }

    /**
     * Reads {@link #dump()} text back; {@code S} and {@code G} are open cells whose indices are
     * returned in {@link Parsed}. Used by tests, and so a logged layout pastes straight into one.
     */
    public static Parsed parse(String text) {
        String[] rows = text.split("/");
        int w = rows[0].length();
        boolean[] cells = new boolean[w * rows.length];
        int start = -1;
        int goal = -1;
        for (int z = 0; z < rows.length; z++) {
            if (rows[z].length() != w) {
                throw new IllegalArgumentException("ragged row " + z);
            }
            for (int x = 0; x < w; x++) {
                char c = rows[z].charAt(x);
                int i = z * w + x;
                switch (c) {
                    case '#' -> cells[i] = true;
                    case '.' -> { }
                    case 'S' -> start = i;
                    case 'G' -> goal = i;
                    default -> throw new IllegalArgumentException("cell '" + c + "'");
                }
            }
        }
        return new Parsed(new IcePathBoard(w, rows.length, cells), start, goal);
    }

    /** A parsed layout with its marked start and goal ({@code -1} when unmarked). */
    public record Parsed(IcePathBoard board, int start, int goal) {
    }
}
