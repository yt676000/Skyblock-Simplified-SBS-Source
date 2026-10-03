/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import java.util.regex.Pattern;

/**
 * One entry of the scoreboard element catalogue, compiled and ready to match.
 *
 * <p>The runtime form of a {@link ScoreboardElementData.Entry}, plus the two kinds of element that
 * have no pattern at all: the layout pieces the player places themselves ({@link Kind#SPACER},
 * {@link Kind#SEPARATOR}), the mod's own rows ({@link Kind#SBS}), and the catch-all slot every
 * unrecognised line lands in ({@link Kind#UNRECOGNIZED}).
 *
 * @param id      the stable identity the saved layout is keyed on - never derived from {@link #name}
 * @param name    what the editor calls it; free to change without moving anything
 * @param note    why the row is sometimes absent ("The Garden only"), or {@code ""}
 * @param example a representative line for the editor's preview while the real row is absent
 * @param pattern what recognises the row, or {@code null} for the pattern-less kinds
 * @param body    whether the lines under it belong to it until the next blank row
 * @param weak    whether it recognises by shape, and so must never break an open block
 * @param kind    which family it belongs to - the editor and the layout both branch on this
 */
public record ScoreboardElement(String id, String name, String note, String example,
                                Pattern pattern, boolean body, boolean weak, Kind kind) {

    /** The families an element can belong to. */
    public enum Kind {

        /** Recognised from the server's own sidebar, via {@link ScoreboardElement#pattern}. */
        SERVER,

        /** A row SBS invented (Bank, Clock, Ping...), gated by its own setting. */
        SBS,

        /** A blank row the player placed for spacing. Repeatable. */
        SPACER,

        /** A thin rule the player placed to divide the panel. Repeatable. */
        SEPARATOR,

        /**
         * Where every line matching nothing goes. Positioned once, then it stays put - which is the
         * whole point: without a slot of its own an unrecognised line has nowhere to be but the
         * bottom, and it is re-appended there every time its text changes.
         */
        UNRECOGNIZED
    }

    /** Whether the same element may appear more than once in a layout (spacers and rules). */
    public boolean repeatable() {
        return kind == Kind.SPACER || kind == Kind.SEPARATOR;
    }

    /** Whether this element draws nothing but occupies a row (used to skip text measuring). */
    public boolean blankDrawing() {
        return kind == Kind.SPACER || kind == Kind.SEPARATOR;
    }

    /** A catalogue entry for a pattern-less kind. */
    static ScoreboardElement synthetic(String id, String name, String note, Kind kind) {
        return new ScoreboardElement(id, name, note, "", null, false, false, kind);
    }
}
