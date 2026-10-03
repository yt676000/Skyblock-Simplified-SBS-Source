/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

/**
 * One puzzle as the tab list reports it for the current run: which puzzle, what state it is in, and
 * - when it has been failed - who the game named.
 *
 * @param type     the recognised puzzle, or {@code null} when the row named something this build
 *                 does not know (a new room, or a row shape the parser read wrongly)
 * @param rawName  the name exactly as the tab printed it, kept for the diagnostics log so an
 *                 unrecognised puzzle can be identified from a real run rather than guessed at
 * @param status   incomplete / complete / failed, or {@link PuzzleStatus#UNKNOWN}
 * @param failedBy the player the tab blamed for a failure, or {@code null}. Never inferred - it is
 *                 either printed or it is not known
 */
public record PuzzleEntry(PuzzleType type, String rawName, PuzzleStatus status, String failedBy) {

    /** The name to show the player: the official one when recognised, else whatever the tab said. */
    public String displayName() {
        return type != null ? type.displayName() : rawName;
    }
}
