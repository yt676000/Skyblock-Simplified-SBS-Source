/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

/**
 * Exact game-tree search for the player's next mark. Pure and allocation-light: the board is a
 * {@code byte[9]} that is played into and undone in place.
 *
 * <p>The whole tree from an empty board is under 550,000 nodes, and the solver only searches from
 * positions where the computer has already opened, so a full search is a few milliseconds at worst and
 * runs once per board change - no table, no pruning, nothing to get wrong.
 *
 * <p>Scoring prefers a win (sooner is better), then a draw, then a loss (later is better). The room is
 * passed by a draw ({@code "... tied Tic Tac Toe!"}); preferring a win where one exists cannot turn a
 * draw into a loss, because a move scored as a win is a forced win.
 */
public final class TicTacToeSearch {

    public static final byte NONE = 0;
    public static final byte COMPUTER = 1;
    public static final byte PLAYER = 2;

    /** The eight lines, as cell indices. */
    private static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private TicTacToeSearch() {
    }

    /** The side with three in a row, or {@link #NONE}. */
    public static byte winner(byte[] cells) {
        for (int[] line : LINES) {
            byte a = cells[line[0]];
            if (a != NONE && a == cells[line[1]] && a == cells[line[2]]) {
                return a;
            }
        }
        return NONE;
    }

    public static boolean full(byte[] cells) {
        for (byte cell : cells) {
            if (cell == NONE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every best move for {@code side}, as a bit mask over cell indices ({@code 1 << i}). Zero when the
     * game is already over. The input is left unchanged.
     */
    public static int bestMoves(byte[] cells, byte side) {
        if (winner(cells) != NONE || full(cells)) {
            return 0;
        }
        byte[] work = cells.clone();
        int best = Integer.MIN_VALUE;
        int mask = 0;
        for (int i = 0; i < 9; i++) {
            if (work[i] != NONE) {
                continue;
            }
            work[i] = side;
            int score = -negamax(work, other(side), 1);
            work[i] = NONE;
            if (score > best) {
                best = score;
                mask = 1 << i;
            } else if (score == best) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /**
     * The value of the position for the side to move: {@code 10 - depth} for a win, {@code depth - 10}
     * for a loss, 0 for a draw.
     */
    private static int negamax(byte[] work, byte toMove, int depth) {
        if (winner(work) != NONE) {
            return depth - 10;   // the side that just moved has won, so the side to move has lost
        }
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < 9; i++) {
            if (work[i] != NONE) {
                continue;
            }
            work[i] = toMove;
            int score = -negamax(work, other(toMove), depth + 1);
            work[i] = NONE;
            if (score > best) {
                best = score;
            }
        }
        return best == Integer.MIN_VALUE ? 0 : best;
    }

    public static byte other(byte side) {
        return side == PLAYER ? COMPUTER : PLAYER;
    }
}
