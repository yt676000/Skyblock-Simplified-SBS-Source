/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import sbs.modid.client.dungeons.terminal.TerminalSolutions.Hint;
import sbs.modid.client.ui.render.MarkKind;

/**
 * Translates the solver's {@link Hint}s into the shared mark language and words, without touching
 * the solve: the hint keeps its raw colour (which the solver tests compare), and this class reads
 * what that colour <i>meant</i>. Pure - no rendering, no Minecraft state - so it is unit-tested.
 */
public final class TerminalMarks {

    private TerminalMarks() {
    }

    /** The mark kind a hint is drawn as. */
    public static MarkKind kindOf(Hint hint) {
        if (hint.preview()) {
            return MarkKind.NEXT;
        }
        if (hint.color() == TerminalSolutions.WAIT) {
            return MarkKind.WAIT;
        }
        if (hint.color() == TerminalSolutions.RIGHT_CLICK) {
            return MarkKind.ALT;
        }
        if ("!".equals(hint.label())) {
            return MarkKind.NOW; // melody: the note is on the marked column
        }
        return MarkKind.DO;
    }

    /**
     * The count badge for a hint, or {@code null} for none. The order terminal's number is already
     * printed in its cell (it is the item), and melody's "!" is replaced by the pulse; only rubix
     * carries a real count. A right-click count drops its minus sign - the ALT shape and its
     * top-left badge already say "the other way".
     */
    public static String badgeOf(TerminalType type, Hint hint) {
        String label = hint.label();
        if (label == null || label.isEmpty() || hint.preview() || type != TerminalType.RUBIX) {
            return null;
        }
        return label.startsWith("-") ? label.substring(1) : label;
    }

    /** Board title: the terminal's name in our words. */
    public static String nameOf(TerminalType type) {
        return switch (type) {
            case ORDER -> "Numbers";
            case COLOR -> "Select Colour";
            case STARTS_WITH -> "Starts With";
            case PANES -> "Correct Panes";
            case RUBIX -> "Same Colour";
            case MELODY -> "Melody";
            case ITEM_NAME -> "Click Item";
            case NONE -> "";
        };
    }

    /** The one-line instruction under the title. Shapes, not colours, so it reads colour-blind. */
    public static String instructionOf(TerminalType type) {
        return switch (type) {
            case ORDER -> "Click the filled number; the ring is next";
            case COLOR, STARTS_WITH, ITEM_NAME -> "Click every filled cell";
            case PANES -> "Flip every filled pane";
            case RUBIX -> "Left-click filled cells, right-click notched ones, as often as shown";
            case MELODY -> "Click your button when it pulses";
            case NONE -> "";
        };
    }

    /**
     * Progress as {@code done} out of {@code total}, packed into one long ({@code done << 32 | total})
     * so the per-frame caller allocates nothing. {@code total == 0} means "no progress to show".
     *
     * @param doneCount  cells already done (selected items, flipped panes); for ORDER the lowest
     *                   number still due minus one
     * @param todoCount  cells still to do (the non-preview hints; for ORDER the remaining numbered panes)
     */
    public static long progress(TerminalType type, int doneCount, int todoCount) {
        return switch (type) {
            case ORDER, COLOR, STARTS_WITH, PANES, ITEM_NAME -> {
                int done = Math.max(0, doneCount);
                int total = done + Math.max(0, todoCount);
                yield total <= 0 ? 0L : ((long) done << 32) | total;
            }
            case RUBIX, MELODY, NONE -> 0L;
        };
    }

    public static int done(long progress) {
        return (int) (progress >>> 32);
    }

    public static int total(long progress) {
        return (int) progress;
    }

    /** Whether the board is finished: something to do existed, and all of it is done. */
    public static boolean complete(long progress) {
        return total(progress) > 0 && done(progress) >= total(progress);
    }
}
