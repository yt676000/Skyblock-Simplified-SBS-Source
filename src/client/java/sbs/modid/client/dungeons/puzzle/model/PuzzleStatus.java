/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

/**
 * The state the tab list reports for one puzzle in the run.
 *
 * <p>{@link #UNKNOWN} is not a failure mode to be tidied away - it is the honest answer whenever the
 * row was found but its state marker was not, and it is what every consumer must be able to show.
 * A puzzle silently presented as incomplete when its state was never actually read is the kind of
 * confident-wrong display this mod's rules exist to prevent.
 */
public enum PuzzleStatus {

    /** Not solved yet. */
    INCOMPLETE,
    /** Solved. */
    COMPLETE,
    /** Failed - the 14-point Skill penalty has already been taken. */
    FAILED,
    /** The row was read but its state was not. Show as unknown; never round to incomplete. */
    UNKNOWN
}
