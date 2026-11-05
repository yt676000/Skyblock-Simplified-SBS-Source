/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reads the Tic Tac Toe wall into a 3x3 board. Pure: it knows block coordinates and opaque mark keys,
 * nothing about Minecraft, so the tests can feed it walls built by hand.
 *
 * <p><b>Input.</b> One {@link Spot} per thing seen on the wall: an empty cell (a button, key
 * {@link #EMPTY}) or a played cell (an item frame holding a map, key = whatever tells the two symbols
 * apart - the solver passes the map's centre pixel colour). A frame and a button reported for the same
 * block count as the frame.
 *
 * <p><b>Which symbol is whose is decided by counting, not by colour.</b> The computer opens every game,
 * so on the player's turn it has exactly one mark more than the player: the more frequent key is the
 * computer's. That keeps the reader free of colour constants nobody here has seen in 26.2. With equal
 * counts it is the computer's turn, and the played cells stay {@link #UNATTRIBUTED}.
 *
 * <p><b>Geometry.</b> The nine cells must lie in one vertical plane (constant x or constant z), on three
 * consecutive heights and three consecutive columns, all nine present. Anything else - a stray button
 * in the plane, a missing cell, two candidate planes, three symbols - reads as {@code null}: draw
 * nothing. {@link #lastReason()} says why.
 */
public final class TicTacToeGrid {

    /** Key of an unplayed cell. */
    public static final int EMPTY = Integer.MIN_VALUE;

    /** A played cell whose owner is not known (the computer's turn: counts are equal). */
    public static final byte UNATTRIBUTED = 3;

    /** Something seen on the wall at block {@code (x, y, z)}. */
    public record Spot(int x, int y, int z, int key) {
    }

    /**
     * A read board. Index {@code row * 3 + column}, row 0 the top; columns run along the wall in
     * ascending world coordinate (the game is symmetric under mirroring, so the direction changes no
     * answer).
     *
     * @param cells        {@link TicTacToeSearch#NONE}, {@link TicTacToeSearch#COMPUTER},
     *                     {@link TicTacToeSearch#PLAYER} or {@link #UNATTRIBUTED} per cell
     * @param playerToMove whether the computer has exactly one mark more than the player
     */
    public record Board(byte[] cells, boolean playerToMove, int[] x, int[] y, int[] z) {

        /** {@code "X O .|. X .|..."}-style: X the computer, O the player, ? unattributed, . empty. */
        public String render() {
            StringBuilder out = new StringBuilder(11);
            for (int i = 0; i < 9; i++) {
                if (i > 0 && i % 3 == 0) {
                    out.append('|');
                }
                out.append(symbol(cells[i]));
            }
            return out.toString();
        }

        private static char symbol(byte cell) {
            return switch (cell) {
                case TicTacToeSearch.COMPUTER -> 'X';
                case TicTacToeSearch.PLAYER -> 'O';
                case UNATTRIBUTED -> '?';
                default -> '.';
            };
        }
    }

    /** The outcome of one read: a board, or why there is none. */
    public record Result(Board board, String reason) {
    }

    private record Layout(int[] x, int[] y, int[] z, int[] keys) {
    }

    private TicTacToeGrid() {
    }

    /** Reads the wall. Never throws; an unreadable wall is a {@link Result} with a {@code null} board. */
    public static Result read(List<Spot> spots) {
        Map<Long, Spot> byBlock = new LinkedHashMap<>();
        for (Spot spot : spots) {
            long block = pack(spot.x(), spot.y(), spot.z());
            Spot known = byBlock.get(block);
            if (known == null || known.key() == EMPTY) {
                byBlock.put(block, spot);
            }
        }
        List<Spot> unique = new ArrayList<>(byBlock.values());

        Layout found = null;
        for (Spot candidate : unique) {
            for (int axis = 0; axis < 2; axis++) {
                Layout layout = inPlane(unique, axis, axis == 0 ? candidate.x() : candidate.z());
                if (layout == null || (found != null && sameBlocks(found, layout))) {
                    continue;
                }
                if (found != null) {
                    return new Result(null, "two candidate grids");
                }
                found = layout;
            }
        }
        if (found == null) {
            return new Result(null, "no plane holds a complete 3x3 grid (" + unique.size() + " spot(s))");
        }
        return attribute(found);
    }

    private static Layout inPlane(List<Spot> spots, int axis, int plane) {
        List<Spot> on = new ArrayList<>(9);
        TreeSet<Integer> heights = new TreeSet<>();
        TreeSet<Integer> along = new TreeSet<>();
        for (Spot spot : spots) {
            if ((axis == 0 ? spot.x() : spot.z()) != plane) {
                continue;
            }
            on.add(spot);
            heights.add(spot.y());
            along.add(axis == 0 ? spot.z() : spot.x());
        }
        if (on.size() != 9 || heights.size() != 3 || along.size() != 3
                || heights.last() - heights.first() != 2 || along.last() - along.first() != 2) {
            return null;
        }
        Layout layout = new Layout(new int[9], new int[9], new int[9], new int[9]);
        for (Spot spot : on) {
            int row = heights.last() - spot.y();
            int column = (axis == 0 ? spot.z() : spot.x()) - along.first();
            int i = row * 3 + column;
            layout.x()[i] = spot.x();
            layout.y()[i] = spot.y();
            layout.z()[i] = spot.z();
            layout.keys()[i] = spot.key();
        }
        return layout;
    }

    /** Names the computer's symbol by count; see the class note. */
    private static Result attribute(Layout layout) {
        int first = EMPTY;
        int second = EMPTY;
        int firstCount = 0;
        int secondCount = 0;
        for (int key : layout.keys()) {
            if (key == EMPTY) {
                continue;
            }
            if (first == EMPTY || key == first) {
                first = key;
                firstCount++;
            } else if (second == EMPTY || key == second) {
                second = key;
                secondCount++;
            } else {
                return new Result(null, "three different symbols on the wall");
            }
        }
        byte[] cells = new byte[9];
        int computerKey;
        if (firstCount == secondCount + 1) {
            computerKey = first;
        } else if (secondCount == firstCount + 1) {
            computerKey = second;
        } else if (firstCount == secondCount) {
            for (int i = 0; i < 9; i++) {
                cells[i] = layout.keys()[i] == EMPTY ? TicTacToeSearch.NONE : UNATTRIBUTED;
            }
            return new Result(new Board(cells, false, layout.x(), layout.y(), layout.z()),
                    "computer to move");
        } else {
            return new Result(null, "counts " + firstCount + "/" + secondCount
                    + " fit no turn order (the computer opens)");
        }
        for (int i = 0; i < 9; i++) {
            int key = layout.keys()[i];
            cells[i] = key == EMPTY ? TicTacToeSearch.NONE
                    : key == computerKey ? TicTacToeSearch.COMPUTER : TicTacToeSearch.PLAYER;
        }
        return new Result(new Board(cells, true, layout.x(), layout.y(), layout.z()), "player to move");
    }

    private static boolean sameBlocks(Layout a, Layout b) {
        return Arrays.equals(a.x(), b.x()) && Arrays.equals(a.y(), b.y()) && Arrays.equals(a.z(), b.z());
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }
}
