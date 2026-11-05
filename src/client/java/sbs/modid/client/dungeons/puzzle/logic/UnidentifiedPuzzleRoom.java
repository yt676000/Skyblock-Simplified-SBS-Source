/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.client.dungeons.puzzle.model.PuzzleType;

/**
 * Names a puzzle room the room database could not match, from the one mob that only that puzzle
 * room has: a blaze (Higher or Lower), a creeper (Creeper Beams) or a silverfish (Ice Path). Only
 * for a <b>purple</b> room, and only when exactly one of the three is present - anything else is
 * left unnamed rather than guessed.
 *
 * <p>Needed because the database scans of several puzzle rooms cannot identify an unsolved room:
 * Higher or Lower and Teleport Maze store the reward chest, which appears only once the puzzle is
 * solved, and Creeper stores a single block every puzzle room shares. On 2026-10-15 a blaze room
 * stayed unidentified until it was already solved, and a Teleport Maze was taken for Creeper. Pure,
 * so it is unit-tested.
 */
public final class UnidentifiedPuzzleRoom {

    private UnidentifiedPuzzleRoom() {
    }

    /** Levers it takes: the Water Board room has seven (maintainer's log, 2026-10-16). */
    static final int WATER_BOARD_LEVERS = 6;

    /**
     * Water Board from its levers: a 1x1 room, purple or not painted on the map yet, with at least
     * {@link #WATER_BOARD_LEVERS} levers. Needed because the room went unidentified in two of three
     * visits on 2026-10-16, both times before the map had coloured its tile, and no mob names it.
     */
    public static PuzzleType guessByLevers(String mapColor, String shape, int levers) {
        boolean color = mapColor == null || "purple".equals(mapColor);
        return color && "1x1".equals(shape) && levers >= WATER_BOARD_LEVERS ? PuzzleType.WATER_BOARD : null;
    }

    /** The puzzle this unidentified room must be, or {@code null} when nothing settles it. */
    public static PuzzleType guess(String mapColor, boolean blaze, boolean creeper, boolean silverfish) {
        if (!"purple".equals(mapColor)) {
            return null;
        }
        int seen = (blaze ? 1 : 0) + (creeper ? 1 : 0) + (silverfish ? 1 : 0);
        if (seen != 1) {
            return null;
        }
        return blaze ? PuzzleType.HIGHER_OR_LOWER : creeper ? PuzzleType.CREEPER_BEAMS : PuzzleType.ICE_PATH;
    }
}
