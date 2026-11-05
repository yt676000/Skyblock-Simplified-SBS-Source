/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import java.util.List;

/**
 * One read of the tab list's {@code Puzzles: (N)} section. Immutable: the reader replaces it whole
 * when the section changes, so a consumer can cache anything derived from it against the instance.
 *
 * @param total      the count the header printed, or {@code -1} when there was no header
 * @param entries    the rows parsed, in tab order; at most {@code total} of them
 * @param rows       the same rows exactly as {@code TabWidgets.lines()} gave them (stripped, trimmed)
 * @param firstRow   the index of the first row in that list, so the reader can pair each row with
 *                   its coloured component for the capture log; {@code -1} without a header
 */
public record PuzzleTabSection(int total, List<PuzzleEntry> entries, List<String> rows, int firstRow) {

    /** No header in the tab: not in a run, or the widget is switched off. */
    public static final PuzzleTabSection ABSENT = new PuzzleTabSection(-1, List.of(), List.of(), -1);

    public boolean present() {
        return total >= 0;
    }

    /** The reported state of one puzzle, or {@link PuzzleStatus#UNKNOWN} when no row named it. */
    public PuzzleStatus statusOf(PuzzleType type) {
        for (PuzzleEntry entry : entries) {
            if (entry.type() == type) {
                return entry.status();
            }
        }
        return PuzzleStatus.UNKNOWN;
    }
}
